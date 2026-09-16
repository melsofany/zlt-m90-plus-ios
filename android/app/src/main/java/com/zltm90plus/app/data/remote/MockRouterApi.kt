package com.zltm90plus.app.data.remote

import com.zltm90plus.app.data.model.BatteryStatus
import com.zltm90plus.app.data.model.ChargingState
import com.zltm90plus.app.data.model.ConnectedDevice
import com.zltm90plus.app.data.model.ConnectedDevices
import com.zltm90plus.app.data.model.DataPlanStatus
import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.data.model.NetworkState
import com.zltm90plus.app.data.model.NetworkStatus
import com.zltm90plus.app.data.model.RouterDeviceInfo
import com.zltm90plus.app.data.model.SignalLevel
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Demo backend for development and UI review when no physical device is reachable.
 *
 * It is only ever wired in when the user explicitly enables demo mode, and every value it
 * returns is tagged [DataSource.ESTIMATED] so the UI can label the whole screen as sample
 * data. It also reproduces the awkward real-world cases (firmware without battery readout,
 * no cellular coverage, router-only connectivity) so those UI branches can be exercised.
 */
class MockRouterApi(
    private val scenario: Scenario = Scenario.NORMAL,
    private val latencyMillis: Long = 350,
) : RouterApiProtocol {

    enum class Scenario {
        /** Typical healthy device. */
        NORMAL,
        /** Firmware does not expose battery data. */
        NO_BATTERY_SUPPORT,
        /** Router reachable, cellular modem not registered. */
        NO_CELLULAR,
        /** Router reachable, no internet uplink. */
        NO_INTERNET,
        /** Login always fails, to preview the credentials error path. */
        BAD_CREDENTIALS,
        /** Every call fails with a transient error. */
        UNREACHABLE,
    }

    private val batteryTicks = AtomicInteger(0)

    override suspend fun login(username: String, password: String) {
        delay(latencyMillis)
        if (scenario == Scenario.BAD_CREDENTIALS) throw RouterError.InvalidCredentials()
        if (scenario == Scenario.UNREACHABLE) throw RouterError.DeviceNotFound("mock")
    }

    override suspend fun fetchDeviceInfo(): RouterDeviceInfo {
        failIfUnreachable()
        return RouterDeviceInfo(
            model = "ZLT M90 Plus",
            firmwareVersion = "MOCK-1.0.0",
            hardwareVersion = "MOCK-HW",
            wifiSsid = "ZLT_M90_PLUS_MOCK",
            uptimeMinutes = 1_440,
            source = DataSource.ESTIMATED,
        )
    }

    override suspend fun fetchBatteryStatus(): BatteryStatus {
        failIfUnreachable()
        if (scenario == Scenario.NO_BATTERY_SUPPORT) {
            return BatteryStatus(percent = null, chargingState = ChargingState.UNKNOWN, source = DataSource.UNAVAILABLE)
        }
        val tick = batteryTicks.getAndIncrement()
        val percent = (86 - tick / 2).coerceAtLeast(5)
        return BatteryStatus(
            percent = percent,
            chargingState = if (tick > 6) ChargingState.CHARGING else ChargingState.DISCHARGING,
            deviceReportedRemainingMinutes = null,
            voltageMillivolts = 3_850,
            temperatureCelsius = 31.5,
            source = DataSource.ESTIMATED,
        )
    }

    override suspend fun fetchNetworkStatus(): NetworkStatus {
        failIfUnreachable()
        val state = when (scenario) {
            Scenario.NO_CELLULAR -> NetworkState.NO_CELLULAR
            Scenario.NO_INTERNET -> NetworkState.ROUTER_ONLY_NO_INTERNET
            else -> NetworkState.ONLINE
        }
        return NetworkStatus(
            state = state,
            carrierName = if (scenario == Scenario.NO_CELLULAR) null else "مشغل تجريبي",
            networkType = if (scenario == Scenario.NO_CELLULAR) null else "LTE",
            signalLevel = if (scenario == Scenario.NO_CELLULAR) SignalLevel.NONE else SignalLevel.GOOD,
            signalPercent = if (scenario == Scenario.NO_CELLULAR) null else 72,
            localIpAddress = "192.168.0.100",
            connectionUptimeMinutes = 1_440,
            lastSuccessfulProbeMillis = System.currentTimeMillis(),
            source = DataSource.ESTIMATED,
        )
    }

    /** Mirrors real firmware: this build does not expose plan counters. */
    override suspend fun fetchDataUsage(): DataPlanStatus {
        failIfUnreachable()
        return DataPlanStatus(source = DataSource.UNAVAILABLE)
    }

    override suspend fun fetchConnectedDevices(): ConnectedDevices {
        failIfUnreachable()
        return ConnectedDevices(
            devices = listOf(
                ConnectedDevice("iPhone-أحمد", "A4:5E:60:11:22:33", "192.168.0.101", "5GHz"),
                ConnectedDevice("Laptop", "B8:27:EB:44:55:66", "192.168.0.102", "2.4GHz"),
                ConnectedDevice("TV", "D0:03:DF:77:88:99", "192.168.0.103", "5GHz"),
                ConnectedDevice(null, "F0:18:98:AA:BB:CC", "192.168.0.104", "2.4GHz"),
                ConnectedDevice("Tablet", "3C:5A:B4:DD:EE:FF", "192.168.0.105", "5GHz"),
            ),
            source = DataSource.ESTIMATED,
        )
    }

    override suspend fun updateWiFi(ssid: String, password: String) {
        failIfUnreachable()
        throw RouterError.FeatureNotSupported("تعديل Wi-Fi", "وضع العرض التجريبي لا ينفذ عمليات على الجهاز")
    }

    override suspend fun restartRouter() {
        failIfUnreachable()
        throw RouterError.FeatureNotSupported("إعادة التشغيل", "وضع العرض التجريبي لا ينفذ عمليات على الجهاز")
    }

    override suspend fun logout() = Unit

    override suspend fun probeInternet(): Boolean {
        delay(latencyMillis)
        return scenario != Scenario.NO_INTERNET && scenario != Scenario.NO_CELLULAR && scenario != Scenario.UNREACHABLE
    }

    override suspend fun probeWebInterface(): Boolean {
        delay(latencyMillis)
        return scenario != Scenario.UNREACHABLE
    }

    private suspend fun failIfUnreachable() {
        delay(latencyMillis)
        if (scenario == Scenario.UNREACHABLE) throw RouterError.DeviceNotFound("mock")
    }
}