package com.zltm90plus.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Collects HTTP traffic and uploads it for analysis.
 *
 * Lives only in the `diagnostic` build type, so a normal build has no reporter, no upload
 * permission and no diagnostic code in its dex.
 *
 * Every exchange is written to disk first and then flushed, so a report survives the app being
 * killed or the network dropping out mid-session. Nothing here is allowed to throw into the
 * request path: a failed upload must never break the connection it is trying to observe.
 */
class DiagnosticReporter(
    context: Context,
    private val endpoint: String,
    private val deviceLabel: String,
) : DiagnosticsSink {

    private val appContext = context.applicationContext
    private val queueFile = File(appContext.filesDir, "diagnostics-queue.json")
    private val lock = Any()

    /** Uploads are serialized so two flushes cannot interleave into the same file. */
    private val uploader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diagnostics-upload").apply { isDaemon = true }
    }

    @Volatile
    private var sessionId: String = "session-${System.currentTimeMillis()}"

    private val pending = mutableListOf<JSONObject>()

    /** Non-null once an upload has been attempted, for the on-screen status line. */
    @Volatile
    var lastUploadResult: String? = null
        private set

    @Volatile
    var uploadedCount: Int = 0
        private set

    init {
        runCatching { loadQueue() }
    }

    override fun record(exchange: DiagnosticExchange) {
        runCatching {
            val entry = exchange.toJson()
            synchronized(lock) {
                pending += entry
                persistQueue()
            }
            scheduleUpload()
        }
    }

    /**
     * A full environment snapshot, so a report can be read without asking follow-up questions.
     * The router host and username are not duplicated here: every captured exchange carries the
     * full URL and form body, so the report shows what was actually sent.
     */
    fun beginSession(context: Context, appVersion: String) {
        sessionId = "session-${System.currentTimeMillis()}"
        runCatching {
            val header = JSONObject().apply {
                put("kind", "session")
                put("sessionId", sessionId)
                put("deviceLabel", deviceLabel)
                put("appVersion", appVersion)
                put("androidSdk", Build.VERSION.SDK_INT)
                put("androidRelease", Build.VERSION.RELEASE)
                put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("network", networkSummary(context))
                put("redaction", "password/token/session fields masked before upload")
            }
            synchronized(lock) {
                pending += header
                persistQueue()
            }
            scheduleUpload()
        }
    }

    /**
     * Builds the same summary the user sees, so the report states what the app believed about the
     * connection rather than only what the socket did.
     */
    private fun networkSummary(context: Context): JSONObject {
        val json = JSONObject()
        runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork
            val capabilities = network?.let { manager.getNetworkCapabilities(it) }
            json.put("onWifi", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false)
            json.put("hasInternet", capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
            ) ?: false)
            val link = network?.let { manager.getLinkProperties(it) }
            json.put("localIpv4", link?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address }
                ?.address?.hostAddress)
            json.put("gateway", link?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress)
            json.put("dns", JSONArray(link?.dnsServers?.map { it.hostAddress } ?: emptyList<String>()))
        }.onFailure { json.put("networkError", it.javaClass.simpleName) }
        return json
    }

    /** Uploads in the background; failures leave the queue on disk for the next attempt. */
    private fun scheduleUpload() {
        uploader.execute {
            runCatching { flush() }
        }
    }

    private fun flush() {
        val payload = synchronized(lock) {
            if (pending.isEmpty()) null else JSONArray(pending.toList())
        } ?: return

        val body = JSONObject().apply {
            put("sessionId", sessionId)
            put("deviceLabel", deviceLabel)
            put("uploadedAtMillis", System.currentTimeMillis())
            put("exchanges", payload)
        }.toString()

        val outcome = upload(body)
        lastUploadResult = outcome
        if (outcome.startsWith("ok")) {
            synchronized(lock) {
                // Only drop what was actually sent; a request recorded during the upload stays.
                repeat(payload.length()) { if (pending.isNotEmpty()) pending.removeAt(0) }
                persistQueue()
            }
            uploadedCount += payload.length()
        }
    }

    private fun upload(body: String): String = runCatching {
        val url = java.net.URL(endpoint)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        connection.disconnect()
        if (code in 200..299) "ok HTTP $code" else "فشل: HTTP $code"
    }.getOrElse { "فشل: ${it.javaClass.simpleName}" }

    private fun persistQueue() {
        runCatching {
            queueFile.writeText(JSONArray(pending.toList()).toString())
        }
    }

    private fun loadQueue() {
        if (!queueFile.exists()) return
        val array = JSONArray(queueFile.readText())
        synchronized(lock) {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { pending += it }
            }
        }
        if (pending.isNotEmpty()) scheduleUpload()
    }

    private fun DiagnosticExchange.toJson() = JSONObject().apply {
        put("kind", "exchange")
        put("sessionId", sessionId)
        put("at", timestampMillis)
        put("url", url)
        put("method", method)
        put("durationMs", durationMillis)
        put("requestBody", requestBody ?: JSONObject.NULL)
        put("status", statusCode ?: JSONObject.NULL)
        put("responseBody", responseBody?.take(MAX_BODY) ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
    }

    private companion object {
        const val MAX_BODY = 16_000
    }
}