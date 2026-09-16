package com.zltm90plus.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A diagnostic build uploads recorded traffic off the device, so redaction is the guard that keeps
 * a captured login from carrying the password with it.
 */
class DiagnosticRedactionTest {

    private val secret = "MyS3cretPassw0rd"

    @Test
    fun `login form body does not leak the password`() {
        val body = "isTest=false&goformId=LOGIN&user=admin&password=$secret"

        val redacted = DiagnosticRedaction.redact(body)

        assertFalse(redacted.contains(secret))
        assertTrue(redacted.contains("user=admin"))
        assertTrue(redacted.contains("goformId=LOGIN"))
        assertTrue(redacted.contains("password=«محجوب»"))
    }

    @Test
    fun `base64 password is redacted too`() {
        // The goform login sends the password Base64-encoded, and the encoded form is just as
        // sensitive as the plain one.
        val encoded = java.util.Base64.getEncoder().encodeToString(secret.toByteArray())
        val body = "goformId=LOGIN&password=$encoded"

        val redacted = DiagnosticRedaction.redact(body)

        assertFalse(redacted.contains(encoded))
    }

    @Test
    fun `session token in JSON is redacted`() {
        val json = """{"result":"0","stok":"abc123token","user":"admin"}"""

        val redacted = DiagnosticRedaction.redact(json)

        assertFalse(redacted.contains("abc123token"))
        assertTrue(redacted.contains("\"result\":\"0\""))
        assertTrue(redacted.contains("admin"))
    }

    @Test
    fun `non-sensitive firmware values survive so the report stays useful`() {
        val json = """{"loginfo":"ok","battery_voltage":"3980"}"""

        val redacted = DiagnosticRedaction.redact(json)

        assertTrue(redacted.contains("loginfo"))
        assertTrue(redacted.contains("3980"))
    }

    @Test
    fun `multiple sensitive keys in one body are all masked`() {
        val body = "password=$secret&token=tok123&sysauth=xyz"

        val redacted = DiagnosticRedaction.redact(body)

        assertFalse(redacted.contains(secret))
        assertFalse(redacted.contains("tok123"))
        assertFalse(redacted.contains("xyz"))
        assertEquals(3, Regex("«محجوب»").findAll(redacted).count())
    }

    @Test
    fun `redaction is case insensitive`() {
        val redacted = DiagnosticRedaction.redact("PASSWORD=$secret&Token=tok123")

        assertFalse(redacted.contains(secret))
        assertFalse(redacted.contains("tok123"))
    }
}