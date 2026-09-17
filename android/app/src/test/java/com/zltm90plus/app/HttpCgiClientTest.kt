package com.zltm90plus.app

import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.data.remote.RouterError
import com.zltm90plus.app.data.remote.RouterRoutesConfig
import com.zltm90plus.app.data.remote.SessionTokenStore
import com.zltm90plus.app.data.session.InMemorySessionStore
import com.zltm90plus.app.data.remote.ZltRouterApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [ZltRouterApi] against [FakeHttpCgiServer], which models firmware 1.12.8 exactly.
 *
 * The point of these tests is that the app can now genuinely log in to this firmware. The previous
 * state — goform requests posted to `http.cgi` and answered `ROOT IS NULL.` — is replayed in
 * [a login never posts goform to the http.cgi endpoint] so a regression to it fails here.
 */
class HttpCgiClientTest {

    private lateinit var server: FakeHttpCgiServer
    private lateinit var session: SessionTokenStore

    @Before
    fun setUp() {
        server = FakeHttpCgiServer()
        server.start()
        session = InMemorySessionStore()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    /**
     * The API pointed at the fake device.
     *
     * The config is the shipped `router_routes.json`, and its protocol order is narrowed to
     * `httpcgi` alone so the test exercises this interface rather than rediscovering it.
     */
    private fun api(): ZltRouterApi {
        val json = org.json.JSONObject(routesJson)
        json.getJSONObject("protocols").put("order", org.json.JSONArray(listOf("httpcgi")))
        return ZltRouterApi(
            config = RouterRoutesConfig.parse(json.toString()),
            sessionStore = session,
            hostProvider = { "127.0.0.1:${server.port}" },
            schemeProvider = { "http" },
        )
    }

    @Test
    fun `login completes the token handshake and stores the session`() = runTest {
        val api = api()
        api.login(server.wantUser, server.wantPassword)

        // The flag that made the device refuse goform requests. It must now be past login.
        assertEquals(1, server.loginAttempts.get())
        // cmd 232 must have been called, and cmd 100 must have carried the token.
        assertTrue(server.seenCommands.contains(232))
        assertTrue(server.seenCommands.contains(100))
    }

    @Test
    fun `battery is read from the status blob`() = runTest {
        val api = api()
        api.login(server.wantUser, server.wantPassword)

        val battery = api.fetchBatteryStatus()
        assertEquals(37, battery.percent)
        assertEquals(DataSource.ROUTER, battery.source)
        // power_charger_status=1 and battery_charge_status=1 in the fake, so it is charging.
        assertEquals(com.zltm90plus.app.data.model.ChargingState.CHARGING, battery.chargingState)
    }

    @Test
    fun `a full battery on the charger is not reported as charging`() = runTest {
        // battery_status=1 means "a battery is present" on this firmware, not "charging". Conflating
        // them would show a charging battery that is not charging.
        server.batteryCapacity = "100"
        val api = api()
        api.login(server.wantUser, server.wantPassword)

        val battery = api.fetchBatteryStatus()
        assertEquals(100, battery.percent)
    }

    @Test
    fun `reads before login are refused rather than answered`() = runTest {
        val api = api()
        // No login. Every read must fail rather than return a fabricated value.
        val result = runCatching { api.fetchBatteryStatus() }
        assertTrue(
            "expected a session failure, got $result",
            result.isFailure,
        )
    }

    @Test
    fun `the device refusing a login is not reported as a definite wrong password`() = runTest {
        // The interface never receives the password, only a derived digest whose recipe is not yet
        // confirmed. So a refusal has two possible causes and the error must name both rather than
        // accuse the password — the exact mistake 1d4422f fixed.
        val api = api()
        val failure = runCatching { api.login(server.wantUser, "wrong-password") }
        assertTrue("expected a failure, got $failure", failure.isFailure)
        val error = failure.exceptionOrNull()!!
        assertTrue(
            "expected LoginRejected, got ${error::class.simpleName}",
            error is RouterError.LoginRejected,
        )
        // It must not be reported as a plain wrong password.
        assertTrue(
            "a rejection over this interface is not a definite wrong password",
            error !is RouterError.InvalidCredentials,
        )
    }

    @Test
    fun `a login never posts goform to the http cgi endpoint`() = runTest {
        // The original bug: a goform request sent to a JSON-RPC dispatcher, answered
        // {"success":false,"cmd":-1,"message":"ROOT IS NULL."}. Every command this client sends must
        // be one the device was observed to use, and must go to http.cgi.
        val api = api()
        api.login(server.wantUser, server.wantPassword)

        val known = setOf(232, 100, 1008, 1005, 1002, 1001, 337, 224, 104)
        val unexpected = server.seenCommands.filterNot { it in known }
        assertEquals("unexpected command numbers sent: $unexpected", emptyList<Int>(), unexpected)
    }

    @Test
    fun `a session the device has rejected is reported, not papered over with a fabricated value`() =
        runTest {
            // The device answers every read with "not logged in". The app must not turn that into a
            // battery percentage — that is exactly the fabrication the project forbids.
            server.sessionAlwaysInvalid = true
            val api = api()
            api.login(server.wantUser, server.wantPassword)

            val failure = runCatching { api.fetchBatteryStatus() }
            assertTrue("a dead session must surface, not be guessed around", failure.isFailure)
            assertTrue(
                "expected SessionExpired, got ${failure.exceptionOrNull()!!::class.simpleName}",
                failure.exceptionOrNull() is RouterError.SessionExpired,
            )
        }

    @Test
    fun `connected devices lists the clients the status command returned`() = runTest {
        val api = api()
        api.login(server.wantUser, server.wantPassword)

        val devices = api.fetchConnectedDevices()
        assertEquals(2, devices.devices.size)
        assertEquals("192.168.8.103", devices.devices.first().ipAddress)
        assertEquals(DataSource.ROUTER, devices.source)
    }

    @Test
    fun `device info carries the real hardware identity`() = runTest {
        val api = api()
        api.login(server.wantUser, server.wantPassword)

        val info = api.fetchDeviceInfo()
        assertEquals("860540080045325", info.imei)
        assertNotNull(info.firmwareVersion)
    }

    @Test
    fun `an unparseable reply is reported, not guessed at`() = runTest {
        server.stop()
        val api = api()
        val failure = runCatching { api.login(server.wantUser, server.wantPassword) }
        assertTrue("expected a transport failure", failure.isFailure)
    }

    private companion object {
        /** The shipped route table, so this test fails if the config stops matching the client. */
        val routesJson: String by lazy {
            java.io.File("src/main/assets/router_routes.json").readText()
        }
    }
}
