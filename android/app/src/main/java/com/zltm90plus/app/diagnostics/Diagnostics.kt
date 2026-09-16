package com.zltm90plus.app.diagnostics

/**
 * A single HTTP exchange with the router, already stripped of credentials.
 *
 * Only one of [statusCode] and [error] is set: a completed exchange has a status, a failed one
 * has the exception's simple name. Bodies are captured verbatim because they are the whole point
 * of a diagnostic build — what the firmware actually answered is what identifies the fault.
 */
data class DiagnosticExchange(
    val timestampMillis: Long,
    val url: String,
    val method: String,
    val requestBody: String?,
    val statusCode: Int?,
    val responseBody: String?,
    val error: String?,
    val durationMillis: Long,
    /**
     * The `Location` header of a redirect, kept separately from the body.
     *
     * A redirect body is usually an empty HTML stub, so without this the log showed "HTTP 301" and
     * nothing about where the device wanted the request to go — the single most useful fact when
     * the client and the firmware disagree about a URL or a scheme.
     */
    val redirectLocation: String? = null,
)

/**
 * Receives every HTTP exchange. The interface exists so the transport can report what it did
 * without depending on a logger, and so a normal build can leave the implementation unset.
 */
fun interface DiagnosticsSink {
    fun record(exchange: DiagnosticExchange)
}

/**
 * Process-wide hook the API layer reports to. Unset in normal builds, which makes every call a
 * no-op and keeps the release path free of diagnostic behaviour.
 */
object Diagnostics {

    @Volatile
    private var sink: DiagnosticsSink? = null

    val enabled: Boolean get() = sink != null

    fun install(sink: DiagnosticsSink?) {
        this.sink = sink
    }

    fun record(exchange: DiagnosticExchange) {
        sink?.record(exchange)
    }

    /**
     * Records a reachability probe, which does not go through the API client and so has no HTTP
     * response to report. Reports the outcome as text so an unreachable address is distinguishable
     * from an address that answered but declined.
     */
    fun recordProbe(
        url: String,
        reachable: Boolean,
        detail: String?,
        durationMillis: Long,
    ) {
        record(
            DiagnosticExchange(
                timestampMillis = System.currentTimeMillis(),
                url = url,
                method = "DISCOVERY_PROBE",
                requestBody = null,
                statusCode = null,
                responseBody = if (reachable) "استجاب" else null,
                error = if (reachable) null else (detail ?: "لا استجابة"),
                durationMillis = durationMillis,
            ),
        )
    }
}
