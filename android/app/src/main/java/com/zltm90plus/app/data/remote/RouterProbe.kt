package com.zltm90plus.app.data.remote

import com.zltm90plus.app.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Decides whether a candidate address is the device.
 *
 * This is deliberately not "did the request succeed". A router admin interface answers a bare
 * `GET /` in several ways that are not a 200:
 *
 * - `401`, because the interface wants a login before it serves anything.
 * - `404`, because this firmware does not serve that path.
 * - `302`, because it redirects onto its own HTTPS.
 * - a TLS error, because that HTTPS uses a self-signed certificate no public CA knows about.
 *
 * All of those mean the device is present and talking, which is what discovery is asking. Only a
 * transport failure — a timeout, a refused connection — means the address is empty. Treating a
 * `302` as a failure is what produced a log full of timeouts with the real device in the list
 * quietly ruled out.
 */
class RouterProbe(private val connectTimeoutMillis: Long = 1_000) {

    data class Attempt(
        val reachable: Boolean,
        /** Scheme to continue on, learned from a redirect or a TLS handshake. */
        val scheme: String?,
        val detail: String?,
    )

    /**
     * Probes one candidate over http, then https.
     *
     * http is tried first because every documented build of this firmware serves plain HTTP, so
     * the common case costs one request. https is only reached when http could not connect at all.
     */
    suspend fun probe(candidate: String): Attempt = withContext(Dispatchers.IO) {
        val client = client()
        var lastFailure: String? = null

        for (scheme in SCHEMES) {
            val request = Request.Builder().url("$scheme://$candidate/").get().build()
            try {
                client.newCall(request).execute().use { response ->
                    // Any status proves something is there. A redirect also tells us which scheme
                    // to use next, which matters when the device refuses to stay on http.
                    return@withContext Attempt(
                        reachable = true,
                        scheme = redirectScheme(response.header("Location")) ?: scheme,
                        detail = "HTTP ${response.code}",
                    )
                }
            } catch (error: Throwable) {
                lastFailure = "${error.javaClass.simpleName}: ${error.message}"
                // A TLS failure means the socket connected and a handshake started, so the address
                // is occupied. The device simply cannot present a certificate Android trusts.
                if (error.hasTlsCause()) {
                    return@withContext Attempt(reachable = true, scheme = "https", detail = lastFailure)
                }
            }
        }
        Attempt(reachable = false, scheme = null, detail = lastFailure)
    }

    /** Probes [candidates] in order and stops at the first device that answers. */
    suspend fun firstReachable(candidates: List<String>): Pair<String, Attempt>? {
        for (candidate in candidates) {
            val startedAt = System.currentTimeMillis()
            val attempt = probe(candidate)
            Diagnostics.recordProbe(
                url = "http://$candidate/",
                reachable = attempt.reachable,
                detail = attempt.detail,
                durationMillis = System.currentTimeMillis() - startedAt,
            )
            if (attempt.reachable) return candidate to attempt
        }
        return null
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        // Redirects are followed by hand: the redirect itself is the signal being detected, so
        // letting OkHttp chase it would swallow the evidence and turn it into a TLS error.
        .followRedirects(false)
        // The device's own HTTPS is self-signed. Candidates are private literals by construction,
        // so trusting their certificate cannot be used against a public host.
        .sslSocketFactory(PrivateHost.trustingSocketFactory(), PrivateHost.trustingTrustManager())
        .hostnameVerifier { _, _ -> true }
        .build()

    internal fun redirectScheme(location: String?): String? = when {
        location == null -> null
        location.startsWith("https://", ignoreCase = true) -> "https"
        location.startsWith("http://", ignoreCase = true) -> "http"
        // A protocol-relative Location inherits the scheme already in use.
        else -> null
    }

    private fun Throwable.hasTlsCause(): Boolean =
        generateSequence(this) { it.cause }.any { it is javax.net.ssl.SSLException }

    private companion object {
        val SCHEMES = listOf("http", "https")
    }
}
