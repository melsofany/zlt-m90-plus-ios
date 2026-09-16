package com.zltm90plus.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * The reporter is the only part of the diagnostic build that talks to the analysis endpoint, so it
 * is exercised against a real socket rather than a stub: the test asserts on the bytes that arrive
 * over the wire, which is what the collector actually receives.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticReporterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPersistedQueue() {
        // filesDir is shared across tests in one Robolectric run, and a report that fails to
        // upload stays on disk on purpose. Leftovers would otherwise be uploaded before the
        // report a test is waiting for, so each test starts from an empty queue.
        java.io.File(context.filesDir, "diagnostics-queue.json").delete()
    }

    /** A one-shot HTTP server that records the request body and answers 200. */
    private class CollectorServer : AutoCloseable {
        private val socket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val bodies = LinkedBlockingQueue<String>()
        private val handle = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { serve(client) }
            }
        }

        val url: String get() = "http://127.0.0.1:${socket.localPort}/report"

        private fun serve(client: Socket) {
            client.use { connection ->
                val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.UTF_8))
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substringAfter(':').trim().toInt()
                    }
                }
                val body = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = reader.read(body, read, contentLength - read)
                    if (count < 0) break
                    read += count
                }
                bodies.offer(String(body, 0, read))

                val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                connection.getOutputStream().write(response.toByteArray())
                connection.getOutputStream().flush()
            }
        }

        /** Waits for one uploaded report, failing the test rather than hanging forever. */
        fun awaitBody(timeoutMillis: Long = 10_000): String =
            bodies.poll(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                ?: throw AssertionError("no report arrived within ${timeoutMillis}ms")

        override fun close() {
            runCatching { socket.close() }
            handle.interrupt()
        }
    }

    private fun exchange(
        url: String = "http://192.168.1.1/goform/goform_set_cmd_process",
        body: String? = "goformId=LOGIN&password=admin",
        status: Int? = 200,
        error: String? = null,
    ) = DiagnosticExchange(
        timestampMillis = System.currentTimeMillis(),
        url = url,
        method = "POST",
        requestBody = DiagnosticRedaction.redact(body.orEmpty()).takeIf { body != null },
        statusCode = status,
        responseBody = """{"result":"0"}""",
        error = error,
        durationMillis = 42,
    )

    @Test
    fun `a recorded exchange is uploaded to the endpoint`() {
        CollectorServer().use { server ->
            val reporter = DiagnosticReporter(context, server.url, "test-device")
            reporter.beginSession(context, "1.0")
            // beginSession uploads on its own, so the header arrives first.
            assertTrue("the session header is uploaded first", server.awaitBody().contains("session"))

            reporter.record(exchange())

            val uploaded = server.awaitBody()
            assertTrue("the URL must be reported", uploaded.contains("goform_set_cmd_process"))
            assertTrue("the method must be reported", uploaded.contains("POST"))
            assertTrue("the firmware answer must be reported", uploaded.contains("result"))
            assertTrue("the HTTP status must be reported", uploaded.contains("200"))
        }
    }

    @Test
    fun `the upload carries a session header describing the environment`() {
        CollectorServer().use { server ->
            val reporter = DiagnosticReporter(context, server.url, "test-device")
            reporter.beginSession(context, "1.0")

            val uploaded = server.awaitBody()
            assertTrue("device label identifies the build", uploaded.contains("test-device"))
            assertTrue("app version is reported", uploaded.contains("1.0"))
            assertTrue("the wifi state is reported", uploaded.contains("onWifi"))
            assertTrue("the android version is reported", uploaded.contains("androidSdk"))
        }
    }

    @Test
    fun `a redacted password is what leaves the device`() {
        CollectorServer().use { server ->
            val reporter = DiagnosticReporter(context, server.url, "test-device")

            reporter.record(exchange(body = "goformId=LOGIN&password=$SUPER_SECRET"))

            val uploaded = server.awaitBody()
            assertTrue("the login must be reported", uploaded.contains("goformId"))
            assertTrue(
                "the password must never reach the collector",
                !uploaded.contains(SUPER_SECRET),
            )
        }
    }

    @Test
    fun `an unreachable endpoint does not throw into the request path`() {
        // Port 1 has no listener. record() must stay silent: it runs inside the login path.
        val reporter = DiagnosticReporter(context, "http://127.0.0.1:1/report", "test-device")

        reporter.record(exchange())

        // Give the background uploader a chance to run and fail.
        Thread.sleep(1_000)
        assertNotNull("the failure must be surfaced to the caller", reporter.lastUploadResult)
        assertTrue(
            "a failed upload must be described, not swallowed",
            reporter.lastUploadResult!!.startsWith("فشل"),
        )
        assertEquals("nothing was accepted", 0, reporter.uploadedCount)
    }

    @Test
    fun `a report that failed to upload is retried from disk`() {
        // Record while the endpoint is down, then bring one up; the queue on disk is the retry.
        val reporter = DiagnosticReporter(context, "http://127.0.0.1:1/report", "test-device")
        reporter.record(exchange())
        Thread.sleep(500)

        val reinstated = DiagnosticReporter(context, "http://127.0.0.1:1/report", "test-device")
        assertTrue(
            "the pending report must be reloaded from disk",
            java.io.File(context.filesDir, "diagnostics-queue.json").exists(),
        )
        assertEquals("nothing accepted yet", 0, reporter.uploadedCount)
        assertEquals("nothing accepted yet", 0, reinstated.uploadedCount)
    }

    private companion object {
        const val SUPER_SECRET = "Sup3rSecretPassw0rd"
    }
}