package com.zltm90plus.app.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The connection log the user can read from inside the app.
 *
 * When a connection fails there is nothing left to inspect: the screen shows one Arabic sentence
 * and the request that produced it is gone. This keeps the exchanges that produced it, so the
 * reason is on the phone rather than in a log nobody can reach.
 *
 * It is a bounded in-memory ring, never written to disk and never uploaded. A report is only ever
 * sent because the user chose to share it, which is also why nothing here needs to survive a
 * restart: the failure being investigated happens while the app is open.
 *
 * Every field was redacted by [DiagnosticRedaction] before it reached this store.
 */
object DiagnosticLog : DiagnosticsSink {

    /**
     * Enough to cover a discovery sweep (up to eight probes) plus a login and a full dashboard
     * load, which is the longest sequence a connection attempt produces.
     */
    private const val CAPACITY = 200

    private val _exchanges = MutableStateFlow<List<DiagnosticExchange>>(emptyList())

    /** Oldest first, so the list reads in the order the connection was attempted. */
    val exchanges: StateFlow<List<DiagnosticExchange>> = _exchanges.asStateFlow()

    override fun record(exchange: DiagnosticExchange) {
        _exchanges.update { (it + exchange).takeLast(CAPACITY) }
    }

    fun clear() = _exchanges.update { emptyList() }

    /**
     * The log as plain text, for the share sheet. Kept readable rather than machine-parseable: it
     * is meant to be pasted into a message, so a person can see what the device answered.
     */
    /**
     * Replaces the contents of script and style blocks with a marker.
     *
     * A page is mostly bundle text, and dumping it whole would bury the handful of characters that
     * matter. What is kept is the markup and every script's `src`, which is where a single-page
     * app names the bundle holding its API — the line this has to be read from.
     */
    private fun summarizeScripts(page: String): String =
        page
            .replace(Regex("""(?s)<script(?![^>]*\bsrc=)[^>]*>.*?</script>"""), "<script>…</script>")
            .replace(Regex("""(?s)<style[^>]*>.*?</style>"""), "<style>…</style>")

    fun asText(): String = buildString {
        appendLine("# سجل التشخيص — ZLT M90 Plus")
        appendLine("# عدد العمليات: ${exchanges.value.size}")
        appendLine()
        exchanges.value.forEachIndexed { index, exchange ->
            appendLine("## ${index + 1}. ${exchange.method} ${exchange.url}")
            appendLine("الوقت: ${exchange.timestampMillis}")
            appendLine("المدة: ${exchange.durationMillis} م.ث")
            exchange.statusCode?.let { appendLine("حالة HTTP: $it") }
            exchange.redirectLocation?.let { appendLine("التحويل إلى: $it") }
            exchange.error?.let { appendLine("الخطأ: $it") }
            exchange.note?.let { appendLine("ملاحظة: $it") }
            exchange.pageSource?.let { appendLine("مصدر الصفحة: ${summarizeScripts(it)}") }
            exchange.requestBody?.takeIf { it.isNotEmpty() }?.let { appendLine("الطلب: $it") }
            exchange.responseBody?.takeIf { it.isNotEmpty() }?.let { appendLine("الرد: $it") }
            appendLine()
        }
    }
}