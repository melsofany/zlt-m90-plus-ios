package com.zltm90plus.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zltm90plus.app.data.model.ChargingState
import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.data.model.DeviceSnapshot
import com.zltm90plus.app.data.model.NetworkState
import com.zltm90plus.app.data.remote.MockRouterApi
import com.zltm90plus.app.data.remote.RouterProbe
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
import com.zltm90plus.app.diagnostics.DiagnosticExchange
import com.zltm90plus.app.diagnostics.DiagnosticLog
import com.zltm90plus.app.diagnostics.Diagnostics

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
    /**
     * Discovered alongside the address. Defaults to http because every documented firmware build
     * serves its admin UI over plain HTTP; it flips to https only when the device itself proves
     * it, by answering a plain-HTTP probe with a redirect or a TLS handshake.
     */
    val scheme: String = "http",
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

    private val probe = RouterProbe()

    private var autoRefreshJob: Job? = null

    // --- state helpers -----------------------------------------------------------------------
    private fun updateState(block: (DashboardUiState) -> DashboardUiState) {
        _state.update(block)
    }

    private fun updateLoginForm(block: (LoginFormState) -> LoginFormState) {
        _state.update { it.copy(loginForm = block(it.loginForm)) }
    }

    private fun updatePhase(phase: ConnectionPhase) {
        _state.update { it.copy(phase = phase) }
    }

    private fun updateConnectionStatus(wifiConnected: Boolean, isDiscovering: Boolean = false) {
        _state.update { it.copy(wifiConnected = wifiConnected, isDiscovering = isDiscovering) }
    }

    private fun updateUserMessage(message: String?) {
        _state.update { it.copy(userMessage = message) }
    }

    private fun updateTechnicalDetail(detail: String?) {
        _state.update { it.copy(technicalDetail = detail) }
    }

    // --- connection ------------------------------------------------------------------------

    init {
        refreshNetworkPresence()
        // Start the form on the address the phone is actually routing through. The old default
        // was a fixed 192.168.0.1, which is simply the wrong device address on builds that ship
        // 192.168.1.1, and a wrong address looks exactly like a broken app.
        //
        // The gateway comes from Android rather than a guess, so on a network whose router is not
        // at `.1` this still starts on the right address. Discovery only has to run when the
        // platform cannot tell us, which is when the phone routes through a VPN or a LAN adapter.
        LocalNetworkChecker.currentGatewayIpv4(getApplication())?.let { gateway ->
            updateLoginForm { it.copy(host = gateway) }
        }
    }

    // --- connection ------------------------------------------------------------------------

    fun updateHost(value: String) = updateLoginForm { it.copy(host = value) }

    fun updateUsername(value: String) = updateLoginForm { it.copy(username = value) }

    fun updatePassword(value: String) = updateLoginForm { it.copy(password = value) }

    fun setDemoScenario(scenario: MockRouterApi.Scenario) =
        updateState { it.copy(demoScenario = scenario, demoMode = true) }

    /**
     * Explicit demo entry: requires a user tap, and the UI keeps a visible "sample data" banner
     * for as long as it is active.
     */
    fun enableDemoMode(scenario: MockRouterApi.Scenario = MockRouterApi.Scenario.NORMAL) {
        updateState {
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
        
        updatePhase(ConnectionPhase.CONNECTING)
        updateConnectionStatus(presence.connectedToWifi)
        updateUserMessage(null)
        updateTechnicalDetail(null)

        viewModelScope.launch {
            factory.configure(
                host = form.host,
                scheme = form.scheme,
                demoScenario = if (_state.value.demoMode) _state.value.demoScenario else null,
            )
            try {
                repository.login(form.username, form.password)
                updatePhase(ConnectionPhase.CONNECTED)
                refresh()
                startAutoRefresh()
            } catch (error: Throwable) {
                // Not being on Wi-Fi is the likeliest reason the device did not answer, and it
                // has its own actionable message, so it refines an unreachable result rather
                // than blocking the attempt up front (a LAN adapter or VPN makes the platform's
                // Wi-Fi flag unreliable).
                val phase = when {
                    // A phone on a different subnet than the device has no route to it, and the
                    // resulting timeout is indistinguishable from a broken device. This check
                    // names it instead, which is the difference between "check the device" and
                    // "join the right network".
                    !presence.connectedToWifi && error.isUnreachable() ->
                        ConnectionPhase.PHONE_NOT_ON_DEVICE_NETWORK

                    error.isUnreachable() &&
                        !LocalNetworkChecker.isPlausiblyLocal(form.host, presence.localIpv4) ->
                        ConnectionPhase.PHONE_NOT_ON_DEVICE_NETWORK

                    else -> mapError(error)
                }
                val userMessage = when {
                    phase == ConnectionPhase.PHONE_NOT_ON_DEVICE_NETWORK &&
                        presence.connectedToWifi ->
                        RouterError.WrongNetwork(form.host, presence.localIpv4).userMessage

                    phase == ConnectionPhase.PHONE_NOT_ON_DEVICE_NETWORK ->
                        RouterError.PhoneNotConnectedToDeviceNetwork().userMessage

                    else -> (error as? RouterError)?.userMessage ?: "تعذر الاتصال بالجهاز. حاول مرة أخرى."
                }
                updatePhase(phase)
                updateUserMessage(userMessage)
                updateTechnicalDetail((error as? RouterError)?.technicalDetail)
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
        updateConnectionStatus(presence.connectedToWifi, true)
        updateUserMessage(
            if (!presence.connectedToWifi) {
                RouterError.PhoneNotConnectedToDeviceNetwork().userMessage
            } else {
                "جاري البحث عن الجهاز على الشبكة…"
            }
        )

        if (!presence.connectedToWifi) {
            updateConnectionStatus(presence.connectedToWifi, false)
            return
        }

        viewModelScope.launch {
            val result = probeCandidates(candidates)
            val found = result.found
            _state.update {
                it.copy(
                    isDiscovering = false,
                    // Remembering the scheme matters when the device only serves HTTPS: a login
                    // over plain http would then fail even though discovery just succeeded.
                    loginForm = if (found != null) {
                        it.loginForm.copy(host = found, scheme = result.scheme ?: "http")
                    } else {
                        it.loginForm
                    },
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
     *
     * The reachability rule lives in [RouterProbe]: anything that answers HTTP counts, including a
     * redirect onto the device's own HTTPS or a TLS handshake Android will not complete.
     */
    private suspend fun probeCandidates(candidates: List<String>): ProbeResult {
        val hit = probe.firstReachable(candidates)
        return ProbeResult(found = hit?.first, scheme = hit?.second?.scheme)
    }

    data class ProbeResult(val found: String?, val scheme: String?)

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

    // --- diagnostics ------------------------------------------------------------------------

    /**
     * The connection log, exposed to the UI. It is the process-wide log rather than a copy, so the
     * screen shows exactly what the transport recorded, and it updates as exchanges arrive.
     */
    val diagnosticExchanges: StateFlow<List<DiagnosticExchange>> = DiagnosticLog.exchanges

    /** The share text for the log. Redaction already happened at capture time. */
    fun diagnosticsReportText(): String = DiagnosticLog.asText()

    fun clearDiagnostics() = DiagnosticLog.clear()

    fun toggleTechnicalDetails() =
        _state.update { it.copy(showTechnicalDetails = !it.showTechnicalDetails) }

    private fun mapError(error: Throwable): ConnectionPhase = when (error) {
        is RouterError.InvalidCredentials -> ConnectionPhase.INVALID_CREDENTIALS
        is RouterError.DeviceNotFound, is RouterError.InvalidHost -> ConnectionPhase.DEVICE_NOT_FOUND
        is RouterError.WrongNetwork, is RouterError.PhoneNotConnectedToDeviceNetwork ->
            ConnectionPhase.PHONE_NOT_ON_DEVICE_NETWORK
        is RouterError.UnsupportedFirmware -> ConnectionPhase.UNSUPPORTED_FIRMWARE
        is RouterError.DeviceResponseUnreadable -> ConnectionPhase.DEVICE_NOT_FOUND
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