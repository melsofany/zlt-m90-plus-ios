package com.zltm90plus.app

import com.zltm90plus.app.data.remote.RouterProbe
import com.zltm90plus.app.data.remote.RouterRoutesConfig
import com.zltm90plus.app.data.remote.ZltRouterApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * Discovery must recognise the device in every way a real firmware answers a bare `GET /`.
 *
 * This suite exists because of an observed field failure: the log showed the device replying to
 * `http://192.168.8.1/` in 624 ms with a TLS error while nine other addresses timed out, yet the
 * app reported "no device responded". The device had answered; the probe had thrown the answer
 * away. Each test below pins one of those answers so the address can no longer be discarded.
 */
class RouterProbeTest {

    /** Anything the test starts and must shut down afterwards. */
    private interface TestServer {
        val address: String
        fun stop()
    }

    private var server: TestServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun serveWith(status: Int, location: String? = null): String =
        RawHttpServer(status, location).start().also { server = it }.address

    @Test
    fun `a redirect onto the device's own https counts as found and reports the scheme`() = runTest {
        val host = serveWith(302, location = "https://127.0.0.1/")

        val attempt = RouterProbe().probe(host)

        assertTrue("a 302 proves the device is present", attempt.reachable)
        assertEquals("https", attempt.scheme)
        assertEquals("HTTP 302", attempt.detail)
    }

    @Test
    fun `a login prompt is found rather than treated as a failure`() = runTest {
        // What an admin interface serves before the user logs in.
        val attempt = RouterProbe().probe(serveWith(401))

        assertTrue(attempt.reachable)
        assertEquals("HTTP 401", attempt.detail)
    }

    @Test
    fun `an unknown path on a real device is still a real device`() = runTest {
        val attempt = RouterProbe().probe(serveWith(404))

        assertTrue("a 404 still means something is listening", attempt.reachable)
        assertEquals("http", attempt.scheme)
    }

    @Test
    fun `a plain 200 is found on http`() = runTest {
        val attempt = RouterProbe().probe(serveWith(200))

        assertTrue(attempt.reachable)
        assertEquals("http", attempt.scheme)
    }

    @Test
    fun `an address nothing is listening on is ruled out`() = runTest {
        // Port 1 on loopback has no listener, so the connection is refused immediately.
        val attempt = RouterProbe(connectTimeoutMillis = 500).probe("127.0.0.1:1")

        assertFalse("a refused connection must not be reported as a device", attempt.reachable)
        assertNull(attempt.scheme)
        assertNotNull("the reason must be recorded for the log", attempt.detail)
    }

    @Test
    fun `discovery keeps the scheme the device demanded and skips dead addresses`() = runTest {
        val live = serveWith(302, location = "https://127.0.0.1/admin")

        // The dead address stands in for the eight timeouts in the field log: it must be passed
        // over rather than aborting the search.
        val candidates = listOf("127.0.0.1:1", live)

        val hit = RouterProbe(connectTimeoutMillis = 500).firstReachable(candidates)

        assertNotNull("the device must be found even when earlier candidates are dead", hit)
        assertEquals(live, hit!!.first)
        assertEquals("https", hit.second.scheme)
    }

    @Test
    fun `a protocol relative redirect keeps the scheme already in use`() = runTest {
        // Firmware often redirects with "//host/path" and no scheme. The probe must continue on
        // the scheme that got the answer rather than losing it.
        val host = serveWith(302, location = "//127.0.0.1/admin")

        val attempt = RouterProbe().probe(host)

        assertTrue(attempt.reachable)
        assertEquals("http", attempt.scheme)
    }

    @Test
    fun `an absolute redirect announces the scheme to switch to`() {
        val probe = RouterProbe()

        assertEquals("https", probe.redirectScheme("https://127.0.0.1/"))
        assertEquals("http", probe.redirectScheme("http://127.0.0.1/"))
        // Null means "no scheme stated", so the caller keeps the one already in use.
        assertNull(probe.redirectScheme("//127.0.0.1/admin"))
        assertNull(probe.redirectScheme(null))
    }

    /**
     * The failure from the field log, reproduced end to end.
     *
     * The log showed `SSLHandshakeException: Trust anchor for certification path not found` for
     * `http://192.168.8.1/`, which is what a device with a self-signed certificate produces. Here
     * a real TLS server serves that certificate over a real socket, so discovery is exercised
     * against the exact handshake that used to look like "no device found".
     */
    @Test
    fun `a device whose self-signed TLS is rejected is still found`() = runTest {
        val host = TlsServer().start().also { server = it }.address

        val attempt = RouterProbe(connectTimeoutMillis = 1_000).probe(host)

        assertTrue(
            "the device answered the handshake, so the address is occupied, got: ${attempt.detail}",
            attempt.reachable,
        )
        assertEquals("https", attempt.scheme)
        assertNotNull("the log must carry the TLS reason", attempt.detail)
    }

    /**
     * The security boundary that makes the exemption above acceptable: a client built for public
     * traffic must keep rejecting the same certificate.
     */
    @Test
    fun `the platform client keeps rejecting the device certificate`() = runTest {
        val host = TlsServer().start().also { server = it }.address

        val rejected = runCatching {
            ZltRouterApi.platformTrustClient()
                .newCall(okhttp3.Request.Builder().url("https://$host/").get().build())
                .execute()
        }.isFailure

        assertTrue("the internet client must not inherit the LAN exemption", rejected)
    }

    @Test
    fun `a silent address is reported unreachable with its reason`() = runTest {
        // A socket that accepts but never writes: the read timeout fires, which is what a
        // firewalled or absent device looks like.
        val silent = RawHttpServer(status = null, location = null).start().also { server = it }

        val attempt = RouterProbe(connectTimeoutMillis = 400).probe(silent.address)

        assertFalse(attempt.reachable)
        assertNotNull("the log must say why nothing was found", attempt.detail)
    }

    /**
     * A minimal HTTP server built on `java.net` only.
     *
     * A null `status` accepts the connection and then stays silent, which reproduces a device that
     * does not answer. Only the JVM socket API is used so this runs under Robolectric too, where
     * the Android framework classes are stubs but sockets are real.
     */
    private class RawHttpServer(private val status: Int?, private val location: String?) : TestServer {

        private val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

        @Volatile
        private var running = true

        override val address: String get() = "127.0.0.1:${serverSocket.localPort}"

        fun start() = apply {
            thread(isDaemon = true, name = "raw-http-server") {
                while (running) {
                    val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { serve(socket) }
                }
            }
        }

        override fun stop() {
            running = false
            runCatching { serverSocket.close() }
        }

        private fun serve(socket: Socket) {
            try {
                socket.use {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.ISO_8859_1))
                    if (reader.readLine() == null) return
                    // Drain the headers so the client finishes sending before the reply.
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val code = status ?: return // Silent: let the client time out.
                    val header = buildString {
                        append("HTTP/1.1 $code ${reason(code)}\r\n")
                        location?.let { target -> append("Location: $target\r\n") }
                        append("Content-Length: 0\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    it.getOutputStream().apply {
                        write(header.toByteArray(Charsets.ISO_8859_1))
                        flush()
                    }
                }
            } catch (_: SocketException) {
                // The client gave up first, which is the point of the silent case.
            }
        }

        private fun reason(code: Int): String = when (code) {
            200 -> "OK"
            302 -> "Found"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Status"
        }
    }

    /**
     * A TLS server presenting the self-signed certificate a router of this family ships.
     *
     * The certificate and key in `src/test/resources/tls` were generated once and are test data,
     * not a secret: the certificate is self-signed and the key protects nothing. A real handshake
     * is the only way to reproduce "Trust anchor for certification path not found", because that
     * error comes from the JSSE provider rather than from application code.
     */
    private class TlsServer : TestServer {

        private val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

        @Volatile
        private var running = true

        override val address: String get() = "127.0.0.1:${serverSocket.localPort}"

        fun start() = apply {
            val context = sslContext()
            thread(isDaemon = true, name = "tls-http-server") {
                while (running) {
                    val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        runCatching {
                            val tls = context.socketFactory.createSocket(
                                socket,
                                socket.inetAddress.hostAddress,
                                socket.port,
                                true,
                            ) as javax.net.ssl.SSLSocket
                            tls.use {
                                it.startHandshake()
                                it.inputStream.readBytes()
                                val body = "HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                it.outputStream.write(body.toByteArray(Charsets.ISO_8859_1))
                            }
                        }
                    }
                }
            }
        }

        override fun stop() {
            running = false
            runCatching { serverSocket.close() }
        }

        /** Loads the PEM fixtures without depending on the Android or JSSE keystore formats. */
        private fun sslContext(): javax.net.ssl.SSLContext {
            val certificate = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(resource("/tls/router-cert.pem")) as java.security.cert.X509Certificate
            val key = readPrivateKey(resourceText("/tls/router-key.pem"))
            val store = java.security.KeyStore.getInstance("PKCS12")
            store.load(null, null)
            store.setKeyEntry("router", key, KEY_PASSWORD, arrayOf(certificate))
            val kmf = javax.net.ssl.KeyManagerFactory.getInstance("SunX509")
            kmf.init(store, KEY_PASSWORD)
            return javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, null, java.security.SecureRandom())
            }
        }

        private fun resource(path: String) =
            requireNotNull(javaClass.getResourceAsStream(path)) { "missing test fixture $path" }

        private fun resourceText(path: String) = resource(path).readBytes().toString(Charsets.UTF_8)

        private fun readPrivateKey(pem: String): java.security.PrivateKey {
            val der = java.util.Base64.getMimeDecoder()
                .decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""))
            return java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
        }

        private companion object {
            val KEY_PASSWORD = "test".toCharArray()
        }
    }
}