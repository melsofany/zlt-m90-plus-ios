package com.zltm90plus.app.data.remote

import com.zltm90plus.app.diagnostics.DiagnosticExchange
import com.zltm90plus.app.diagnostics.DiagnosticRedaction
import com.zltm90plus.app.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * The JSON `/cgi-bin/http.cgi` interface served by ZLT M90 PLUS firmware 1.12.8.
 *
 * Every call is a `POST` to one path with a JSON body carrying `cmd`, `method` and `sessionId`.
 * There are no cookies at all — the session lives in the body — and the password itself is never
 * sent, only a digest of it. Command numbers, field names and the handshake order were all observed
 * in captured browser traffic; see `docs/http-cgi-protocol.md`. None are guesses.
 *
 * The one thing *not* confirmed is which inputs feed the password digest. [RouterRoutesConfig.HttpCgi]
 * therefore carries it as `passwordDigest`, and a rejected login is reported as a rejection that
 * *might* be the digest recipe rather than as a confident "wrong password" — see [RouterError.LoginRejected].
 */
internal class HttpCgiClient(
    private val config: RouterRoutesConfig,
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val sessionStore: SessionTokenStore,
) {
    private val api = config.httpCgi

    /** The `sessionId` handed out by a successful login; empty before that, as the device sends it. */
    @Volatile
    private var sessionId: String = sessionStore.token().orEmpty()

    @Volatile
    private var token: String? = null

    /**
     * Logs in.
     *
     * The order is the device's, and it matters: the token nonce is issued *before* login and must
     * be echoed back, and the `sessionId` is the empty string on both those calls. Sending a stale
     * session, or omitting the token, is refused.
     */
    suspend fun login(username: String, password: String): Unit = withContext(Dispatchers.IO) {
        // A stale session from a previous run would make the device answer from the wrong state.
        sessionId = ""
        sessionStore.clear()

        token = readField(call(api.tokenCmd, method = "GET", session = ""), api.tokenField)
            ?: throw RouterError.UnsupportedFirmware(
                "لم يُرجع الجهاز رمز الجلسة من الأمر ${api.tokenCmd}",
            )

        val body = JSONObject()
            .put(api.loginUserField, username)
            .put(api.loginPasswordField, digest(password))
            .put(api.tokenField, token)
            // The browser sends this, and the device expects the field to be present.
            .put("isAutoUpgrade", "0")

        val response = call(api.loginCmd, method = "POST", session = "", extra = body)

        if (!response.optBoolean("success", false)) {
            // The device refused the login command, so it did evaluate *something*. But this build
            // derives the digest itself, and a wrong derivation produces exactly the same refusal as
            // a wrong password. Claiming the password is wrong would be the same mistake this app
            // just spent a commit fixing, so the ambiguity is stated rather than resolved by
            // guessing which one it is.
            throw RouterError.LoginRejected(
                deviceSaid = findString(response, listOf("message", "msg", "errmsg")),
                digestScheme = api.passwordDigest,
            )
        }

        val issued = readField(response, api.sessionField)
        if (issued.isNullOrEmpty()) {
            throw RouterError.UnsupportedFirmware("لم يُرجع الجهاز جلسة بعد تسجيل الدخول")
        }
        sessionId = issued
        sessionStore.saveToken(issued)
    }

    /** Runs a read command. Throws [RouterError.SessionExpired] when there is no session. */
    suspend fun read(cmd: Int, extra: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        if (sessionId.isEmpty()) throw RouterError.SessionExpired()
        call(cmd, method = "GET", session = sessionId, extra = extra)
    }

    /** The session as the device would send it: empty before login, the issued id after. */
    private fun sessionForLogin(): String = if (api.emptySessionBeforeLogin) "" else sessionId

    /**
     * One request. [extra] carries the command-specific fields (a write's payload, or `subcmd`).
     *
     * The body is built by hand rather than with [okhttp3.FormBody] because this interface is JSON:
     * the fields are a nested object, not form pairs.
     */
    private fun call(
        cmd: Int,
        method: String,
        session: String,
        extra: JSONObject? = null,
    ): JSONObject {
        val path = api.path
        val url = RouterUrl.build(baseUrl, path)

        val body = JSONObject()
            .put("cmd", cmd)
            .put("method", method)
            .put(api.sessionField, session)
        extra?.keys()?.forEach { key -> body.put(key, extra.get(key)) }

        val payload = body.toString()
        val request = Request.Builder()
            .url(url.toString())
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/plain, */*")
            // The firmware answers from its own origin check; the browser always sends this.
            .header("Referer", "$baseUrl/")
            .header("Origin", baseUrl)
            .build()

        val startedAt = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                record(startedAt, url.toString(), payload, response.code, text, null)
                if (!response.isSuccessful) {
                    throw if (response.code == 404) {
                        RouterError.UnsupportedFirmware("الجهاز لا يخدم $path")
                    } else {
                        RouterError.DeviceResponseUnreadable("HTTP ${response.code}")
                    }
                }
                val node = runCatching { JSONObject(text) }.getOrNull()
                    ?: throw RouterError.DeviceResponseUnreadable("استجابة غير صالحة للأمر $cmd")
                // A reply that says it is not logged in is a session problem, not a data problem.
                if (!node.optBoolean("success", false) && cmd != api.loginCmd && isLoggedOut(node)) {
                    throw RouterError.SessionExpired()
                }
                node
            }
        } catch (error: Throwable) {
            if (error !is RouterError) record(startedAt, url.toString(), payload, null, null, describeFailure(error))
            throw error.toRouterError()
        }
    }

    private fun record(
        startedAt: Long,
        url: String,
        requestBody: String,
        status: Int?,
        responseBody: String?,
        error: String?,
    ) {
        Diagnostics.record(
            DiagnosticExchange(
                timestampMillis = startedAt,
                url = url,
                method = "POST",
                // Redacted like every other captured body: the login carries a password digest and
                // the responses carry session ids.
                requestBody = DiagnosticRedaction.redact(requestBody),
                statusCode = status,
                responseBody = responseBody?.let(DiagnosticRedaction::redact),
                error = error,
                durationMillis = System.currentTimeMillis() - startedAt,
            ),
        )
    }

    /** Keeps the OS-level cause, which is what identifies a failure the Arabic message only names. */
    private fun describeFailure(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message ?: ""}"

    /**
     * Whether a reply is the device's "you have no session" answer rather than a real result.
     *
     * The device answers `success:false` with a message; the session wording is matched narrowly so
     * that a genuine command failure is not mistaken for an expired session.
     */
    private fun isLoggedOut(node: JSONObject): Boolean {
        val message = findString(node, listOf("message", "msg", "errmsg"))?.lowercase() ?: return false
        return message.contains("not logged") || message.contains("no login") ||
            message.contains("session") || message.contains("login")
    }

    /** First present, non-blank string among [names]; the device's wording varies by command. */
    private fun findString(node: JSONObject, names: List<String>): String? =
        names.firstNotNullOfOrNull { name -> readField(node, name) }

    /** Reads a field as a string, so a numeric reply such as `"cmd":232` is handled too. */
    private fun readField(node: JSONObject, name: String): String? {
        if (!node.has(name)) return null
        val value = node.opt(name)?.toString()?.trim()
        return value?.takeIf { it.isNotEmpty() }
    }

    /**
     * Turns the password into what the device expects to receive.
     *
     * The device was observed to receive a 64-character value, consistent with a SHA-256 hex digest.
     * The recipe is configurable because it is not yet confirmed — if it turns out to include the
     * token or `domain_value`, that is a change to `router_routes.json`, not to this file.
     */
    private fun digest(password: String): String = when (api.passwordDigest) {
        RouterRoutesConfig.DIGEST_SHA256 -> sha256Hex(password)
        else -> throw RouterError.UnsupportedFirmware(
            "طريقة اشتقاق كلمة المرور غير معروفة: ${api.passwordDigest}",
        )
    }

    private fun Throwable.toRouterError(): RouterError = when (this) {
        is RouterError -> this
        is SocketTimeoutException -> RouterError.Timeout()
        is UnknownHostException -> RouterError.DeviceNotFound(message)
        is ConnectException -> RouterError.DeviceNotFound(message)
        is NoRouteToHostException -> RouterError.DeviceNotFound(message)
        is IOException -> RouterError.DeviceResponseUnreadable(message ?: javaClass.simpleName)
        else -> RouterError.TemporaryFailure(javaClass.simpleName, this)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}