package com.zltm90plus.app.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log is what the user reads when a connection fails, so what it keeps, what it drops and what
 * its share text contains all matter. It is asserted directly rather than through the UI because
 * these are the properties the screen depends on.
 */
class DiagnosticLogTest {

    @After
    fun tearDown() = DiagnosticLog.clear()

    private fun exchange(
        url: String = "http://192.168.8.1/goform/goform_get_cmd_process",
        method: String = "GET",
        status: Int? = 200,
        error: String? = null,
        requestBody: String? = null,
        responseBody: String? = """{"loginfo":"ok"}""",
    ) = DiagnosticExchange(
        timestampMillis = System.currentTimeMillis(),
        url = url,
        method = method,
        requestBody = requestBody,
        statusCode = status,
        responseBody = responseBody,
        error = error,
        durationMillis = 12,
    )

    @Test
    fun `exchanges are kept in the order they happened`() {
        DiagnosticLog.clear()
        DiagnosticLog.record(exchange(url = "http://192.168.8.1/first"))
        DiagnosticLog.record(exchange(url = "http://192.168.8.1/second"))

        val urls = DiagnosticLog.exchanges.value.map { it.url }
        assertEquals(listOf("http://192.168.8.1/first", "http://192.168.8.1/second"), urls)
    }

    @Test
    fun `a failed exchange keeps the reason the request failed`() {
        DiagnosticLog.clear()
        DiagnosticLog.record(
            exchange(
                status = null,
                responseBody = null,
                error = "ConnectException: Failed to connect to /192.168.8.1:80",
            ),
        )

        val recorded = DiagnosticLog.exchanges.value.single()
        assertEquals(null, recorded.statusCode)
        assertTrue(recorded.error!!.contains("Failed to connect"))
    }

    @Test
    fun `the log is bounded so a long session cannot grow without limit`() {
        DiagnosticLog.clear()
        repeat(250) { DiagnosticLog.record(exchange(url = "http://192.168.8.1/$it")) }

        val kept = DiagnosticLog.exchanges.value
        assertEquals(200, kept.size)
        // The newest are the ones worth keeping: a failed attempt is the last thing that happened.
        assertTrue("the most recent exchange must survive", kept.last().url.endsWith("/249"))
        assertFalse("the oldest must be dropped", kept.any { it.url.endsWith("/0") })
    }

    @Test
    fun `clearing empties the log`() {
        DiagnosticLog.record(exchange())
        DiagnosticLog.clear()
        assertTrue(DiagnosticLog.exchanges.value.isEmpty())
    }

    @Test
    fun `the share text names the request the device answered and its response`() {
        DiagnosticLog.clear()
        DiagnosticLog.record(
            exchange(url = "http://192.168.8.1/goform/goform_set_cmd_process", method = "POST"),
        )

        val text = DiagnosticLog.asText()
        assertTrue(text.contains("goform_set_cmd_process"))
        assertTrue(text.contains("POST"))
        assertTrue(text.contains("loginfo"))
    }

    @Test
    fun `the share text states a failure in words`() {
        DiagnosticLog.clear()
        DiagnosticLog.record(
            exchange(status = null, responseBody = null, error = "UnknownHostException: bad host"),
        )

        val text = DiagnosticLog.asText()
        assertTrue(text.contains("UnknownHostException"))
    }

    @Test
    fun `a redacted password is what the log and its share text hold`() {
        DiagnosticLog.clear()
        // Redaction is the transport's job; this asserts the log does not undo it, which is what
        // makes the share text safe to hand to someone else.
        val sentBody = DiagnosticRedaction.redact("goformId=LOGIN&password=$SUPER_SECRET")
        DiagnosticLog.record(
            exchange(method = "POST", requestBody = sentBody, responseBody = """{"result":"0"}"""),
        )

        assertFalse(DiagnosticLog.exchanges.value.single().requestBody!!.contains(SUPER_SECRET))
        assertFalse(DiagnosticLog.asText().contains(SUPER_SECRET))
    }

    @Test
    fun `the share text is empty of entries when nothing was recorded`() {
        DiagnosticLog.clear()
        val text = DiagnosticLog.asText()
        assertTrue("it must still be shareable", text.contains("عدد العمليات: 0"))
    }

    private companion object {
        const val SUPER_SECRET = "Sup3rSecretPassw0rd"
    }
}