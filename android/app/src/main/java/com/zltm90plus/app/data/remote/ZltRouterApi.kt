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
import com.zltm90plus.app.diagnostics.DiagnosticExchange
import com.zltm90plus.app.diagnostics.DiagnosticRedaction
import com.zltm90plus.app.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
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
 * - Every route, command and response field name comes from [RouterRoutesConfig], never hard-coded.
 * - A field the firmware does not return becomes null with [DataSource.UNAVAILABLE]; the app
 *   never fabricates a plausible-looking number.
 * - Credentials and tokens are never logged.
 * - Only local addresses are contacted; see [PrivateHost].
 *
 * The device family ships two different web interfaces, and only one is reachable on a given
 * firmware build. [login] probes them in the order given by the route table and remembers the
 * winner for the rest of the session, because guessing wrong is indistinguishable from
 * "device not found" to the user.
 */
class ZltRouterApi(
    private val config: RouterRoutesConfig,
    sessionStore: SessionTokenStore,
    private val hostProvider: () -> String = { DEFAULT_ROUTER_HOST },
    private val clientFactory: (RouterRoutesConfig) -> OkHttpClient = ::defaultClient,
) : RouterApiProtocol {

    private val session: SessionTokenStore = sessionStore
    private val client: OkHttpClient by lazy { clientFactory(config) }

    /** Set by [login]; stays [RouterProtocol.AUTO] until a family has been confirmed. */
    @Volatile
    private var protocol: RouterProtocol = RouterProtocol.AUTO

    override suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        val attempts = config.protocolOrder.ifEmpty { DEFAULT_PROTOCOL_ORDER }
        var lastError: Throwable? = null

        for (candidate in attempts) {
            try {
                if (candidate == RouterRoutesConfig.GOFORM) {
                    loginGoform(username, password)
                    protocol = RouterProtocol.GOFORM
                } else {
                    loginLuci(username, password)
                    protocol = RouterProtocol.LUCI
                }
                return@withContext
            } catch (error: RouterError.InvalidCredentials) {
                // The interface answered and rejected the credentials, so this is the right
                // family; trying the next one would only produce a second false negative.
                throw error
            } catch (error: RouterError.DeviceNotFound) {
                // Nothing is listening at all, so no other family will do better.
                throw error
            } catch (error: Throwable) {
                lastError = error
            }
        }

        throw when (val error = lastError) {
            null -> RouterError.UnsupportedFirmware("لا توجد واجهة مضبوطة في router_routes.json")
            is RouterError -> error
            else -> RouterError.TemporaryFailure(error.javaClass.simpleName, error)
        }
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
                uptimeMinutes = readUptimeMinutes(node),
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
                connectionUptimeMinutes = readUptimeMinutes(node),
                source = DataSource.ROUTER,
            )
        } ?: NetworkStatus(source = DataSource.UNAVAILABLE)

    /**
     * Plan counters are rarely exposed by ZLT M90 Plus firmware. When the interface is missing or
     * returns nothing usable this reports [DataSource.UNAVAILABLE] rather than zero usage.
     */
    override suspend fun fetchDataUsage(): DataPlanStatus =
        optionalQuery("dataUsage") { node ->
            val total = readPlanTotalBytes(node)
            val used = readPlanUsedBytes(node)
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
            if (protocol == RouterProtocol.GOFORM) {
                val write = config.goform.writes["updateWifi"]
                    ?: throw RouterError.FeatureNotSupported("تعديل Wi-Fi", "العملية غير مضبوطة في router_routes.json")
                val values = write.fields.mapValues { (_, template) ->
                    template.replace("{ssid}", ssid).replace("{password}", password)
                }
                setGoform(write.goformId, values, "updateWifi")
                return@withContext
            }
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
            if (protocol == RouterProtocol.GOFORM) {
                val write = config.goform.writes["restartRouter"]
                    ?: throw RouterError.FeatureNotSupported("إعادة تشغيل الجهاز", "العملية غير مضبوطة في router_routes.json")
                setGoform(write.goformId, write.fields, "restartRouter")
                return@withContext
            }
            val route = config.route("restartRouter")
                ?: throw RouterError.FeatureNotSupported("إعادة تشغيل الجهاز", "المسار غير مضبوط في router_routes.json")
            execute(route, route.bodyTemplate).use { response -> ensureSuccess(response, "restartRouter") }
        }
    }

    override suspend fun logout() {
        withContext(Dispatchers.IO) {
            runCatching {
                if (protocol == RouterProtocol.GOFORM) {
                    setGoform(config.goform.logoutGoformId, emptyMap(), "logout")
                } else {
                    config.route("logout")?.let { execute(it).use { response -> ensureSuccess(response, "logout") } }
                }
            }
            session.clear()
            protocol = RouterProtocol.AUTO
        }
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

    /**
     * Unauthenticated reachability check used by discovery. Any HTTP answer means "a web
     * interface is serving here"; only a transport failure rules the address out.
     */
    override suspend fun probeWebInterface(): Boolean = withContext(Dispatchers.IO) {
        val base = runCatching { RouterUrl.base(hostProvider()) }.getOrNull() ?: return@withContext false
        val request = Request.Builder()
            .url("$base/")
            .applyCommonHeaders(base)
            .get()
            .build()
        runCatching { client.newCall(request).execute().use { true } }.getOrDefault(false)
    }

    // --- login per interface family -------------------------------------------------------

    private fun loginGoform(username: String, password: String) {
        val goform = config.goform
        val response = postForm(
            path = goform.setPath,
            values = mapOf(
                // The firmware dispatches on goformId; a login body without it is ignored.
                "isTest" to "false",
                "goformId" to goform.loginGoformId,
                goform.loginUserField to username.ifBlank { goform.username },
                goform.loginPasswordField to encodePassword(password, goform.loginPasswordEncoding),
            ),
        )
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw if (it.code == 401 || it.code == 403) {
                    RouterError.InvalidCredentials()
                } else {
                    RouterError.TemporaryFailure("HTTP ${it.code} (login)")
                }
            }

            val node = ResponseParser.parse(text, config.encoding)
                ?: throw RouterError.UnsupportedFirmware("استجابة تسجيل الدخول غير قابلة للقراءة")

            val result = node.findString(RESULT_ALIASES)?.trim()
            when {
                result != null && result in goform.wrongPasswordResultCodes ->
                    throw RouterError.InvalidCredentials()
                result != null && result !in goform.successResultCodes ->
                    throw RouterError.InvalidCredentials()
                result == null && looksLikeLoginPage(text) ->
                    throw RouterError.UnsupportedFirmware("الاستجابة صفحة HTML وليست واجهة goform")
            }

            // The session can arrive two ways: the firmware cookie, or an explicit token in the
            // body. The cookie jar already retains the former, so only a bare token is stored.
            ResponseParser.extractSessionCookie(it.headers.toMultimap())?.let(session::saveToken)
                ?: node.findString(clientSessionAliases())?.let(session::saveToken)

            if (!goformSessionIsValid()) throw RouterError.InvalidCredentials()
        }
    }

    /**
     * The goform login expects the password Base64-encoded, not in clear text. Sending it raw is
     * answered with the wrong-password result code, which is what made a correct password look
     * rejected. `plain` is honoured for a build that does not want the encoding.
     */
    private fun encodePassword(password: String, encoding: String): String =
        if (encoding.equals(RouterRoutesConfig.PASSWORD_ENCODING_PLAIN, ignoreCase = true)) {
            password
        } else {
            java.util.Base64.getEncoder().encodeToString(password.toByteArray(Charsets.UTF_8))
        }

    private fun loginLuci(username: String, password: String) {
        val route = config.route("login")
            ?: throw RouterError.UnsupportedFirmware("لم يُضبط مسار تسجيل الدخول في router_routes.json")
        val body = route.bodyTemplate.mapValues { (_, template) ->
            template.replace("{username}", username).replace("{password}", password)
        }
        execute(route, body).use { response ->
            val text = response.body?.string().orEmpty()
            ensureAuthResponse(response, text, route)
            val node = ResponseParser.parse(text, config.encoding)
            val cookieToken = ResponseParser.extractSessionCookie(response.headers.toMultimap())
            val bodyToken = node?.findString(TOKEN_ALIASES)
            (cookieToken ?: bodyToken)?.let(session::saveToken)
            // A cookie-only session needs no explicit token: the CookieJar retains it.
        }
    }

    /** `loginfo` is the firmware's own "am I logged in" flag, so it is the honest confirmation. */
    private fun goformSessionIsValid(): Boolean = runCatching {
        getGoform(config.goform.sessionCheckCmd).use { response ->
            if (!response.isSuccessful) return false
            val node = ResponseParser.parse(response.body?.string().orEmpty(), config.encoding)
                ?: return false
            val value = node.findString(listOf(config.goform.sessionCheckCmd)) ?: return false
            value.equals(config.goform.sessionOkValue, ignoreCase = true)
        }
    }.getOrDefault(false)

    // --- internals ------------------------------------------------------------------------

    private suspend fun <T> requiredQuery(routeKey: String, mapper: (ResponseNode) -> T): T =
        withContext(Dispatchers.IO) {
            val node = fetchDataset(routeKey)
                ?: throw RouterError.UnsupportedFirmware("لم تُضبط بيانات $routeKey في router_routes.json")
            mapper(node)
        }

    /** Returns null when the interface is absent or the firmware response is unusable. */
    private suspend fun <T> optionalQuery(routeKey: String, mapper: (ResponseNode) -> T): T? =
        withContext(Dispatchers.IO) {
            runCatching { fetchDataset(routeKey)?.let(mapper) }
                .getOrElse { error ->
                    when (error) {
                        is RouterError.DeviceNotFound,
                        is RouterError.Timeout,
                        is RouterError.InvalidCredentials,
                        is RouterError.SessionExpired,
                        is RouterError.InvalidHost,
                        -> throw error
                        // Missing endpoints and unparseable bodies are the normal "firmware does
                        // not expose this" path, not a connectivity problem.
                        else -> null
                    }
                }
        }

    /**
     * Reads one dataset over whichever interface answered at login. Returns null when the
     * interface has no definition for it, which callers translate into "unavailable".
     */
    private fun fetchDataset(routeKey: String): ResponseNode? = when (protocol) {
        RouterProtocol.GOFORM -> {
            val cmd = config.goform.commands[routeKey] ?: return null
            fetchGoformDataset(routeKey, cmd)
        }
        RouterProtocol.LUCI -> {
            val route = config.route(routeKey) ?: return null
            execute(route).use { response ->
                ensureSuccess(response, routeKey)
                parseBody(response, routeKey)
            }
        }
        RouterProtocol.AUTO -> throw RouterError.SessionExpired()
    }

    private fun fetchGoformDataset(routeKey: String, cmd: String): ResponseNode {
        getGoform(cmd).use { response ->
            ensureSuccess(response, routeKey)
            val text = response.body?.string().orEmpty()
            val node = ResponseParser.parse(text, config.encoding)
                ?: throw UnsupportedFirmwareException("استجابة فارغة أو غير قابلة للقراءة: $routeKey")
            if (goformReportsLoggedOut(node, text)) throw RouterError.SessionExpired()
            return node
        }
    }

    private fun goformReportsLoggedOut(node: ResponseNode, text: String): Boolean {
        val flag = node.findString(listOf(config.goform.sessionCheckCmd))
        if (flag != null && !flag.equals(config.goform.sessionOkValue, ignoreCase = true)) return true
        val result = node.findString(RESULT_ALIASES)?.trim() ?: return false
        return result.equals("error", ignoreCase = true) && looksLikeLoginPage(text)
    }

    /**
     * goform reads are `cmd`-driven. `multi_data=1` is what makes the firmware answer with a JSON
     * object instead of a bare value, so it is always sent.
     */
    private fun getGoform(cmd: String): Response {
        val base = RouterUrl.base(hostProvider())
        val url = RouterUrl.build(
            baseUrl = base,
            path = config.goform.getPath,
            params = mapOf("isTest" to "false", "multi_data" to "1", "cmd" to cmd),
        )
        return executeRequest(Request.Builder().url(url).applyCommonHeaders(base).get().build())
    }

    private fun setGoform(goformId: String, values: Map<String, String>, routeKey: String) {
        postForm(
            path = config.goform.setPath,
            values = mapOf("isTest" to "false", "goformId" to goformId) + values,
        ).use { response ->
            ensureSuccess(response, routeKey)
            val node = ResponseParser.parse(response.body?.string().orEmpty(), config.encoding)
            val result = node?.findString(RESULT_ALIASES)?.trim()
            val accepted = config.goform.successResultCodes + "success"
            if (result != null && result !in accepted) {
                throw RouterError.TemporaryFailure("نتيجة الجهاز: $result ($routeKey)")
            }
        }
    }

    private fun postForm(path: String, values: Map<String, String>): Response {
        val base = RouterUrl.base(hostProvider())
        val body: RequestBody = FormBody.Builder()
            .apply { values.forEach { (key, value) -> add(key, value) } }
            .build()
        return executeRequest(
            Request.Builder()
                .url(RouterUrl.build(base, path))
                .applyCommonHeaders(base)
                .post(body)
                .build(),
        )
    }

    private fun Request.Builder.applyCommonHeaders(base: String): Request.Builder {
        // The firmware checks the referer on form posts and rejects requests that omit it.
        header("Referer", "$base/index.html")
        header("User-Agent", USER_AGENT)
        session.token()?.let { token ->
            // A bare value is a token; a string containing '=' is already a cookie pair.
            header("Cookie", if (token.contains('=')) token else "stok=$token")
        }
        return this
    }

    private fun parseBody(response: Response, routeKey: String): ResponseNode {
        val text = response.body?.string().orEmpty()
        return ResponseParser.parse(text, config.encoding)
            ?: throw UnsupportedFirmwareException("استجابة فارغة أو غير قابلة للقراءة: $routeKey")
    }

    /**
     * Blocking on purpose: every caller already runs on [Dispatchers.IO], so wrapping again here
     * would only add a nested hop without changing where the I/O happens.
     */
    private fun execute(
        route: RouterRoutesConfig.Route,
        bodyValues: Map<String, String> = emptyMap(),
    ): Response {
        val base = RouterUrl.base(hostProvider())
        val builder = Request.Builder().url(RouterUrl.build(base, route.path))
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
        builder.applyCommonHeaders(base)
        return executeRequest(builder.build())
    }

    private fun executeRequest(request: Request): Response {
        // Only runs when a diagnostics build installed a sink; a no-op otherwise.
        if (!Diagnostics.enabled) return executeRequestInternal(request)
        val startedAt = System.currentTimeMillis()
        val url = request.url.toString()
        val body = runCatching { request.body?.let(::bodyText) }.getOrNull()
        return try {
            val response = executeRequestInternal(request)
            val text = runCatching { response.peekBody(PEEK_LIMIT)?.string() }.getOrNull()
            Diagnostics.record(
                DiagnosticExchange(
                    timestampMillis = startedAt,
                    url = url,
                    method = request.method,
                    requestBody = body?.let(DiagnosticRedaction::redact),
                    statusCode = response.code,
                    responseBody = text?.let(DiagnosticRedaction::redact),
                    error = null,
                    durationMillis = System.currentTimeMillis() - startedAt,
                ),
            )
            response
        } catch (e: Throwable) {
            Diagnostics.record(
                DiagnosticExchange(
                    timestampMillis = startedAt,
                    url = url,
                    method = request.method,
                    requestBody = body?.let(DiagnosticRedaction::redact),
                    statusCode = null,
                    responseBody = null,
                    error = describeFailure(e),
                    durationMillis = System.currentTimeMillis() - startedAt,
                ),
            )
            throw e
        }
    }

    /**
     * Names the failure in the terms needed to act on it. The transport maps socket errors onto
     * [com.zltm90plus.app.data.remote.RouterError] subclasses, whose message is Arabic prose for
     * the user; the underlying OS error is what identifies the cause, so both are kept.
     */
    private fun describeFailure(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val parts = buildList {
            add("${error.javaClass.simpleName}: ${error.message}")
            if (error is com.zltm90plus.app.data.remote.RouterError) {
                error.technicalDetail?.let { add("detail=$it") }
            }
            if (root !== error) add("root=${root.javaClass.simpleName}: ${root.message}")
        }
        return DiagnosticRedaction.redact(parts.joinToString(" | "))
    }

    /**
     * Reads a request body without consuming it. `peekBody` is not available on the request side,
     * so the buffer is copied back once read.
     */
    private fun bodyText(body: RequestBody): String {
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun executeRequestInternal(request: Request): Response =
        try {
            client.newCall(request).execute()
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

    private fun clientSessionAliases(): List<String> =
        config.goform.sessionTokenAliases.ifEmpty { TOKEN_ALIASES }

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

    /**
     * Uptime arrives either as minutes or as seconds depending on the firmware field, so both
     * spellings are read and normalised. The reference device reports `realtime_time=636` for an
     * uptime of 10 minutes 36 seconds, i.e. seconds.
     */
    private fun readUptimeMinutes(node: ResponseNode): Long? {
        node.findInt(config.aliases("uptimeMinutes"))?.let { return it.toLong() }
        return node.findInt(config.aliases("uptimeSeconds"))?.let { (it / 60).toLong() }
    }

    /**
     * Traffic counters are reported as plain byte totals by this firmware family, so an absent
     * unit suffix means bytes. Anything with an explicit unit is converted before use.
     */
    private fun readBytes(node: ResponseNode, field: String): Long? {
        val raw = node.findString(config.aliases(field))?.trim()?.lowercase() ?: return null
        val number = Regex("([0-9]+(?:\\.[0-9]+)?)").find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: return null
        return (number * (unitMultiplier(raw) ?: 1.0)).toLong()
    }

    /**
     * The quota field is a bare number whose unit lives in a separate firmware field, so a
     * missing unit makes the value ambiguous. Guessing would produce a plausible but wrong
     * figure, which is worse than reporting the plan as unavailable.
     */
    private fun readPlanTotalBytes(node: ResponseNode): Long? {
        val raw = node.findString(config.aliases("planTotalBytes"))?.trim()?.lowercase() ?: return null
        val number = Regex("([0-9]+(?:\\.[0-9]+)?)").find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: return null
        val declaredUnit = node.findString(config.aliases("planTotalUnit"))
        val multiplier = unitMultiplier(raw) ?: unitMultiplier(declaredUnit ?: "") ?: return null
        return (number * multiplier).toLong()
    }

    /** Bytes-per-unit implied by an explicit suffix or a firmware unit field. */
    private fun unitMultiplier(rawInput: String): Double? {
        val raw = rawInput.trim().lowercase()
        return when {
            raw.isEmpty() -> null
            raw.contains("gb") || raw.contains("gig") -> 1_000_000_000.0
            raw.contains("mb") || raw.contains("meg") -> 1_000_000.0
            raw.contains("kb") -> 1_000.0
            // A plain number of bytes is only meaningful when the field itself says bytes.
            raw == "b" || raw == "bytes" -> 1.0
            else -> null
        }
    }

    /**
     * goform reports traffic as two separate counters (sent and received), so the used total is
     * their sum. When only one side is present the total stays unknown rather than half-counted.
     */
    private fun readPlanUsedBytes(node: ResponseNode): Long? {
        readBytes(node, "planUsedBytes")?.let { return it }
        val sent = readBytes(node, "planUsedTxBytes") ?: return null
        val received = readBytes(node, "planUsedRxBytes") ?: return null
        return sent + received
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
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) ZLTM90Plus"
        /** Enough of a response to identify the firmware's answer without holding it all. */
        private const val PEEK_LIMIT = 64L * 1024L
        private val TOKEN_ALIASES = listOf("token", "stok", "session", "sessionid", "key")
        private val RESULT_ALIASES = listOf("result")

        private val DEFAULT_PROTOCOL_ORDER =
            listOf(RouterRoutesConfig.GOFORM, RouterRoutesConfig.LUCI)

        /** An HTML payload is the login page, not an interface response. */
        internal fun looksLikeLoginPage(text: String): Boolean {
            val trimmed = text.trimStart()
            if (!trimmed.startsWith("<")) return false
            return trimmed.contains("<html", ignoreCase = true) ||
                trimmed.contains("login", ignoreCase = true)
        }

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
internal object InMemoryCookieJar : CookieJar {
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
