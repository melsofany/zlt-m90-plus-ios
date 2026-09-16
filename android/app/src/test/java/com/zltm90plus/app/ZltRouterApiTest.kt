package com.zltm90plus.app

import com.zltm90plus.app.data.model.ChargingState
import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.data.model.NetworkState
import com.zltm90plus.app.data.remote.RouterError
import com.zltm90plus.app.data.remote.RouterRoutesConfig
import com.zltm90plus.app.data.remote.SessionTokenStore
import com.zltm90plus.app.data.remote.ZltRouterApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises [ZltRouterApi] against a real HTTP server.
 *
 * These are the tests that would have caught the reported bug: the client previously built its
 * requests against LuCI routes, judged a goform `{"result":"0"}` login as wrong credentials, and
 * threw its session away between calls. Nothing here is mocked — a socket is opened, headers and
 * cookies travel, and the response is parsed by the production code.
 */
class ZltRouterApiTest {

    private lateinit var server: FakeGoformServer
    private lateinit var session: RecordingSessionStore

    private class RecordingSessionStore : SessionTokenStore {
        private var value: String? = null
        val writes = AtomicInteger()

        override fun saveToken(token: String) {
            value = token
            writes.incrementAndGet()
        }

        override fun token(): String? = value
        override fun clear() {
            value = null
        }
    }

    @Before
    fun setUp() {
        server = FakeGoformServer().start()
        session = RecordingSessionStore()
    }

    @After
    fun tearDown() = server.stop()

    /** A dedicated host/port pair, used by the API tests to reach a local fake firmware. */
    private fun api(host: String = server.host, port: Int? = server.port): ZltRouterApi = ZltRouterApi(
        config = RouterRoutesConfig.parse(routesJson),
        sessionStore = session,
        hostProvider = { if (port != null) "$host:$port" else host },
    )

    /** The shipped config with one goform value overridden, to exercise its alternatives. */
    private fun configWith(loginPasswordEncoding: String): RouterRoutesConfig {
        val json = JSONObject(routesJson)
        json.getJSONObject("goform").put("loginPasswordEncoding", loginPasswordEncoding)
        return RouterRoutesConfig.parse(json.toString())
    }

    private fun String.parseFormValue(name: String): String? =
        split("&")
            .firstOrNull { it.substringBefore('=') == name }
            ?.let { java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun `successful goform login is accepted and stores the session`() = runTest {
        val api = api()
        api.login("admin", "admin")
        assertEquals(1, server.loginAttempts.get())
        assertTrue("session should be retained for later requests", session.token() != null)
    }

    @Test
    fun `the login password is sent Base64 encoded as the firmware requires`() = runTest {
        val api = api()
        api.login("admin", "admin")
        // The firmware rejects a clear-text password with the same result code it uses for a
        // wrong one, so this asserts the wire format rather than trusting the server's verdict.
        val body = server.lastSetBody.single()
        val sent = body.parseFormValue("password")
        assertEquals("YWRtaW4=", sent)
        assertEquals("admin", String(java.util.Base64.getDecoder().decode(sent), Charsets.UTF_8))
    }

    @Test
    fun `a config that asks for a plain password is honoured`() = runTest {
        val plainConfig = configWith(loginPasswordEncoding = "plain")
        val api = ZltRouterApi(plainConfig, session, hostProvider = { "${server.host}:${server.port}" })
        api.login("admin", "admin")
        assertEquals("admin", server.lastSetBody.single().parseFormValue("password"))
    }

    @Test
    fun `wrong password maps to invalid credentials, not to a network error`() = runTest {
        val error = runCatching { api().login("admin", "nope") }.exceptionOrNull()
        assertTrue("expected InvalidCredentials but was $error", error is RouterError.InvalidCredentials)
    }

    /**
     * The field failure this fix exists for.
     *
     * The log showed `ProtocolException: Unexpected status line:
     * /goform/goform_set_cmd_process HTTP/1.1 301 Moved Permanently` reported as
     * "حدث خطأ مؤقت. يمكنك إعادة المحاولة." — advice that cannot work, because the same request
     * provokes the same answer. The device did reply, so this must be named as an unreadable reply
     * rather than as a transient fault.
     */
    @Test
    fun `a status line that echoes the request is reported as an unreadable reply`() = runTest {
        server.echoRequestLineAsStatus = true

        val error = runCatching { api().login("admin", "admin") }.exceptionOrNull()

        assertTrue(
            "a device that answered must not be reported as a temporary failure, got: $error",
            error is RouterError.DeviceResponseUnreadable,
        )
        assertTrue(
            "the reason must be carried for the log, got: ${(error as RouterError?)?.technicalDetail}",
            (error as RouterError?)?.technicalDetail?.contains("Unexpected status line") == true,
        )
    }

    /**
     * The other half of the field failure: discovery saw a redirect on `GET /` and switched the
     * whole app to https, where this firmware answers every API path with 404. A preference for
     * https must not become a dead end when only http serves the interface.
     */
    @Test
    fun `login falls back to http when the preferred https scheme has no interface`() = runTest {
        // The fake answers the API over http; asking it to serve https is what the 404 stands in
        // for, so a client that must start on https is pointed at a scheme that cannot work.
        val httpsOnly = ZltRouterApi(
            config = RouterRoutesConfig.parse(routesJson),
            sessionStore = session,
            hostProvider = { "${server.host}:${server.port}" },
            schemeProvider = { "https" },
        )

        val error = runCatching { httpsOnly.login("admin", "admin") }.exceptionOrNull()

        assertNull(
            "the client must try http after https fails, not report a failure: $error",
            error,
        )
        assertEquals("the login must have reached the device", 1, server.loginAttempts.get())
    }

    @Test
    fun `the scheme that logged in is the one used for later reads`() = runTest {
        val api = api()
        api.login("admin", "admin")
        api.fetchBatteryStatus()

        // The fake only speaks http, so a read that succeeded proves the resolved scheme was
        // carried past login rather than recomputed from the caller's preference.
        assertEquals(0, server.queriesWithoutSession.get())
    }

    @Test
    fun `queries after login carry the session cookie`() = runTest {
        val api = api()
        api.login("admin", "admin")
        api.fetchBatteryStatus()
        // The device rejects reads without its session cookie; the client must never do that.
        assertEquals(0, server.queriesWithoutSession.get())
    }

    @Test
    fun `battery percent and charging state come from the device fields`() = runTest {
        val api = api()
        api.login("admin", "admin")
        val battery = api.fetchBatteryStatus()
        assertEquals(78, battery.percent)
        assertEquals(ChargingState.DISCHARGING, battery.chargingState)
        assertEquals(DataSource.ROUTER, battery.source)
    }

    @Test
    fun `device info maps firmware, imei and the device model`() = runTest {
        val api = api()
        api.login("admin", "admin")
        val info = api.fetchDeviceInfo()
        assertEquals("1.12.8", info.firmwareVersion)
        assertEquals("860540080045325", info.imei)
        // Distinct firmware fields are kept apart rather than collapsed into one value.
        assertEquals("M90P_V1.0", info.hardwareVersion)
        assertEquals("10.44.0.9", info.wanIpAddress)
    }

    @Test
    fun `uptime reported in seconds is converted to minutes`() = runTest {
        val api = api()
        api.login("admin", "admin")
        val info = api.fetchDeviceInfo()
        // The device answers realtime_time=636 for an uptime of 0h 10m 36s. Reading that as
        // minutes would have shown roughly ten hours instead of ten minutes.
        assertEquals(10L, info.uptimeMinutes)
    }

    @Test
    fun `a field that is present but empty does not hide a populated alias`() = runTest {
        val api = api()
        api.login("admin", "admin")
        // The M90 Plus sends wan_connect_status as "" while ppp_status holds the real state.
        // Reading only the first matching key left the connection state unknown.
        val network = api.fetchNetworkStatus()
        assertEquals(NetworkState.ONLINE, network.state)
    }

    @Test
    fun `the firmware placeholder dash is reported as unavailable`() = runTest {
        val api = api()
        api.login("admin", "admin")
        val info = api.fetchDeviceInfo()
        // The reference device answers "-" for fields it cannot read; that is not a serial number.
        assertNull(info.serialNumber)
    }

    @Test
    fun `network status derives state and signal from firmware flags`() = runTest {
        val api = api()
        api.login("admin", "admin")
        val network = api.fetchNetworkStatus()
        assertEquals(NetworkState.ONLINE, network.state)
        assertEquals("Mobily", network.carrierName)
        assertEquals("LTE", network.networkType)
        assertEquals(80, network.signalPercent)
    }

    @Test
    fun `data plan sums the sent and received counters and reads the declared quota unit`() = runTest {
        val api = api()
        api.login("admin", "admin")
        val plan = api.fetchDataUsage()
        // 128 MiB sent + 1 GiB received.
        assertEquals(134_217_728L + 1_073_741_824L, plan.usedBytes)
        // The quota is "50" with the unit declared in a separate field, so it is 50 GB, not 50 B.
        assertEquals(50_000_000_000L, plan.totalBytes)
        assertEquals(DataSource.ROUTER, plan.source)
    }

    @Test
    fun `a quota without a unit is reported unavailable rather than guessed`() = runTest {
        server.stop()
        server = FakeGoformServer(unsupportedFields = setOf("data_volume_limit_unit")).start()
        val api = api()
        api.login("admin", "admin")
        val plan = api.fetchDataUsage()
        assertNull("an ambiguous quota must not be guessed", plan.totalBytes)
        assertEquals(DataSource.UNAVAILABLE, plan.source)
    }

    @Test
    fun `connected devices include hosts with and without a name`() = runTest {
        val api = api()
        api.login("admin", "admin")
        val devices = api.fetchConnectedDevices().devices
        assertEquals(2, devices.size)
        assertEquals("iPhone", devices[0].hostname)
        assertEquals("A4:5E:60:11:22:33", devices[0].macAddress)
        assertNull(devices[1].hostname)
    }

    @Test
    fun `a firmware field the device does not implement reports unavailable, never zero`() = runTest {
        server.stop()
        // Every battery field is absent, which is how a firmware build with no battery support
        // answers. battery_vol_percent is deliberately included: it is a real percent field and
        // leaving it present would make this test pass for the wrong reason.
        server = FakeGoformServer(
            unsupportedFields = setOf(
                "battery_value",
                "battery_charging",
                "battery_vol_percent",
                "battery_pers",
            ),
        ).start()
        val api = api()
        api.login("admin", "admin")
        val battery = api.fetchBatteryStatus()
        assertNull("a missing firmware field must stay null", battery.percent)
        assertEquals(DataSource.UNAVAILABLE, battery.source)
    }

    @Test
    fun `an expired session is reported instead of silently returning stale data`() = runTest {
        val api = api()
        api.login("admin", "admin")
        server.sessionAlwaysInvalid = true
        val error = runCatching { api.fetchNetworkStatus() }.exceptionOrNull()
        assertTrue("expected SessionExpired but was $error", error is RouterError.SessionExpired)
    }

    @Test
    fun `a public address is refused before any request is made`() = runTest {
        val error = runCatching { api(host = "8.8.8.8", port = null).login("admin", "admin") }.exceptionOrNull()
        assertTrue("expected InvalidHost but was $error", error is RouterError.InvalidHost)
    }

    @Test
    fun `an unreachable device maps to device not found`() = runTest {
        server.stop()
        // Port 1 on loopback is local and valid, but nothing is listening behind it.
        val error = runCatching { api(host = "127.0.0.1", port = 1).login("admin", "admin") }.exceptionOrNull()
        assertTrue("expected DeviceNotFound but was $error", error is RouterError.DeviceNotFound)
    }

    @Test
    fun `credentials never appear in the exception message`() = runTest {
        val caught = runCatching { api().login("admin", "super-secret-password") }.exceptionOrNull()
        val error = caught as? RouterError
        val text = listOfNotNull(error?.userMessage, error?.technicalDetail).joinToString(" ")
        assertTrue("credential leaked into error text: $text", !text.contains("super-secret-password"))
    }

    private companion object {
        /**
         * The route table shipped in assets, so this test fails if the app's own configuration
         * stops matching the interface the client implements.
         */
        val routesJson: String by lazy {
            java.io.File("src/main/assets/router_routes.json").readText()
        }
    }
}

/** Guards the shape of the shipped configuration file itself. */
class RouterRoutesAssetTest {

    private val config = RouterRoutesConfig.parse(
        java.io.File("src/main/assets/router_routes.json").readText(),
    )

    @Test
    fun `goform is tried before the legacy luci fallback`() {
        assertEquals(listOf("goform", "luci"), config.protocolOrder)
    }

    @Test
    fun `the goform command table covers every dataset the client reads`() {
        assertEquals(
            setOf("deviceInfo", "battery", "network", "dataUsage", "connectedDevices"),
            config.goform.commands.keys,
        )
    }

    @Test
    fun `field aliases put the ZLT firmware names first`() {
        // battery_value is the name this firmware family actually returns; ordering matters
        // because the first alias that is present in a response wins.
        assertEquals("battery_value", config.aliases("batteryPercent").first())
        assertEquals("network_provider", config.aliases("carrierName").first())
        assertEquals("cr_version", config.aliases("firmwareVersion").first())
    }

    @Test
    fun `the shipped goform login asks for a Base64 password`() {
        // The firmware answers a clear-text password with the wrong-password result code, so this
        // is the difference between "connected" and "بيانات الدخول غير صحيحة".
        assertEquals("base64", config.goform.loginPasswordEncoding)
    }

    @Test
    fun `credentials and tokens are not stored in the routes file`() {
        val raw = java.io.File("src/main/assets/router_routes.json").readText()
        val json = JSONObject(raw)
        assertTrue("a login password must never be committed", !json.has("password"))
        assertTrue(!raw.contains("\"sessionToken\""))
    }
}
