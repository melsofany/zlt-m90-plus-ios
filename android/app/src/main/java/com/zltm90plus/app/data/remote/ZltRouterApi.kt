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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    private val schemeProvider: () -> String = { "http" },
    private val clientFactory: (RouterRoutesConfig) -> OkHttpClient = ::defaultClient,
) : RouterApiProtocol {

    private val session: SessionTokenStore = sessionStore
    private val client: OkHttpClient by lazy { clientFactory(config) }

    /**
     * Used only for [probeInternet], the one request that goes outside the LAN. Kept separate
     * from [client] so the router's self-signed certificate exemption cannot apply to a public
     * host.
     */
    private val internetClient: OkHttpClient by lazy { platformTrustClient() }

    /**
     * The one place a request URL is built. Validation happens here, so no other call site can
     * skip the private-host check or pick a scheme of its own.
     */
    private fun baseUrl(): String = baseFor(null)

    /** The same URL builder, pinned to one scheme for the duration of a login attempt. */
    private fun baseFor(scheme: String?): String =
        RouterUrl.base(authority(), scheme ?: resolvedScheme ?: schemeProvider())

    /**
     * The scheme that actually answered, learned by [login]. Null until a login has succeeded.
     *
     * Discovery only ever sees `GET /`, and a device may redirect that one path to HTTPS while
     * still serving its whole API over plain HTTP. Standing on that inference cost a real login:
     * the app was pointed at https, where this firmware answers every API path with 404. So the
     * probe's scheme is a starting preference, and the scheme that logs in is the one that counts.
     */
    @Volatile
    private var resolvedScheme: String? = null

    /**
     * The address the device named in a redirect, once one has been followed.
     *
     * The configured host is not always the one that serves the interface: this device answers
     * `192.168.8.1` with a redirect to the port it actually listens on. Once that address is
     * known, every later request is built against it, so a follow-up read does not have to
     * rediscover it.
     */
    @Volatile
    private var redirectedAuthority: String? = null

    /** The device's own address, if it has named one, ahead of the configured host. */
    private fun authority(): String = redirectedAuthority ?: hostProvider()

    /**
     * Where the device's login page says it posts a login, learned at login time.
     *
     * Null until the page has been read. Purely an override: when it is null the configured path is
     * used, so a device whose page this does not understand behaves exactly as before.
     */
    @Volatile
    private var loginEndpoint: RouterLoginPage.Endpoint? = null

    /**
     * Endpoints the device's own files named that this build cannot speak to.
     *
     * The field log showed `/cgi-bin/http.cgi` here. Kept so the failure can name what the device
     * actually publishes instead of only reporting that the login did not work.
     */
    @Volatile
    private var foreignEndpoints: List<String> = emptyList()

    /** The real login path, taken from the device's page when it named one. */
    private fun setCmdPath(): String = loginEndpoint?.path ?: config.goform.setPath

    /**
     * The read path, matched to the learned login path so the two stay on the same interface.
     *
     * The two endpoints are siblings under the same prefix on this firmware, so a device that
     * serves its login somewhere else serves its reads alongside it. Only the `set`/`get` part is
     * swapped, because that is the one word that differs between them.
     */
    private fun getCmdPath(): String {
        val learned = loginEndpoint?.path ?: return config.goform.getPath
        if (!learned.contains("set_cmd_process")) return config.goform.getPath
        return learned.replace("set_cmd_process", "get_cmd_process")
    }

    /** The preferred scheme first, then the other, so one wrong guess cannot block a connection. */
    private fun schemeCandidates(): List<String> =
        if (schemeProvider().equals("https", ignoreCase = true)) listOf("https", "http")
        else listOf("http", "https")

    /** Set by [login]; stays [RouterProtocol.AUTO] until a family has been confirmed. */
    @Volatile
    private var protocol: RouterProtocol = RouterProtocol.AUTO

    override suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        val interfaces = config.protocolOrder.ifEmpty { DEFAULT_PROTOCOL_ORDER }
        var lastError: Throwable? = null
        var followedRedirect = false
        // Guards the page read so a device that rejects every path cannot be asked for its page
        // once per scheme and interface combination.
        var endpointLearned = false

        // Outer loop over schemes, inner over interface families: a device serving its admin only
        // over HTTP would otherwise be missed whenever discovery happened to see a redirect.
        for (scheme in schemeCandidates()) {
            for (candidate in interfaces) {
                try {
                    if (candidate == RouterRoutesConfig.GOFORM) {
                        loginGoform(username, password, scheme)
                        protocol = RouterProtocol.GOFORM
                    } else {
                        loginLuci(username, password, scheme)
                        protocol = RouterProtocol.LUCI
                    }
                    resolvedScheme = scheme
                    return@withContext
                } catch (error: RouterError.InvalidCredentials) {
                    // The interface answered and rejected the credentials, so this is the right
                    // family and the right scheme; trying the next one would only produce a second
                    // false negative.
                    throw error
                } catch (error: RouterError.DeviceNotFound) {
                    // Nothing is listening at all, so no other scheme or family will do better.
                    // Kept separate from the branch below so an absent device costs one attempt
                    // rather than four.
                    throw error
                } catch (error: RouterError.Redirected) {
                    // The device named the address that serves this interface, so the request is
                    // repeated there. Only once: a device that keeps redirecting would otherwise
                    // be followed until the app gave up, and the second answer is the real one.
                    if (followedRedirect) {
                        if (error.rank() >= (lastError?.rank() ?: Int.MIN_VALUE)) lastError = error
                        continue
                    }
                    followedRedirect = true
                    followRedirect(error.authority)
                    // The redirect is the first sign that the configured path is a guess about the
                    // firmware, so the device's own login page is asked where it really posts.
                    endpointLearned = true
                    discoverLoginEndpoint(resolvedScheme ?: scheme)?.let { loginEndpoint = it }
                    try {
                        if (candidate == RouterRoutesConfig.GOFORM) {
                            loginGoform(username, password, resolvedScheme ?: error.scheme)
                            protocol = RouterProtocol.GOFORM
                        } else {
                            loginLuci(username, password, resolvedScheme ?: error.scheme)
                            protocol = RouterProtocol.LUCI
                        }
                        return@withContext
                    } catch (retry: RouterError.InvalidCredentials) {
                        // The redirected address answered and rejected the credentials, which is a
                        // definite answer rather than another place to try.
                        throw retry
                    } catch (retry: Throwable) {
                        if (retry.rank() >= (lastError?.rank() ?: Int.MIN_VALUE)) lastError = retry
                    }
                } catch (error: Throwable) {
                    // A 404 on the configured path means the path is a guess about the firmware, so
                    // the device's own login page is asked where it really posts — once, and only
                    // ever when the configured path could not be reached at all.
                    if (!endpointLearned && error.isPathRejected()) {
                        endpointLearned = true
                        if (discoverLoginEndpoint(scheme)?.let { loginEndpoint = it } != null) {
                            try {
                                if (candidate == RouterRoutesConfig.GOFORM) {
                                    loginGoform(username, password, scheme)
                                    protocol = RouterProtocol.GOFORM
                                } else {
                                    loginLuci(username, password, scheme)
                                    protocol = RouterProtocol.LUCI
                                }
                                resolvedScheme = scheme
                                return@withContext
                            } catch (retry: RouterError.InvalidCredentials) {
                                throw retry
                            } catch (retry: Throwable) {
                                if (retry.rank() >= (lastError?.rank() ?: Int.MIN_VALUE)) lastError = retry
                            }
                        }
                    }
                    // Anything else — a 404 on this scheme, a timeout, an unreadable answer — is
                    // worth retrying on the other scheme, so it is kept and the search continues.
                    // The most informative failure is the one reported, not merely the last: a
                    // device that answered with something unreadable on http is a different
                    // situation from the connect timeout https produces when it is not listening,
                    // and reporting the timeout would hide the reply that actually happened.
                    if (error.rank() >= (lastError?.rank() ?: Int.MIN_VALUE)) lastError = error
                }
            }
        }

        // Nothing answered in a protocol this build speaks, but the device did publish endpoints.
        // Naming them is the actionable form of this failure: it says which interface the firmware
        // actually serves, which is what a fix has to be written against.
        if (foreignEndpoints.isNotEmpty() && lastError !is RouterError.InvalidCredentials) {
            throw RouterError.InterfaceNotSupported(foreignEndpoints.first())
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
            //
            // This is the one request that leaves the LAN, so it uses its own client built with
            // the platform's trust anchors rather than [client]. [client] accepts the router's
            // self-signed certificate, which is only defensible for a device on the local
            // network; reusing it here would make the app accept any certificate for a public
            // host too.
            val request = Request.Builder().url(INTERNET_PROBE_URL).head().build()
            runCatching {
                internetClient.newCall(request).execute().use { it.code == 204 || it.isSuccessful }
            }.getOrDefault(false)
        }
    }

    /**
     * Unauthenticated reachability check used by discovery. Any HTTP answer means "a web
     * interface is serving here"; only a transport failure rules the address out.
     */
    override suspend fun probeWebInterface(): Boolean = withContext(Dispatchers.IO) {
        val base = runCatching { baseUrl() }.getOrNull() ?: return@withContext false
        val request = Request.Builder()
            .url("$base/")
            .applyCommonHeaders(base)
            .get()
            .build()
        runCatching { client.newCall(request).execute().use { true } }.getOrDefault(false)
    }

    // --- login per interface family -------------------------------------------------------

    private fun loginGoform(username: String, password: String, scheme: String) {
        val goform = config.goform
        val endpoint = loginEndpoint
        val response = postForm(
            // The device's own login page decides the path and the field names where it stated
            // them. The configured values remain the fallback, because a page this does not
            // understand must not change how the request is built.
            path = endpoint?.path ?: goform.setPath,
            values = mapOf(
                // The firmware dispatches on goformId; a login body without it is ignored.
                "isTest" to "false",
                "goformId" to goform.loginGoformId,
                (endpoint?.userField ?: goform.loginUserField) to username.ifBlank { goform.username },
                // The encoding is the configured one, always. Reading it from the login page made a
                // correct password look wrong: the page mentions `base64` for other reasons, and
                // that flipped the encoding to plain text, which this firmware rejects with its
                // wrong-password code. The encoding is a property of the firmware, not of the page.
                (endpoint?.passwordField ?: goform.loginPasswordField) to
                    encodePassword(password, goform.loginPasswordEncoding),
            ),
            scheme = scheme,
        )
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw when {
                    it.code == 401 || it.code == 403 -> RouterError.InvalidCredentials()
                    // A redirect here means the write endpoint is not at this scheme or path.
                    // Reported with the target so the log shows where the device pointed.
                    it.code in 300..399 -> RouterError.DeviceResponseUnreadable(
                        "HTTP ${it.code} (login) → ${it.header("Location") ?: "بلا عنوان تحويل"}",
                    )
                    else -> RouterError.TemporaryFailure("HTTP ${it.code} (login)")
                }
            }

            val node = ResponseParser.parse(text, config.encoding)
                ?: throw RouterError.UnsupportedFirmware("استجابة تسجيل الدخول غير قابلة للقراءة")

            val result = node.findString(RESULT_ALIASES)?.trim()

            // The session can arrive two ways: the firmware cookie, or an explicit token in the
            // body. The cookie jar already retains the former, so only a bare token is stored.
            val handedSession = ResponseParser.extractSessionCookie(it.headers.toMultimap())
                ?: node.findString(clientSessionAliases())
            handedSession?.let(session::saveToken)

            val endpoint = loginEndpoint?.path ?: goform.setPath
            when {
                // The device named these codes itself, so they are its verdict on the credentials.
                result != null && result in goform.wrongPasswordResultCodes ->
                    throw RouterError.InvalidCredentials()
                result != null && result !in goform.successResultCodes ->
                    throw RouterError.InvalidCredentials()
                result == null && looksLikeLoginPage(text) ->
                    throw RouterError.UnsupportedFirmware("الاستجابة صفحة HTML وليست واجهة goform")
                // A session handed over is proof on its own; some builds answer with only a token.
                result == null && handedSession != null -> Unit
                // An envelope goform never uses, carrying the device's own refusal. This is the
                // field log's `{"success":false,"cmd":-1,"message":"ROOT IS NULL."}`: a dispatcher
                // with no `goformId` concept answered, so the password was never judged. Reporting
                // it as a wrong password is what sent the user after a credential problem that did
                // not exist, so it is named for what it is instead.
                result == null && node.hasAnyField(FOREIGN_ENVELOPE_KEYS) ->
                    throw RouterError.InterfaceNotSupported(
                        endpoint = endpoint,
                        deviceSaid = node.findString(FOREIGN_MESSAGE_ALIASES),
                    )
                // No verdict of any kind: an answer this build cannot interpret. Still not a
                // statement about the password, which is the invariant that matters here.
                result == null -> throw RouterError.DeviceResponseUnreadable(
                    "استجابة تسجيل الدخول بلا حقل result: " +
                        node.objectMap.keys.joinToString(", "),
                )
            }

            // The session check only ever subtracts from what the device already said. `result`
            // carrying an accepted code is the device stating the login succeeded; `loginfo` is
            // corroboration. Only the device explicitly answering "not logged in" rejects; a check
            // that could not run (404, unreadable body) leaves that statement standing rather than
            // replacing it with an accusation about the password.
            if (goformSessionCheck(scheme) == SessionCheck.REJECTED) {
                throw RouterError.InvalidCredentials()
            }
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

    private fun loginLuci(username: String, password: String, scheme: String) {
        val route = config.route("login")
            ?: throw RouterError.UnsupportedFirmware("لم يُضبط مسار تسجيل الدخول في router_routes.json")
        val body = route.bodyTemplate.mapValues { (_, template) ->
            template.replace("{username}", username).replace("{password}", password)
        }
        execute(route, body, scheme).use { response ->
            val text = response.body?.string().orEmpty()
            ensureAuthResponse(response, text, route)
            val node = ResponseParser.parse(text, config.encoding)
            val cookieToken = ResponseParser.extractSessionCookie(response.headers.toMultimap())
            val bodyToken = node?.findString(TOKEN_ALIASES)
            (cookieToken ?: bodyToken)?.let(session::saveToken)
            // A cookie-only session needs no explicit token: the CookieJar retains it.
        }
    }

    /**
     * What the firmware's own "am I logged in" flag (`loginfo`) said about the session.
     *
     * Three outcomes rather than a boolean, because the check is corroboration and a check that
     * could not be performed is not a rejection. Collapsing [UNKNOWN] into "not logged in" is what
     * turned a 404 on the session endpoint into "اسم المستخدم أو كلمة المرور غير صحيحة" — an
     * accusation about the password from a request that never mentioned it.
     */
    private enum class SessionCheck { CONFIRMED, REJECTED, UNKNOWN }

    /** `loginfo` is the firmware's own "am I logged in" flag, so it is the honest confirmation. */
    private fun goformSessionCheck(scheme: String): SessionCheck = runCatching {
        getGoform(config.goform.sessionCheckCmd, scheme).use { response ->
            // The endpoint is absent on this build: no verdict is available, and none is invented.
            if (!response.isSuccessful) return SessionCheck.UNKNOWN
            val node = ResponseParser.parse(response.body?.string().orEmpty(), config.encoding)
                ?: return SessionCheck.UNKNOWN
            val value = node.findString(listOf(config.goform.sessionCheckCmd))
                ?: return SessionCheck.UNKNOWN
            if (value.equals(config.goform.sessionOkValue, ignoreCase = true)) {
                SessionCheck.CONFIRMED
            } else {
                SessionCheck.REJECTED
            }
        }
    }.getOrDefault(SessionCheck.UNKNOWN)

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
    private fun getGoform(cmd: String, scheme: String? = null): Response {
        val base = baseFor(scheme)
        val url = RouterUrl.build(
            baseUrl = base,
            path = getCmdPath(),
            params = mapOf("isTest" to "false", "multi_data" to "1", "cmd" to cmd),
        )
        return executeRequest(Request.Builder().url(url).applyCommonHeaders(base).get().build())
    }

    private fun setGoform(goformId: String, values: Map<String, String>, routeKey: String) {
        postForm(
            path = setCmdPath(),
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

    private fun postForm(path: String, values: Map<String, String>, scheme: String? = null): Response {
        val base = baseFor(scheme)
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
        // The device's GoAhead server has been observed answering a reused connection with a
        // status line that begins with the request path instead of the HTTP version. Asking for a
        // fresh connection each time avoids that desync, and this interface is a handful of
        // requests per refresh on a LAN, so the handshake cost is irrelevant next to a reply the
        // client cannot parse.
        header("Connection", "close")
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
        scheme: String? = null,
    ): Response {
        val base = baseFor(scheme)
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
                    redirectLocation = response.header("Location")?.let(DiagnosticRedaction::redact),
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
            // The address a redirect named, so the log shows where the device pointed even when
            // following it did not work. Without this the entry read only as "unreadable reply",
            // which hid the one piece of information the device had volunteered.
            if (error is com.zltm90plus.app.data.remote.RouterError.Redirected) {
                add("redirect=${error.scheme}://${error.authority}")
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
        } catch (e: java.net.ProtocolException) {
            // The device answered with a status line OkHttp will not parse. The reply is read a
            // second time over a plain socket, because that is the only way to recover the
            // Location header OkHttp's validation destroyed — and the Location is what says where
            // the interface really is. Ignoring it is why a device that was plainly answering
            // looked unreachable.
            throw malformedReply(request.url.encodedPath, e)
        } catch (e: IOException) {
            // OkHttp wraps the same malformed reply, so the cause chain is checked before the
            // error is dismissed as transient.
            if (e.hasProtocolCause()) throw malformedReply(request.url.encodedPath, e)
            // Deliberately excludes the request body, which may carry credentials.
            throw RouterError.TemporaryFailure(e.javaClass.simpleName, e)
        }

    /**
     * Turns a malformed reply into either "the device named another address" or "the reply is
     * unusable", by reading the reply directly. Never throws.
     */
    private fun malformedReply(path: String, cause: Throwable): RouterError {
        val target = RouterRedirect.probeHttp(baseAuthority(), path)
        return if (target != null) {
            RouterError.Redirected(target.scheme, target.authority, cause.message)
        } else {
            RouterError.DeviceResponseUnreadable(cause.message)
        }
    }

    /** Host (and port) of the configured device, used to re-read a refused reply. */
    private fun baseAuthority(): String =
        RouterUrl.base(authority(), resolvedScheme ?: schemeProvider()).removePrefix("http://").removePrefix("https://")

    /**
     * The address a redirect pointed at, rebuilt from the scheme to use for the next request.
     *
     * The device names a port in its redirect, and that port belongs to the scheme it chose: taking
     * the port without the scheme produced `http://192.168.8.1:443`, which nothing serves. That was
     * a bug in the redirect handling, visible in the field log as attempt 3.
     */
    private fun followRedirect(target: String) {
        val parsed = "http://${target.removePrefix("http://").removePrefix("https://")}"
            .toHttpUrlOrNull() ?: return
        val https = parsed.port == 443
        resolvedScheme = if (https) "https" else "http"
        redirectedAuthority = if ((https && parsed.port == 443) || (!https && parsed.port == 80)) {
            // The port is the scheme's default, so it adds nothing and is dropped for legibility.
            parsed.host
        } else {
            "${parsed.host}:${parsed.port}"
        }
    }

    /**
     * Asks the device's own login page where it accepts a login.
     *
     * A GET, so it needs no credentials, and it is the only source that cannot be wrong: the page
     * has to name the endpoint it posts to. The configured path is a guess about the firmware, and
     * this firmware answers it with 404.
     *
     * @return the endpoint, or null when the page could not be read; a caller that gets null keeps
     *     the configured defaults rather than swapping one guess for another.
     */
    private fun discoverLoginEndpoint(scheme: String): RouterLoginPage.Endpoint? {
        val base = baseFor(scheme)
        val html = fetchText(base, "/") ?: return null

        // Kept in full. It is small, it is the only place the field names the page states can be
        // seen, and it tells the two failures apart: a page that names the endpoint and one that
        // does not look identical from the outside.
        Diagnostics.recordProbe(
            url = RouterUrl.build(base, "/").toString(),
            reachable = true,
            detail = null,
            durationMillis = 0,
            page = html,
        )

        val defaults = RouterLoginPage.Defaults(
            userField = config.goform.loginUserField,
            passwordField = config.goform.loginPasswordField,
        )
        RouterLoginPage.parse(html, defaults)?.let { endpoint ->
            if (isGoformEndpoint(endpoint.path)) return endpoint
            rememberForeignEndpoint(endpoint.path)
        }

        // No form in the page: this firmware serves a single-page shell, so the API lives in its
        // JavaScript and the shell itself can never name it. Each script is read and asked for the
        // paths it quotes — verbatim, never guessed — because an endpoint a bundle does not contain
        // is not one this device serves.
        for (script in RouterLoginPage.scriptSources(html)) {
            val source = fetchText(base, script) ?: continue
            val found = RouterLoginPage.endpointsInBundle(source)
            // Only a path belonging to the interface this build speaks may be returned. Taking
            // the first path in the bundle is what sent a login to `/cgi-bin/http.cgi`, a
            // different protocol that answered without ever looking at the password.
            val path = found.firstOrNull(::isGoformEndpoint)
            found.filterNot(::isGoformEndpoint).forEach(::rememberForeignEndpoint)
            // The bundle is megabytes of minified framework, so the log gets what matters — the
            // paths found, and a bounded excerpt to show how they are written — rather than a file
            // nobody can read. The note carries the conclusion, which is the line to read first.
            Diagnostics.recordProbe(
                url = RouterUrl.build(base, script).toString(),
                reachable = true,
                detail = null,
                durationMillis = 0,
                page = source.take(EXCERPT_LIMIT),
                note = when {
                    path != null -> "المسار من ملف الجهاز: $path"
                    found.isNotEmpty() -> "مسارات ليست من عائلة goform: ${found.joinToString(", ")}"
                    else -> "لا مسار في هذا الملف (${source.length} حرفًا)"
                },
            )
            if (path == null) continue
            // Only the path is taken from the script. Field names are deliberately not read here: a
            // minified bundle is full of strings that merely look like field names, and a wrong one
            // turns a correct password into a rejected login. The configured names are the
            // known-good values for this firmware, so they stay unless the page states otherwise.
            return RouterLoginPage.Endpoint(path, defaults.userField, defaults.passwordField)
        }
        return null
    }

    /** True when a path the device named belongs to the interface this build speaks. */
    private fun isGoformEndpoint(path: String): Boolean {
        val markers = config.goform.endpointPathMarkers
            .ifEmpty { RouterRoutesConfig.DEFAULT_ENDPOINT_PATH_MARKERS }
        return markers.any { path.contains(it, ignoreCase = true) }
    }

    /**
     * Keeps a path the device named that this build cannot speak to.
     *
     * Recorded rather than discarded: when no interface answers, naming the endpoint the device
     * itself published is the difference between a bug report that can be acted on and one that
     * only says the login failed.
     */
    private fun rememberForeignEndpoint(path: String) {
        if (foreignEndpoints.none { it.equals(path, ignoreCase = true) }) {
            foreignEndpoints = foreignEndpoints + path
        }
    }

    /**
     * Reads a page or script as text.
     *
     * Bounded by [BUNDLE_LIMIT]: a framework bundle is megabytes and the endpoint is a short string
     * near the code that uses it, so reading the whole file would cost time on a slow link for no
     * gain. Buffered to text in one read, because a single-page shell must be read whole before its
     * script tags can be found.
     */
    private fun fetchText(base: String, path: String): String? = runCatching {
        val request = Request.Builder()
            .url(RouterUrl.build(base, path))
            .applyCommonHeaders(base)
            .get()
            .build()
        executeRequest(request).use { response ->
            if (!response.isSuccessful) return@use null
            response.peekBody(BUNDLE_LIMIT)?.string().orEmpty()
        }
    }.getOrNull()

    /** True when the failure means the configured path is not served at all. */
    private fun Throwable.isPathRejected(): Boolean {
        if (this is RouterError.FeatureNotSupported) return true
        if (this !is RouterError) return false
        // The 404 is carried as the technical detail, not as the message — the message is the
        // Arabic text shown to the user, so looking for the code there never matched.
        return technicalDetail?.contains("404") == true
    }

    /** True when anything in the cause chain is a malformed-reply error. */
    private fun Throwable.hasProtocolCause(): Boolean =
        generateSequence(this) { it.cause }.any { it is java.net.ProtocolException }

    /**
     * How much this failure tells the user, used to pick which attempt to report when several
     * schemes failed. A reply that arrived outranks a silence, because "the device answered with
     * something I cannot read" points at the device, while a timeout suggests the wrong address —
     * and only the former is true when the device did answer.
     */
    private fun Throwable.rank(): Int = when (this) {
        is RouterError.InvalidCredentials -> 40
        is RouterError.DeviceResponseUnreadable -> 30
        // Above a silence and below an unreadable reply: the device did answer, and it named
        // somewhere to go, but a redirect that led nowhere says less than a reply that could not
        // be read at all.
        is RouterError.Redirected -> 28
        is RouterError.FeatureNotSupported -> 25
        is RouterError.UnsupportedFirmware -> 20
        is RouterError.Timeout -> 10
        is RouterError.DeviceNotFound -> 5
        else -> 1
    }

    private fun ensureSuccess(response: Response, routeKey: String) {
        when (response.code) {
            200, 201, 204 -> Unit
            401, 403 -> throw RouterError.InvalidCredentials()
            404 -> throw RouterError.FeatureNotSupported(routeKey, "HTTP 404")
            408, 504 -> throw RouterError.Timeout("HTTP ${response.code}")
            // Redirects are not followed (see [defaultClient]): OkHttp rewrites a redirected POST
            // into a GET, which would drop the goform body and turn a login into an anonymous
            // page fetch. The device is telling us the resource moved, and the caller decides
            // what to do about it.
            in 300..399 -> throw RouterError.DeviceResponseUnreadable(
                "HTTP ${response.code} → ${response.header("Location") ?: "بلا عنوان تحويل"} ($routeKey)",
            )
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

        /** The internet probe is a reachability hint, not a load, so it gives up quickly. */
        private const val INTERNET_PROBE_TIMEOUT_SECONDS = 3L
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) ZLTM90Plus"
        /** Enough of a response to identify the firmware's answer without holding it all. */
        private const val PEEK_LIMIT = 64L * 1024L

        /**
         * A page or script is read whole in one go, so this only has to exceed a bundle's size.
         *
         * A framework bundle is megabytes; eight is ample for the device's own code, and reading a
         * partial script would risk cutting the endpoint string in half.
         */
        private const val BUNDLE_LIMIT = 8L * 1024L * 1024L

        /** How much of a bundle the diagnostics log keeps, enough to see how paths are written. */
        private const val EXCERPT_LIMIT = 4_000
        private val TOKEN_ALIASES = listOf("token", "stok", "session", "sessionid", "key")
        private val RESULT_ALIASES = listOf("result")

        /**
         * Keys of the JSON envelope the device's other dispatcher answers with.
         *
         * `/cgi-bin/http.cgi` replies `{"success":…,"cmd":…,"message":…}`, which carries no `result`
         * at all. Recognising it is what lets the app say "this interface is not supported" instead
         * of inventing a verdict on the password that nothing in the reply supports.
         */
        private val FOREIGN_ENVELOPE_KEYS = listOf("success", "cmd", "message")

        private val FOREIGN_MESSAGE_ALIASES = listOf("message", "msg", "errmsg", "error")

        private val DEFAULT_PROTOCOL_ORDER =
            listOf(RouterRoutesConfig.GOFORM, RouterRoutesConfig.LUCI)

        /** An HTML payload is the login page, not an interface response. */
        internal fun looksLikeLoginPage(text: String): Boolean {
            val trimmed = text.trimStart()
            if (!trimmed.startsWith("<")) return false
            return trimmed.contains("<html", ignoreCase = true) ||
                trimmed.contains("login", ignoreCase = true)
        }

        /**
         * A client with the platform's normal trust rules and no certificate exemption.
         *
         * [defaultClient] deliberately accepts the router's self-signed certificate. That is only
         * safe for requests to a private address, so anything reaching the public internet must
         * come through here instead.
         */
        fun platformTrustClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(INTERNET_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(INTERNET_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        fun defaultClient(config: RouterRoutesConfig): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(config.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(config.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .cookieJar(InMemoryCookieJar)
            // Redirects are handled by the caller, not chased here. OkHttp rewrites a redirected
            // POST into a GET and drops the body, so following one would turn a goform login into
            // an anonymous fetch of the login page and report "wrong password" for a correct one.
            // The device's Location header is also evidence worth keeping in the connection log,
            // which chasing it would consume before the transport could record it.
            .followRedirects(false)
            .followSslRedirects(false)
            // The device's HTTPS presents a self-signed certificate, so the platform's trust
            // anchors reject the router the user is connected to. Requests only ever go to
            // private addresses (RouterUrl.base enforces that), so accepting the device's own
            // certificate does not widen trust for anything reachable on the internet.
            .sslSocketFactory(
                PrivateHost.trustingSocketFactory(),
                PrivateHost.trustingTrustManager(),
            )
            .hostnameVerifier { _, _ -> true }
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
