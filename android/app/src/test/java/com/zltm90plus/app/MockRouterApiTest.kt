package com.zltm90plus.app

import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.data.model.NetworkState
import com.zltm90plus.app.data.remote.MockRouterApi
import com.zltm90plus.app.data.remote.RouterError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockRouterApiTest {

    @Test
    fun `demo values are always labelled estimated`() = runTest {
        val api = MockRouterApi(latencyMillis = 0)
        assertEquals(DataSource.ESTIMATED, api.fetchDeviceInfo().source)
        assertEquals(DataSource.ESTIMATED, api.fetchBatteryStatus().source)
        assertEquals(DataSource.ESTIMATED, api.fetchConnectedDevices().source)
        assertEquals(NetworkState.ONLINE, api.fetchNetworkStatus().state)
    }

    @Test
    fun `no battery support scenario reports unavailable without a number`() = runTest {
        val api = MockRouterApi(MockRouterApi.Scenario.NO_BATTERY_SUPPORT, latencyMillis = 0)
        val battery = api.fetchBatteryStatus()
        assertNull(battery.percent)
        assertEquals(DataSource.UNAVAILABLE, battery.source)
    }

    @Test
    fun `no cellular scenario clears carrier and signal`() = runTest {
        val api = MockRouterApi(MockRouterApi.Scenario.NO_CELLULAR, latencyMillis = 0)
        val network = api.fetchNetworkStatus()
        assertEquals(NetworkState.NO_CELLULAR, network.state)
        assertNull(network.carrierName)
        assertNull(network.signalPercent)
    }

    @Test
    fun `no internet scenario keeps the router reachable`() = runTest {
        val api = MockRouterApi(MockRouterApi.Scenario.NO_INTERNET, latencyMillis = 0)
        assertEquals(NetworkState.ROUTER_ONLY_NO_INTERNET, api.fetchNetworkStatus().state)
        assertTrue(!api.probeInternet())
    }

    @Test(expected = RouterError.InvalidCredentials::class)
    fun `bad credentials scenario throws invalid credentials`() = runTest {
        MockRouterApi(MockRouterApi.Scenario.BAD_CREDENTIALS, latencyMillis = 0).login("admin", "wrong")
    }

    @Test(expected = RouterError.DeviceNotFound::class)
    fun `unreachable scenario maps to device not found`() = runTest {
        MockRouterApi(MockRouterApi.Scenario.UNREACHABLE, latencyMillis = 0).fetchBatteryStatus()
    }

    @Test
    fun `demo data usage is unavailable, never fabricated zeros`() = runTest {
        val plan = MockRouterApi(latencyMillis = 0).fetchDataUsage()
        assertEquals(DataSource.UNAVAILABLE, plan.source)
        assertNull(plan.totalBytes)
    }
}