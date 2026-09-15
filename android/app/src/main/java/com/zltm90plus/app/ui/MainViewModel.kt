package com.zltm90plus.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zltm90plus.app.data.model.ChargingState
import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.data.model.DeviceSnapshot
import com.zltm90plus.app.data.model.NetworkState
import com.zltm90plus.app.data.remote.MockRouterApi
import com.zltm90plus.app.data.remote.RouterError
import com.zltm90plus.app.data.repository.BatteryHistoryStore
import com.zltm90plus.app.data.repository.ManualPlanStore
import com.zltm90plus.app.data.repository.RouterApiFactory
import com.zltm90plus.app.data.repository.RouterRepository
import com.zltm90plus.app.data.session.SecureSessionStore
import com.zltm90plus.app.domain.BatteryEstimator
import com.zltm90plus.app.util.LocalNetworkChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectionPhase {
    IDLE,
    CONNECTING,
    CONNECTED,
    INVALID_CREDENTIALS,
    DEVICE_NOT_FOUND,
    PHONE_NOT_ON_DEVICE_NETWORK,
    UNSUPPORTED_FIRMWARE,
    TEMPORARY_FAILURE,
    SESSION_EXPIRED,
}

data class LoginFormState(
    val host: String = "192.168.0.1",
    val username: String = "admin",
    val password: String = "",
    val rememberHost: Boolean = true,
)

data class DashboardUiState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val loginForm: LoginFormState = LoginFormState(),
    val snapshot: DeviceSnapshot? = null,
    val batteryEstimate: BatteryEstimator.Result? = null,
    val isRefreshing: Boolean = false,
    val demoMode: Boolean = false,
    val demoScenario: MockRouterApi.Scenario = MockRouterApi.Scenario.NORMAL,
    val userMessage: String? = null,
    val technicalDetail: String? = null,
    val showTechnicalDetails: Boolean = false,
    val wifiConnected: Boolean = false,
    val lastRefreshMillis: Long? = null,
    val batteryHistory: List<com.zltm90plus.app.data.model.BatteryReading> = emptyList(),
) {
    val isConnected: Boolean get() = phase == ConnectionPhase.CONNECTED
    val isDemo: Boolean get() = demoMode
}

/**
 * Owns all screen state and the only place that talks to [RouterRepository].
 *
 * A failed refresh never wipes the last good snapshot: a stale-but-labelled value is more
 * useful than an empty screen, and the UI shows the last-updated timestamp next to it.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionStore = SecureSessionStore(application)
    private val factory = RouterApiFactory(application, sessionStore)
    private val repository = RouterRepository(
        apiProvider = { factory.create() },
        historyStore = BatteryHistoryStore(application),
        manualPlanStore = ManualPlanStore(application),
    )

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var autoRefreshJob: Job? = null

    init {
        refreshNetworkPresence()
    }

    // --- connection ------------------------------------------------------------------------

    fun updateHost(value: String) = _state.update { it.copy(loginForm = it.loginForm.copy(host = value)) }

    fun updateUsername(value: String) = _state.update { it.copy(loginForm = it.loginForm.copy(username = value)) }

    fun updatePassword(value: String) = _state.update { it.copy(loginForm = it.loginForm.copy(password = value)) }

    fun setDemoScenario(scenario: MockRouterApi.Scenario) =
        _state.update { it.copy(demoScenario = scenario, demoMode = true) }

    /**
     * Explicit demo entry: requires a user tap, and the UI keeps a visible "sample data" banner
     * for as long as it is active.
     */
    fun enableDemoMode(scenario: MockRouterApi.Scenario = MockRouterApi.Scenario.NORMAL) {
        _state.update {
            it.copy(
                demoMode = true,
                demoScenario = scenario,
                loginForm = it.loginForm.copy(host = "192.168.0.1"),
            )
        }
        connect()
    }

    fun connect() {
        val form = _state.value.loginForm
        val presence = LocalNetworkChecker.current(getApplication())
        _state.update {
            it.copy(
                phase = ConnectionPhase.CONNECTING,
                wifiConnected = presence.connectedToWifi,
                userMessage = null,
                technicalDetail = null,
            )
        }

        if (!_state.value.demoMode && !presence.connectedToWifi) {
            _state.update {
                it.copy(
                    phase = ConnectionPhase.PHONE_NOT_ON_DEVICE_NETWORK,
                    userMessage = RouterError.PhoneNotConnectedToDeviceNetwork().userMessage,
                )
            }
            return
        }

        viewModelScope.launch {
            factory.configure(form.host, if (_state.value.demoMode) _state.value.demoScenario else null)
            try {
                repository.login(form.username, form.password)
                _state.update { it.copy(phase = ConnectionPhase.CONNECTED) }
                refresh()
                startAutoRefresh()
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        phase = mapError(error),
                        userMessage = (error as? RouterError)?.userMessage
                            ?: "تعذر الاتصال بالجهاز. حاول مرة أخرى.",
                        technicalDetail = (error as? RouterError)?.technicalDetail,
                    )
                }
            }
        }
    }

    /** Best-effort discovery of the router on the current subnet. */
    fun discoverDevice() {
        val presence = LocalNetworkChecker.current(getApplication())
        val candidate = presence.localIpv4
            ?.split(".")
            ?.takeIf { it.size == 4 }
            ?.let { "${it[0]}.${it[1]}.${it[2]}.1" }
            ?: "192.168.0.1"
        _state.update {
            it.copy(
                loginForm = it.loginForm.copy(host = candidate),
                wifiConnected = presence.connectedToWifi,
                userMessage = if (!presence.connectedToWifi) {
                    RouterError.PhoneNotConnectedToDeviceNetwork().userMessage
                } else {
                    "تم اقتراح العنوان $candidate بناءً على شبكتك الحالية. عدّله إذا كان مختلفًا."
                },
            )
        }
    }

    fun disconnect() {
        autoRefreshJob?.cancel()
        viewModelScope.launch {
            runCatching { repository.logout() }
            _state.update {
                it.copy(
                    phase = ConnectionPhase.IDLE,
                    snapshot = null,
                    batteryEstimate = null,
                    demoMode = false,
                    userMessage = null,
                )
            }
        }
    }

    // --- refresh ---------------------------------------------------------------------------

    fun refresh() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                val snapshot = repository.loadSnapshot()
                val estimate = computeEstimate(snapshot)
                _state.update {
                    it.copy(
                        phase = if (it.phase == ConnectionPhase.CONNECTING) it.phase else ConnectionPhase.CONNECTED,
                        snapshot = snapshot,
                        batteryEstimate = estimate,
                        isRefreshing = false,
                        lastRefreshMillis = System.currentTimeMillis(),
                        batteryHistory = repository.batteryReadings(),
                        userMessage = null,
                        technicalDetail = null,
                    )
                }
            } catch (error: Throwable) {
                // Keep the previous snapshot so the user still sees the last known values.
                _state.update {
                    it.copy(
                        isRefreshing = false,
                        phase = if (error is RouterError.SessionExpired) ConnectionPhase.SESSION_EXPIRED else it.phase,
                        userMessage = (error as? RouterError)?.userMessage
                            ?: "تعذر تحديث البيانات. تحقق من الاتصال بالجهاز.",
                        technicalDetail = (error as? RouterError)?.technicalDetail,
                    )
                }
            }
        }
    }

    private fun computeEstimate(snapshot: DeviceSnapshot): BatteryEstimator.Result {
        val percent = snapshot.battery.percent
        val current = percent?.let {
            com.zltm90plus.app.data.model.BatteryReading(
                timestampMillis = snapshot.battery.updatedAtMillis,
                percent = it,
                chargingState = snapshot.battery.chargingState,
            )
        }
        return BatteryEstimator.estimate(
            readings = repository.batteryReadings(),
            current = current,
            deviceReportedMinutes = snapshot.battery.deviceReportedRemainingMinutes,
        )
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                if (_state.value.isConnected) refresh() else break
            }
        }
    }

    fun refreshNetworkPresence() {
        val presence = LocalNetworkChecker.current(getApplication())
        _state.update { it.copy(wifiConnected = presence.connectedToWifi) }
    }

    // --- data plan -------------------------------------------------------------------------

    fun saveManualPlan(totalBytes: Long, usedBytes: Long?, startMillis: Long?, renewalMillis: Long?, operator: String?) {
        ManualPlanStore(getApplication()).save(
            com.zltm90plus.app.data.model.DataPlanStatus(
                totalBytes = totalBytes,
                usedBytes = usedBytes,
                planStartMillis = startMillis,
                renewalMillis = renewalMillis,
                operatorName = operator,
                source = DataSource.MANUAL,
            ),
        )
        refresh()
    }

    // --- destructive actions ---------------------------------------------------------------

    /** Only called after the user confirms in the UI. Never invoked in demo mode. */
    fun updateWifi(ssid: String, password: String, onResult: (Result<Unit>) -> Unit) {
        if (_state.value.demoMode) {
            onResult(Result.failure(RouterError.FeatureNotSupported("تعديل Wi-Fi", "وضع العرض التجريبي")))
            return
        }
        viewModelScope.launch {
            onResult(runCatching { repository.api.updateWiFi(ssid, password) })
        }
    }

    fun restartRouter(onResult: (Result<Unit>) -> Unit) {
        if (_state.value.demoMode) {
            onResult(Result.failure(RouterError.FeatureNotSupported("إعادة التشغيل", "وضع العرض التجريبي")))
            return
        }
        viewModelScope.launch {
            onResult(runCatching { repository.api.restartRouter() })
        }
    }

    fun dismissMessage() = _state.update { it.copy(userMessage = null, technicalDetail = null) }

    fun toggleTechnicalDetails() =
        _state.update { it.copy(showTechnicalDetails = !it.showTechnicalDetails) }

    private fun mapError(error: Throwable): ConnectionPhase = when (error) {
        is RouterError.InvalidCredentials -> ConnectionPhase.INVALID_CREDENTIALS
        is RouterError.DeviceNotFound -> ConnectionPhase.DEVICE_NOT_FOUND
        is RouterError.PhoneNotConnectedToDeviceNetwork -> ConnectionPhase.PHONE_NOT_ON_DEVICE_NETWORK
        is RouterError.UnsupportedFirmware -> ConnectionPhase.UNSUPPORTED_FIRMWARE
        is RouterError.SessionExpired -> ConnectionPhase.SESSION_EXPIRED
        is RouterError.Timeout, is RouterError.TemporaryFailure -> ConnectionPhase.TEMPORARY_FAILURE
        is RouterError.FeatureNotSupported -> ConnectionPhase.UNSUPPORTED_FIRMWARE
        else -> ConnectionPhase.TEMPORARY_FAILURE
    }

    private companion object {
        const val AUTO_REFRESH_INTERVAL_MS = 60_000L
    }
}

/** Human-readable Arabic description of a charging state. */
fun ChargingState.label(): String = when (this) {
    ChargingState.CHARGING -> "يشحن"
    ChargingState.DISCHARGING -> "يعمل على البطارية"
    ChargingState.FULL -> "مكتمل الشحن"
    ChargingState.UNKNOWN -> "غير معروفة"
}

fun NetworkState.label(): String = when (this) {
    NetworkState.ONLINE -> "متصل بالإنترنت"
    NetworkState.ROUTER_ONLY_NO_INTERNET -> "متصل بالجهاز فقط ولا يوجد إنترنت"
    NetworkState.NO_CELLULAR -> "لا توجد شبكة هاتف"
    NetworkState.CONNECTING -> "جاري الاتصال"
    NetworkState.UNKNOWN -> "غير معروف"
}