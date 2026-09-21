package com.zltm90plus.app.diagnostics

/**
 * Removes credential-shaped values from captured traffic before it leaves the device.
 *
 * Diagnostic builds upload what the firmware answered, and a login response echoes nothing
 * sensitive, but a request body legitimately carries the password. Redacting is therefore done on
 * every captured string rather than trusting the caller to remember.
 */
object DiagnosticRedaction {

    private val SENSITIVE_KEYS = listOf(
        "password",
        "passwd",
        "pwd",
        "passphrase",
        "stok",
        "token",
        "sessionid",
        "sysauth",
        "auth",
        "secret",
    )

    /** `key=value` inside a form body or a query string. */
    private val FORM_PAIR = Regex(
        "(?i)\\b(${SENSITIVE_KEYS.joinToString("|")})=([^&\\s\"'<>]*)",
    )

    /** `"key":"value"` inside a JSON body. */
    private val JSON_PAIR = Regex(
        "(?i)\"(${SENSITIVE_KEYS.joinToString("|")})\"\\s*:\\s*\"([^\"]*)\"",
    )

    private const val MASK = "«محجوب»"

    fun redact(text: String): String =
        text.replace(FORM_PAIR) { "${it.groupValues[1]}=$MASK" }
            .replace(JSON_PAIR) { "\"${it.groupValues[1]}\":\"$MASK\"" }
}
