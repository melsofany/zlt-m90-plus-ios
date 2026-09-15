package com.zltm90plus.app.data.repository

import android.content.Context
import com.zltm90plus.app.data.model.BatteryReading
import com.zltm90plus.app.data.model.BatteryStatus
import com.zltm90plus.app.data.model.ConnectedDevices
import com.zltm90plus.app.data.model.DataPlanStatus
import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.data.model.DeviceSnapshot
import com.zltm90plus.app.data.model.NetworkStatus
import com.zltm90plus.app.data.model.RouterDeviceInfo
import com.zltm90plus.app.data.remote.RouterApiProtocol
import com.zltm90plus.app.data.remote.RouterError
import com.zltm90plus.app.data.remote.ZltRouterApi
import com.zltm90plus.app.domain.BatteryEstimator
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single point of access for router data.
 *
 * Responsibilities:
 * - Reads the device once per refresh and assembles a [DeviceSnapshot].
 * - Keeps a rolling battery history so [BatteryEstimator] has real data to work with.
 * - Merges carrier or manually entered plan data on top of whatever the device provides,
 *   preserving the correct [DataSource] on every value.
 */
class RouterRepository(
    private val apiProvider: () -> RouterApiProtocol,
    private val historyStore: BatteryHistoryStore,
    private val manualPlanStore: ManualPlanStore,
) {

    val api: RouterApiProtocol get() = apiProvider()

    suspend fun login(username: String, password: String) = api.login(username, password)

    suspend fun logout() = api.logout()

    suspend fun loadSnapshot(): DeviceSnapshot {
        val deviceInfo = runCatching { api.fetchDeviceInfo() }
            .getOrElse { RouterDeviceInfo(source = DataSource.UNAVAILABLE) }

        val battery = runCatching { api.fetchBatteryStatus() }
            .getOrElse { BatteryStatus(source = DataSource.UNAVAILABLE) }

        val network = runCatching { api.fetchNetworkStatus() }
            .getOrElse { NetworkStatus(source = DataSource.UNAVAILABLE) }

        val connected = runCatching { api.fetchConnectedDevices() }
            .getOrElse { ConnectedDevices(source = DataSource.UNAVAILABLE) }

        // Record the reading before estimating so the estimator sees the current sample too.
        battery.percent?.let { percent ->
            historyStore.append(
                BatteryReading(
                    timestampMillis = battery.updatedAtMillis,
                    percent = percent,
                    chargingState = battery.chargingState,
                ),
            )
        }

        val devicePlan = runCatching { api.fetchDataUsage() }
            .getOrElse { DataPlanStatus(source = DataSource.UNAVAILABLE) }

        val plan = mergePlan(devicePlan, manualPlanStore.load())

        val probeSucceeded = runCatching { api.probeInternet() }.getOrDefault(false)
        val networkResolved = applyProbe(network, probeSucceeded, deviceInfo)

        return DeviceSnapshot(
            deviceInfo = deviceInfo,
            battery = battery,
            network = networkResolved,
            plan = plan,
            connectedDevices = connected,
        )
    }

    fun batteryReadings(): List<BatteryReading> = historyStore.load()

    private fun applyProbe(network: NetworkStatus, probeSucceeded: Boolean, info: RouterDeviceInfo): NetworkStatus {
        if (!probeSucceeded) {
            return network.copy(
                state = when (network.state) {
                    com.zltm90plus.app.data.model.NetworkState.NO_CELLULAR ->
                        com.zltm90plus.app.data.model.NetworkState.NO_CELLULAR
                    else -> com.zltm90plus.app.data.model.NetworkState.ROUTER_ONLY_NO_INTERNET
                },
                lastSuccessfulProbeMillis = network.lastSuccessfulProbeMillis,
            )
        }
        return network.copy(
            state = if (network.state == com.zltm90plus.app.data.model.NetworkState.NO_CELLULAR) {
                com.zltm90plus.app.data.model.NetworkState.NO_CELLULAR
            } else {
                com.zltm90plus.app.data.model.NetworkState.ONLINE
            },
            lastSuccessfulProbeMillis = System.currentTimeMillis(),
        )
    }

    /**
     * Device-reported plan data always wins. Manual entry fills the gap only when the firmware
     * exposes nothing, and is relabelled [DataSource.MANUAL] so the UI can say so.
     */
    private fun mergePlan(devicePlan: DataPlanStatus, manualPlan: DataPlanStatus?): DataPlanStatus {
        if (devicePlan.isAvailable || devicePlan.source == DataSource.CARRIER) return devicePlan
        return manualPlan?.copy(source = DataSource.MANUAL) ?: devicePlan
    }
}

/** Persists the rolling battery history. Small, bounded, and non-sensitive. */
class BatteryHistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("zlt_battery_history", Context.MODE_PRIVATE)

    fun append(reading: BatteryReading) {
        val current = load().toMutableList()
        current += reading
        val trimmed = current.takeLast(BatteryEstimator.MAX_HISTORY)
        val array = JSONArray()
        trimmed.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("t", item.timestampMillis)
                    put("p", item.percent)
                    put("c", item.chargingState.name)
                },
            )
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun load(): List<BatteryReading> = runCatching {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val timestamp = obj.optLong("t", 0L).takeIf { it > 0L } ?: return@mapNotNull null
            val percent = obj.optInt("p", -1).takeIf { it in 0..100 } ?: return@mapNotNull null
            val state = runCatching {
                com.zltm90plus.app.data.model.ChargingState.valueOf(obj.optString("c"))
            }.getOrDefault(com.zltm90plus.app.data.model.ChargingState.UNKNOWN)
            BatteryReading(timestamp, percent, state)
        }
    }.getOrDefault(emptyList())

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private companion object {
        const val KEY_HISTORY = "readings"
    }
}

/** Non-sensitive user-entered plan values. Passwords are never stored here. */
class ManualPlanStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("zlt_manual_plan", Context.MODE_PRIVATE)

    fun save(plan: DataPlanStatus) {
        prefs.edit()
            .putLong(KEY_TOTAL, plan.totalBytes ?: -1L)
            .putLong(KEY_USED, plan.usedBytes ?: -1L)
            .putLong(KEY_START, plan.planStartMillis ?: -1L)
            .putLong(KEY_RENEWAL, plan.renewalMillis ?: -1L)
            .putString(KEY_OPERATOR, plan.operatorName)
            .apply()
    }

    fun load(): DataPlanStatus? {
        val total = prefs.getLong(KEY_TOTAL, -1L).takeIf { it >= 0 } ?: return null
        return DataPlanStatus(
            totalBytes = total,
            usedBytes = prefs.getLong(KEY_USED, -1L).takeIf { it >= 0 },
            planStartMillis = prefs.getLong(KEY_START, -1L).takeIf { it >= 0 },
            renewalMillis = prefs.getLong(KEY_RENEWAL, -1L).takeIf { it >= 0 },
            operatorName = prefs.getString(KEY_OPERATOR, null),
            source = DataSource.MANUAL,
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_TOTAL = "total_bytes"
        const val KEY_USED = "used_bytes"
        const val KEY_START = "plan_start"
        const val KEY_RENEWAL = "renewal"
        const val KEY_OPERATOR = "operator"
    }
}

/** Exposed for the login screen so connection failures map to a friendly Arabic message. */
fun Throwable.toUserMessage(): String = when (this) {
    is RouterError -> userMessage
    else -> "تعذر إتمام العملية. حاول مرة أخرى."
}