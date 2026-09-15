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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

const val DEFAULT_ROUTER_HOST = "192.168.0.1"

/** Storage abstraction so unit tests can inject an in-memory session without the Keystore. */
interface SessionTokenStore {
    fun saveToken(token: String)
    fun token(): String?
    fun clear()
}

/**
 * Talks to the ZLT M90 Plus local web interface.
 *
 * Design rules enforced here:
 * - Every route and response field name comes from [RouterRoutesConfig], never hard-coded.
 * - A field the firmware does not return becomes null with [DataSource.UNAVAILABLE]; the app
 *   never fabricates a plausible-looking number.
 * - Credentials and tokens are never logged.
 */
class ZltRouterApi(
    private val config: RouterRoutesConfig,
    private val sessionStore: SessionTokenStore,
    private val hostProvider: () -> String = { DEFAULT_ROUTER_HOST },
    private val clientFactory: (RouterRoutesConfig) -> OkHttpClient = ::defaultClient,
) : RouterApiProtocol {

    private val client: OkHttpClient by lazy { clientFactory(config) }

    override suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        val route = config.route("login")
            ?: throw RouterError.UnsupportedFirmware("لم يُضبط مسار تسجيل الدخول في router_routes.json")
        val body = route.bodyTemplate.mapValues { (_, template) ->
            template.replace("{username}", username).replace("{password}", password)
        }
        execute(route, body).use { response ->
            val text = response.body?.string().orEmpty()
            ensureAuthResponse(response, text, route)
            val node = ResponseParser.parse(text, config.encoding)
            val cookieToken = ResponseParser.extractSessionToken(response.headers.toMultimap())
            val bodyToken = node?.findString(TOKEN_ALIASES)
            (cookieToken ?: bodyToken)?.let { sessionStore.saveToken(it) }
            // A cookie-only session needs no explicit token: the CookieJar retains it.
        }
        Unit
    }

    override suspend fun fetchDeviceInfo(): RouterDeviceInfo =
        requiredQuery("deviceInfo") { node ->
            RouterDeviceInfo(
                model = node.findString(config.aliases("deviceModel")),
                firmwareVersion = node.findString(config.aliases("firmwareVersion")),
                hardwareVersion = node.findString(config.aliases("hardwareVersion")),
                imei = node.findString(config.aliases("imei")),
                serialNumber = node.findString(config.aliases("serialNumber")),
                wifiSsid = node.findString(config.aliases("wifiSsid")),
                wanIpAddress = node.findString(config.aliases("wanIp")),
                uptimeMinutes = node.findInt(config.aliases("uptimeMinutes"))?.toLong(),
                source = DataSource.ROUTER,
            )
        }

    override suspend fun fetchBatteryStatus(): BatteryStatus =
        optionalQuery("battery") { node ->
            val percent = node.findInt(config.aliases("batteryPercent"))?.takeIf { it in 0..100 }
            BatteryStatus(
                percent = percent,
                chargingState = readChargingState(node, percent),
                deviceReportedRemainingMinutes = node.findInt(config.aliases("batteryRemainingMinutes")),
                voltageMillivolts = node.findInt(config.aliases("batteryVoltage")),
                temperatureCelsius = node.findDouble(config.aliases("batteryTemperature")),
                source = if (percent != null) DataSource.ROUTER else DataSource.UNAVAILABLE,
            )
        } ?: BatteryStatus(chargingState = ChargingState.UNKNOWN, source = DataSource.UNAVAILABLE)

    override suspend fun fetchNetworkStatus(): NetworkStatus =
        optionalQuery("network") { node ->
            val registered = node.findBoolean(config.aliases("cellularRegistered"), config)
            val internetFlag = node.findBoolean(config.aliases("internetConnected"), config)
            val signalPercent = node.findInt(config.aliases("signalPercent"))
            val signalDbm = node.findDouble(config.aliases("signalDbm"))?.toInt()
            NetworkStatus(
                state = when {
                    registered == false -> NetworkState.NO_CELLULAR
                    internetFlag == true -> NetworkState.ONLINE
                    internetFlag == false -> NetworkState.ROUTER_ONLY_NO_INTERNET
                    else -> NetworkState.UNKNOWN
                },
                carrierName = node.findString(config.aliases("carrierName")),
                networkType = normalizeNetworkType(node.findString(config.aliases("networkType"))),
                signalLevel = deriveSignalLevel(node, signalPercent, signalDbm),
                signalPercent = signalPercent,
                signalDbm = signalDbm,
                localIpAddress = node.findString(config.aliases("localIp")),
                connectionUptimeMinutes = node.findInt(config.aliases("uptimeMinutes"))?.toLong(),
                source = DataSource.ROUTER,
            )
        } ?: NetworkStatus(source = DataSource.UNAVAILABLE)

    /**
     * Plan counters are rarely exposed by ZLT M90 Plus firmware. When the route is missing or
     * returns nothing usable this reports [DataSource.UNAVAILABLE] rather than zero usage.
     */
    override suspend fun fetchDataUsage(): DataPlanStatus =
        optionalQuery("dataUsage") { node ->
            val total = readBytes(node, "planTotalBytes")
            val used = readBytes(node, "planUsedBytes")
            DataPlanStatus(
                totalBytes = total,
                usedBytes = used,
                planStartMillis = node.findString(config.aliases("planStart"))?.let(::parseDate),
                renewalMillis = node.findString(config.aliases("planRenewal"))?.let(::parseDate),
                operatorName = node.findString(config.aliases("carrierName")),
                source = if (total != null && used != null) DataSource.ROUTER else DataSource.UNAVAILABLE,
            )
        } ?: DataPlanStatus(source = DataSource.UNAVAILABLE)

    override suspend fun fetchConnectedDevices(): ConnectedDevices =
        optionalQuery("connectedDevices") { node ->
            val items = node.findArray(config.aliases("deviceList"))
                ?: return@optionalQuery ConnectedDevices(source = DataSource.UNAVAILABLE)
            ConnectedDevices(
                devices = items.map { item ->
                    ConnectedDevice(
                        hostname = item.findString(config.aliases("deviceHostname")),
                        macAddress = item.findString(config.aliases("deviceMac"))?.uppercase(),
                        ipAddress = item.findString(config.aliases("deviceIp")),
                        connectionType = item.findString(config.aliases("deviceType")),
                    )
                },
                source = DataSource.ROUTER,
            )
        } ?: ConnectedDevices(source = DataSource.UNAVAILABLE)

    override suspend fun updateWiFi(ssid: String, password: String) {
        withContext(Dispatchers.IO) {
            val route = config.route("updateWifi")
                ?: throw RouterError.FeatureNotSupported("تعديل Wi-Fi", "المسار غير مضبوط في router_routes.json")
            val body = route.bodyTemplate.mapValues { (_, template) ->
                template.replace("{ssid}", ssid).replace("{password}", password)
            }
            execute(route, body).use { response -> ensureSuccess(response, "updateWifi") }
        }
    }

    override suspend fun restartRouter() {
        withContext(Dispatchers.IO) {
            val route = config.route("restartRouter")
                ?: throw RouterError.FeatureNotSupported("إعادة تشغيل الجهاز", "المسار غير مضبوط في router_routes.json")
            execute(route, route.bodyTemplate).use { response -> ensureSuccess(response, "restartRouter") }
        }
    }

    override suspend fun logout() {
        withContext(Dispatchers.IO) { sessionStore.clear() }
    }

    override suspend fun probeInternet(): Boolean {
        return withContext(Dispatchers.IO) {
            // Reaching the router admin page proves nothing about internet reachability, so the
            // probe targets a neutral endpoint instead of the device itself.
            val request = Request.Builder().url(INTERNET_PROBE_URL).head().build()
            runCatching { client.newCall(request).execute().use { it.code == 204 || it.isSuccessful } }
                .getOrDefault(false)
        }
    }

    // --- internals -----------------------------------------------------------------------

    private suspend fun <T> requiredQuery(routeKey: String, mapper: (ResponseNode) -> T): T =
        withContext(Dispatchers.IO) {
            val route = config.route(routeKey)
                ?: throw RouterError.UnsupportedFirmware("مسار غير مضبوط: $routeKey")
            execute(route).use { response ->
                ensureSuccess(response, routeKey)
                mapper(parseBody(response, routeKey))
            }
        }

    /** Returns null when the route is absent or the firmware response is unusable. */
    private suspend fun <T> optionalQuery(routeKey: String, mapper: (ResponseNode) -> T): T? =
        withContext(Dispatchers.IO) {
            val route = config.route(routeKey) ?: return@withContext null
            runCatching {
                execute(route).use { response ->
                    ensureSuccess(response, routeKey)
                    mapper(parseBody(response, routeKey))
                }
            }.getOrElse { error ->
                when (error) {
                    is RouterError.DeviceNotFound,
                    is RouterError.Timeout,
                    is RouterError.InvalidCredentials,
                    is RouterError.SessionExpired,
                    -> throw error
                    // Missing routes and unparseable bodies are the normal "firmware does not
                    // expose this" path, not a connectivity problem.
                    else -> null
                }
            }
        }

    private fun parseBody(response: Response, routeKey: String): ResponseNode {
        val text = response.body?.string().orEmpty()
        return ResponseParser.parse(text, config.encoding)
            ?: throw UnsupportedFirmwareException("استجابة فارغة أو غير قابلة للقراءة: $routeKey")
    }

    private suspend fun execute(
        route: RouterRoutesConfig.Route,
        bodyValues: Map<String, String> = emptyMap(),
    ): Response = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(baseUrl() + route.path)
        if (route.method.uppercase() == "POST") {
            if (config.encoding == "json") {
                val json = org.json.JSONObject().apply { bodyValues.forEach { (k, v) -> put(k, v) } }
                builder.post(json.toString().toRequestBody("application/json".toMediaType()))
            } else {
                builder.post(FormBody.Builder().apply { bodyValues.forEach { (k, v) -> add(k, v) } }.build())
            }
        } else {
            builder.get()
        }
        sessionStore.token()?.let { token -> builder.header("Cookie", token) }

        try {
            client.newCall(builder.build()).execute()
        } catch (e: SocketTimeoutException) {
            throw RouterError.Timeout(e.message)
        } catch (e: UnknownHostException) {
            throw RouterError.DeviceNotFound(e.message)
        } catch (e: NoRouteToHostException) {
            throw RouterError.DeviceNotFound(e.message)
        } catch (e: ConnectException) {
            throw RouterError.DeviceNotFound(e.message)
        } catch (e: IOException) {
            // Deliberately excludes the request body, which may carry credentials.
            throw RouterError.TemporaryFailure(e.javaClass.simpleName, e)
        }
    }

    private fun ensureSuccess(response: Response, routeKey: String) {
        when (response.code) {
            200, 201, 204 -> Unit
            401, 403 -> throw RouterError.InvalidCredentials()
            404 -> throw RouterError.FeatureNotSupported(routeKey, "HTTP 404")
            408, 504 -> throw RouterError.Timeout("HTTP ${response.code}")
            else -> throw RouterError.TemporaryFailure("HTTP ${response.code} ($routeKey)")
        }
    }

    private fun ensureAuthResponse(response: Response, text: String, route: RouterRoutesConfig.Route) {
        if (!response.isSuccessful) {
            throw if (response.code == 401 || response.code == 403) RouterError.InvalidCredentials()
            else RouterError.TemporaryFailure("HTTP ${response.code} (login)")
        }
        if (route.failureIndicators.any { text.contains(it, ignoreCase = true) }) {
            throw RouterError.InvalidCredentials()
        }
        if (route.successIndicators.none { text.contains(it, ignoreCase = true) }) {
            throw RouterError.InvalidCredentials()
        }
    }

    private fun baseUrl(): String {
        val host = hostProvider().trim()
            .removePrefix("http://").removePrefix("https://").trimEnd('/')
        return "http://$host"
    }

    private fun readChargingState(node: ResponseNode, percent: Int?): ChargingState {
        val flag = node.findBoolean(config.aliases("batteryCharging"), config)
        val raw = node.findString(config.aliases("batteryCharging"))?.lowercase().orEmpty()
        return when {
            raw.contains("full") -> ChargingState.FULL
            raw.contains("charg") || flag == true ->
                if (percent == 100) ChargingState.FULL else ChargingState.CHARGING
            raw.contains("discharg") || flag == false -> ChargingState.DISCHARGING
            else -> ChargingState.UNKNOWN
        }
    }

    private fun deriveSignalLevel(node: ResponseNode, percent: Int?, dbm: Int?): SignalLevel {
        node.findString(config.aliases("signalLevel"))?.let { text ->
            when {
                text.contains("excellent", true) -> return SignalLevel.EXCELLENT
                text.contains("good", true) -> return SignalLevel.GOOD
                text.contains("fair", true) || text.contains("moderate", true) -> return SignalLevel.FAIR
                text.contains("weak", true) || text.contains("poor", true) -> return SignalLevel.WEAK
                text.contains("none", true) -> return SignalLevel.NONE
            }
        }
        percent?.let { return signalLevelFromPercent(it) }
        dbm?.let { return signalLevelFromDbm(it) }
        return SignalLevel.UNKNOWN
    }

    private fun readBytes(node: ResponseNode, field: String): Long? {
        val raw = node.findString(config.aliases(field))?.trim()?.lowercase() ?: return null
        val number = Regex("([0-9]+(?:\\.[0-9]+)?)").find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: return null
        val multiplier = when {
            raw.contains("gb") || raw.contains("gig") -> 1_000_000_000.0
            raw.contains("mb") || raw.contains("meg") -> 1_000_000.0
            raw.contains("kb") -> 1_000.0
            else -> 1.0
        }
        return (number * multiplier).toLong()
    }

    private fun parseDate(raw: String): Long? {
        val patterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "dd/MM/yyyy", "yyyy/MM/dd")
        patterns.forEach { pattern ->
            runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.US).parse(raw.trim())?.time
            }.getOrNull()?.let { return it }
        }
        return null
    }

    companion object {
        private const val INTERNET_PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"
        private val TOKEN_ALIASES = listOf("token", "stok", "session", "sessionid", "key")

        fun defaultClient(config: RouterRoutesConfig): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(config.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(config.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .cookieJar(InMemoryCookieJar)
            .build()
    }
}

/** Keeps firmware session cookies in memory only; nothing is persisted to disk. */
private object InMemoryCookieJar : CookieJar {
    private val store = java.util.concurrent.ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isNotEmpty()) store[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
}

internal fun normalizeNetworkType(raw: String?): String? {
    val value = raw?.trim()?.uppercase().orEmpty()
    return when {
        value.isBlank() -> null
        value.contains("NR") || value.contains("5G") -> "5G"
        value.contains("LTE") || value.contains("4G") -> "LTE"
        value.contains("HSPA") || value.contains("UMTS") || value.contains("3G") -> "3G"
        value.contains("EDGE") || value.contains("GPRS") || value.contains("2G") -> "2G"
        else -> value
    }
}

internal fun signalLevelFromPercent(percent: Int): SignalLevel = when {
    percent <= 0 -> SignalLevel.NONE
    percent < 30 -> SignalLevel.WEAK
    percent < 60 -> SignalLevel.FAIR
    percent < 80 -> SignalLevel.GOOD
    else -> SignalLevel.EXCELLENT
}

/** RSRP-style dBm value mapped to the same buckets used for percentages. */
internal fun signalLevelFromDbm(dbm: Int): SignalLevel = when {
    dbm >= 0 -> SignalLevel.UNKNOWN
    dbm >= -85 -> SignalLevel.EXCELLENT
    dbm >= -100 -> SignalLevel.GOOD
    dbm >= -110 -> SignalLevel.FAIR
    dbm >= -120 -> SignalLevel.WEAK
    else -> SignalLevel.NONE
}