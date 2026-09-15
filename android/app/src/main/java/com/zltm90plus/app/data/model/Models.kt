package com.zltm90plus.app.data.model

/**
 * Where a value came from. Every field surfaced in the UI carries one of these so the
 * app never presents an estimate as if the device or carrier reported it.
 */
enum class DataSource {
    /** Read from the local router firmware interface. */
    ROUTER,
    /** Returned by a carrier / operator API integration. */
    CARRIER,
    /** Typed in by the user (for example a manual data plan). */
    MANUAL,
    /** Computed by this app from other readings. Always shown as an approximation. */
    ESTIMATED,
    /** The device firmware does not expose this value. */
    UNAVAILABLE,
}

enum class ChargingState {
    CHARGING,
    DISCHARGING,
    FULL,
    UNKNOWN,
}

enum class NetworkState {
    /** Router has an internet route and the probe succeeded. */
    ONLINE,
    /** Router reachable, no internet route. */
    ROUTER_ONLY_NO_INTERNET,
    /** No cellular network registered. */
    NO_CELLULAR,
    /** A check is currently running. */
    CONNECTING,
    UNKNOWN,
}

enum class SignalLevel {
    NONE,
    WEAK,
    FAIR,
    GOOD,
    EXCELLENT,
    UNKNOWN,
}

enum class ReadingQuality {
    GOOD,
    LOW,
    INSUFFICIENT,
}

data class BatteryStatus(
    val percent: Int? = null,
    val chargingState: ChargingState = ChargingState.UNKNOWN,
    /** Remaining runtime in minutes as reported by the device firmware, when supported. */
    val deviceReportedRemainingMinutes: Int? = null,
    val voltageMillivolts: Int? = null,
    val temperatureCelsius: Double? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val source: DataSource = DataSource.ROUTER,
) {
    val isAvailable: Boolean get() = percent != null
}

data class NetworkStatus(
    val state: NetworkState = NetworkState.UNKNOWN,
    val carrierName: String? = null,
    /** e.g. LTE, 4G, 5G, 3G. */
    val networkType: String? = null,
    val signalLevel: SignalLevel = SignalLevel.UNKNOWN,
    /** 0-100 as reported by firmware, when available. */
    val signalPercent: Int? = null,
    val signalDbm: Int? = null,
    val localIpAddress: String? = null,
    val connectionUptimeMinutes: Long? = null,
    val lastSuccessfulProbeMillis: Long? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val source: DataSource = DataSource.ROUTER,
)

data class DataPlanStatus(
    val totalBytes: Long? = null,
    val usedBytes: Long? = null,
    val planStartMillis: Long? = null,
    val renewalMillis: Long? = null,
    val operatorName: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val source: DataSource = DataSource.MANUAL,
) {
    val remainingBytes: Long?
        get() = when {
            totalBytes == null || usedBytes == null -> null
            else -> (totalBytes - usedBytes).coerceAtLeast(0L)
        }

    /** 0.0 - 1.0, or null when either side of the ratio is unknown. */
    val usedFraction: Double?
        get() {
            val total = totalBytes ?: return null
            val used = usedBytes ?: return null
            if (total <= 0L) return null
            return (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        }

    val isAvailable: Boolean get() = totalBytes != null && usedBytes != null
}

data class ConnectedDevice(
    val hostname: String? = null,
    val macAddress: String? = null,
    val ipAddress: String? = null,
    val connectionType: String? = null,
    val isBlocked: Boolean = false,
) {
    /** Stable display key; MAC first because it survives DHCP changes. */
    val stableId: String
        get() = macAddress ?: ipAddress ?: hostname ?: "unknown"
}

data class ConnectedDevices(
    val devices: List<ConnectedDevice> = emptyList(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val source: DataSource = DataSource.ROUTER,
) {
    /** Null when the firmware does not expose a client list at all. */
    val count: Int? get() = if (source == DataSource.UNAVAILABLE) null else devices.size
}

data class RouterDeviceInfo(
    val model: String? = null,
    val firmwareVersion: String? = null,
    val hardwareVersion: String? = null,
    val imei: String? = null,
    val serialNumber: String? = null,
    val wifiSsid: String? = null,
    val wanIpAddress: String? = null,
    val uptimeMinutes: Long? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val source: DataSource = DataSource.ROUTER,
)

/** One battery observation, used by [com.zltm90plus.app.domain.BatteryEstimator]. */
data class BatteryReading(
    val timestampMillis: Long,
    val percent: Int,
    val chargingState: ChargingState,
)

data class DeviceSnapshot(
    val deviceInfo: RouterDeviceInfo = RouterDeviceInfo(source = DataSource.UNAVAILABLE),
    val battery: BatteryStatus = BatteryStatus(source = DataSource.UNAVAILABLE),
    val network: NetworkStatus = NetworkStatus(),
    val plan: DataPlanStatus = DataPlanStatus(source = DataSource.UNAVAILABLE),
    val connectedDevices: ConnectedDevices = ConnectedDevices(source = DataSource.UNAVAILABLE),
    val capturedAtMillis: Long = System.currentTimeMillis(),
)
