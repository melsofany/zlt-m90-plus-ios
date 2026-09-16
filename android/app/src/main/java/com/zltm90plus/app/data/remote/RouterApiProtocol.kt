package com.zltm90plus.app.data.remote

import com.zltm90plus.app.data.model.BatteryStatus
import com.zltm90plus.app.data.model.ConnectedDevices
import com.zltm90plus.app.data.model.DataPlanStatus
import com.zltm90plus.app.data.model.NetworkStatus
import com.zltm90plus.app.data.model.RouterDeviceInfo

/**
 * A firmware response the app could not interpret. Firmware paths and field names change
 * between ZLT M90 Plus versions and carrier builds, so this is an expected outcome rather
 * than an exceptional one and gets its own user-facing branch.
 */
class UnsupportedFirmwareException(
    message: String,
    val technicalDetail: String? = null,
) : Exception(message)

/** Core router operations. Implemented by [ZltRouterApi] (real device) and [MockRouterApi]. */
interface RouterApiProtocol {

    /** Establishes a session and stores the resulting token. */
    suspend fun login(username: String, password: String)

    suspend fun fetchDeviceInfo(): RouterDeviceInfo

    suspend fun fetchBatteryStatus(): BatteryStatus

    suspend fun fetchNetworkStatus(): NetworkStatus

    /**
     * Carrier plan counters. Most ZLT M90 Plus builds do not expose these; a default
     * implementation may throw [UnsupportedFirmwareException] instead of returning zeros.
     */
    suspend fun fetchDataUsage(): DataPlanStatus

    suspend fun fetchConnectedDevices(): ConnectedDevices

    /** Affects the live device. Callers must confirm with the user before invoking. */
    suspend fun updateWiFi(ssid: String, password: String)

    /** Affects the live device. Callers must confirm with the user before invoking. */
    suspend fun restartRouter()

    /** Drops any stored session. Safe to call at any time. */
    suspend fun logout()

    /** Probe that does not depend on the router web UI responding. */
    suspend fun probeInternet(): Boolean

    /**
     * Reports whether a web interface answers at the configured host, without credentials.
     * Used by device discovery to test candidate addresses instead of guessing one.
     */
    suspend fun probeWebInterface(): Boolean
}
