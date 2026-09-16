package com.zltm90plus.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.data.model.NetworkState
import com.zltm90plus.app.data.remote.RouterRoutesConfig
import com.zltm90plus.app.data.remote.SessionTokenStore
import com.zltm90plus.app.data.remote.ZltRouterApi
import com.zltm90plus.app.data.repository.BatteryHistoryStore
import com.zltm90plus.app.data.repository.ManualPlanStore
import com.zltm90plus.app.data.repository.RouterRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the whole read path the app uses after a successful login: repository → API → socket.
 *
 * The API tests cover each endpoint on its own. This one asserts the pieces still add up once
 * they are assembled the way the app assembles them — the login handshake, the session cookie,
 * and a full [com.zltm90plus.app.data.model.DeviceSnapshot] read from one server.
 *
 * Robolectric only supplies a `Context` for the two real preference-backed stores; the router is
 * a live socket server, so nothing about the network path is simulated.
 */
@RunWith(RobolectricTestRunner::class)
class RouterRepositoryEndToEndTest {

    private lateinit var server: FakeGoformServer
    private lateinit var session: SessionTokenStore

    private class InMemorySessionStore : SessionTokenStore {
        private var value: String? = null
        override fun saveToken(token: String) {
            value = token
        }

        override fun token(): String? = value
        override fun clear() {
            value = null
        }
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun repositoryFor(target: FakeGoformServer): RouterRepository {
        val config = RouterRoutesConfig.parse(
            java.io.File("src/main/assets/router_routes.json").readText(),
        )
        val api = ZltRouterApi(
            config = config,
            sessionStore = session,
            hostProvider = { "${target.host}:${target.port}" },
        )
        return RouterRepository(
            apiProvider = { api },
            historyStore = BatteryHistoryStore(context),
            manualPlanStore = ManualPlanStore(context),
        )
    }

    @Before
    fun setUp() {
        server = FakeGoformServer().start()
        session = InMemorySessionStore()
    }

    @After
    fun tearDown() = server.stop()

    @Test
    fun `login then loadSnapshot returns every dataset from the device`() = runTest {
        val repository = repositoryFor(server)
        repository.login("admin", "admin")
        val snapshot = repository.loadSnapshot()

        assertEquals("1.12.8", snapshot.deviceInfo.firmwareVersion)
        assertEquals("860540080045325", snapshot.deviceInfo.imei)
        assertEquals(10L, snapshot.deviceInfo.uptimeMinutes)
        assertEquals(DataSource.ROUTER, snapshot.deviceInfo.source)

        assertEquals(78, snapshot.battery.percent)
        assertEquals(DataSource.ROUTER, snapshot.battery.source)

        assertEquals(NetworkState.ONLINE, snapshot.network.state)
        assertEquals("Mobily", snapshot.network.carrierName)
        assertEquals("LTE", snapshot.network.networkType)

        assertEquals(2, snapshot.connectedDevices.devices.size)
        assertTrue(
            "a host that reports no name must not have one invented for it",
            snapshot.connectedDevices.devices.any { it.hostname == null },
        )

        // The declared quota is a plan fact, so it is reported as coming from the device.
        assertEquals(50_000_000_000L, snapshot.plan.totalBytes)
        assertEquals(1_207_959_552L, snapshot.plan.usedBytes)
        assertEquals(DataSource.ROUTER, snapshot.plan.source)
    }

    @Test
    fun `a firmware that exposes no battery reports it unavailable rather than zero`() = runTest {
        server.stop()
        server = FakeGoformServer(
            unsupportedFields = setOf("battery_value", "battery_charging", "battery_vol_percent"),
        ).start()

        val repository = repositoryFor(server)
        repository.login("admin", "admin")
        val snapshot = repository.loadSnapshot()

        assertNull("an absent reading must never be rendered as 0%", snapshot.battery.percent)
        assertEquals(DataSource.UNAVAILABLE, snapshot.battery.source)
    }

    @Test
    fun `the session survives repeated reads`() = runTest {
        val repository = repositoryFor(server)
        repository.login("admin", "admin")
        assertTrue(session.token() != null)

        // Reads a second time on the same logged-in client. Dropping the session between calls
        // was the original "connects once, then every screen goes blank" failure.
        repository.loadSnapshot()
        val second = repository.loadSnapshot()
        assertEquals(DataSource.ROUTER, second.deviceInfo.source)
        assertEquals(0, server.queriesWithoutSession.get())
    }
}