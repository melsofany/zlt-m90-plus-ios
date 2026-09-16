package com.zltm90plus.app

import com.zltm90plus.app.data.remote.RouterRoutesConfig
import com.zltm90plus.app.data.remote.SessionTokenStore
import com.zltm90plus.app.data.remote.ZltRouterApi
import com.zltm90plus.app.diagnostics.DiagnosticExchange
import com.zltm90plus.app.diagnostics.Diagnostics
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The diagnostic build is only useful if the transport reports what it actually sent and received.
 * These tests install a recording sink exactly the way the diagnostic application does, then drive
 * a real login against [FakeGoformServer], so the capture is exercised over a real socket rather
 * than asserted against a mock.
 */
class DiagnosticCaptureTest {

    private lateinit var server: FakeGoformServer
    private lateinit var recorded: MutableList<DiagnosticExchange>
    private lateinit var config: RouterRoutesConfig

    private val password = "admin"

    @Before
    fun setUp() {
        server = FakeGoformServer(password = password).start()
        recorded = java.util.Collections.synchronizedList(mutableListOf())
        config = RouterRoutesConfig.parse(
            java.io.File("src/main/assets/router_routes.json").readText(),
        )
        Diagnostics.install { recorded += it }
    }

    @After
    fun tearDown() {
        Diagnostics.install(null)
        server.stop()
    }

    private fun api(host: String = "${server.host}:${server.port}") = ZltRouterApi(
        config = config,
        sessionStore = RecordingSessionStore(),
        hostProvider = { host },
    )

    private fun loginExchange() =
        recorded.firstOrNull { it.method == "POST" && it.requestBody?.contains("goformId=LOGIN") == true }

    @Test
    fun `a login is captured with its status and the firmware answer`() = runTest {
        api().login("admin", password)

        val login = loginExchange()
        assertNotNull("the login POST must be captured", login)
        assertTrue("the status must be recorded", (login!!.statusCode ?: 0) in 200..299)
        assertTrue(
            "the firmware answer must be recorded",
            login.responseBody?.contains("result") == true,
        )
    }

    @Test
    fun `the captured login body never carries the cleartext password`() = runTest {
        api().login("admin", password)

        val body = loginExchange()!!.requestBody.orEmpty()
        assertFalse("the password must be redacted", body.contains("password=$password"))
        assertTrue("the rest of the body is still captured", body.contains("goformId=LOGIN"))
    }

    @Test
    fun `a refused connection is captured as an error rather than a status`() = runTest {
        // Port 1 has no listener, so this is a genuine refusal and not a simulated one.
        runCatching { api(host = "127.0.0.1:1").login("admin", password) }

        val failure = recorded.firstOrNull { it.error != null }
        assertNotNull("a refused connection must be captured", failure)
        assertNull("a failed exchange has no status", failure!!.statusCode)
        assertTrue(
            "the error must carry the OS-level cause, not only the user-facing text; actual=[${failure.error}]",
            failure.error!!.contains("Failed to connect"),
        )
        assertFalse("no credentials in the error line", failure.error!!.contains(password))
    }

    @Test
    fun `reads after login are captured so a session problem is visible`() = runTest {
        val client = api()
        client.login("admin", password)
        runCatching { client.fetchBatteryStatus() }

        val reads = recorded.filter { it.method == "GET" }
        assertTrue("at least one read must be captured", reads.isNotEmpty())
        assertTrue("the read must record the cmd asked for", reads.any { it.url.contains("cmd=") })
        assertTrue("a GET carries no body", reads.all { it.requestBody == null })
    }

    @Test
    fun `without a sink nothing is recorded`() = runTest {
        Diagnostics.install(null)

        api().login("admin", password)

        assertTrue("a normal build must record nothing", recorded.isEmpty())
    }

    @Test
    fun `capture does not consume the response the caller parses`() = runTest {
        // peekBody is used for capture; if it consumed the stream, login would fail to parse.
        api().login("admin", password)

        assertNull("login must still succeed with capture on", recorded.firstOrNull { it.error != null })
        assertTrue("the login must be observed", recorded.any { it.statusCode != null })
    }

    private class RecordingSessionStore : SessionTokenStore {
        private var value: String? = null
        override fun saveToken(token: String) {
            value = token
        }

        override fun token(): String? = value
        override fun clear() {
            value = null
        }
    }
}