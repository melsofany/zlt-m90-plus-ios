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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zltm90plus.app.diagnostics.Diagnostics
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
    val isDiscovering: Boolean = false,
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
        // Start the form on the address the phone is actually routing through. The old default
        // was a fixed 192.168.0.1, which is simply the wrong device address on builds that ship
        // 192.168.1.1, and a wrong address looks exactly like a broken app.
        LocalNetworkChecker.currentGatewayIpv4(getApplication())?.let { gateway ->
            _state.update { it.copy(loginForm = it.loginForm.copy(host = gateway)) }
        }
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

        viewModelScope.launch {
            factory.configure(form.host, if (_state.value.demoMode) _state.value.demoScenario else null)
            try {
                repository.login(form.username, form.password)
                _state.update { it.copy(phase = ConnectionPhase.CONNECTED) }
                refresh()
                startAutoRefresh()
            } catch (error: Throwable) {
                // Not being on Wi-Fi is the likeliest reason the device did not answer, and it
                // has its own actionable message, so it refines an unreachable result rather
                // than blocking the attempt up front (a LAN adapter or VPN makes the platform's
                // Wi-Fi flag unreliable).
                val phase = if (!presence.connectedToWifi && error.isUnreachable()) {
                    ConnectionPhase.PHONE_NOT_ON_DEVICE_NETWORK
                } else {
                    mapError(error)
                }
                _state.update {
                    it.copy(
                        phase = phase,
                        userMessage = if (phase == ConnectionPhase.PHONE_NOT_ON_DEVICE_NETWORK) {
                            RouterError.PhoneNotConnectedToDeviceNetwork().userMessage
                        } else {
                            (error as? RouterError)?.userMessage ?: "تعذر الاتصال بالجهاز. حاول مرة أخرى."
                        },
                        technicalDetail = (error as? RouterError)?.technicalDetail,
                    )
                }
            }
        }
    }

    private fun Throwable.isUnreachable(): Boolean =
        this is RouterError.DeviceNotFound || this is RouterError.Timeout

    /**
     * Looks for the router on the current network.
     *
     * The previous version only guessed `x.y.z.1` from the phone's address and reported it as if
     * it had been confirmed, which is exactly the kind of fabricated result this app must not
     * present. This probes every candidate and only claims one that actually answered.
     */
    fun discoverDevice() {
        val presence = LocalNetworkChecker.current(getApplication())
        val gateway = LocalNetworkChecker.currentGatewayIpv4(getApplication())
        val candidates = LocalNetworkChecker.discoveryCandidates(presence.localIpv4, gateway)
        _state.update {
            it.copy(
                wifiConnected = presence.connectedToWifi,
                isDiscovering = true,
                userMessage = if (!presence.connectedToWifi) {
                    RouterError.PhoneNotConnectedToDeviceNetwork().userMessage
                } else {
                    "جاري البحث عن الجهاز على الشبكة…"
                },
            )
        }

        if (!presence.connectedToWifi) {
            _state.update { it.copy(isDiscovering = false) }
            return
        }

        viewModelScope.launch {
            val found = probeCandidates(candidates)
            _state.update {
                it.copy(
                    isDiscovering = false,
                    loginForm = if (found != null) it.loginForm.copy(host = found) else it.loginForm,
                    phase = if (found != null) ConnectionPhase.IDLE else it.phase,
                    userMessage = when (found) {
                        null -> "لم يستجب أي جهاز على العناوين المجرَّبة (${candidates.size} عنوانًا). " +
                            "افتح لوحة الإدارة في المتصفح وتأكد من العنوان، ثم أدخله يدويًا."
                        else -> "تم العثور على جهاز يستجيب على العنوان $found. أدخل بيانات الدخول واضغط اتصال بالجهاز."
                    },
                )
            }
        }
    }

    /**
     * Each candidate gets a short deadline so one dead address cannot stall the whole search.
     * Addresses already known to be wrong are skipped by the caller through plain ordering.
     */
    private suspend fun probeCandidates(candidates: List<String>): String? {
        val probeClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        for (candidate in candidates) {
            val startedAt = System.currentTimeMillis()
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url("http://$candidate/").get().build()
                    probeClient.newCall(request).execute().use { true }
                }
            }
            Diagnostics.recordProbe(
                url = "http://$candidate/",
                reachable = outcome.getOrDefault(false),
                detail = outcome.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" },
                durationMillis = System.currentTimeMillis() - startedAt,
            )
            if (outcome.getOrDefault(false)) return candidate
        }
        return null
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
        is RouterError.DeviceNotFound, is RouterError.InvalidHost -> ConnectionPhase.DEVICE_NOT_FOUND
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