package com.zltm90plus.app.data.remote

/**
 * Learns where a device actually accepts a login, from the page it serves.
 *
 * The configured goform path is a guess about the firmware, and this firmware refuses it: the field
 * log showed `POST /goform/goform_set_cmd_process` answered 404 over https, while the same device
 * redirected plain http to https. Both configured paths were wrong at once, so no amount of
 * switching between them could work.
 *
 * The device publishes the right answer in its own login page: the page that asks for the password
 * has to name the endpoint it posts to. Reading it costs one GET, needs no credentials, and cannot
 * be wrong about the endpoint — the page could not log anyone in if it were.
 *
 * The parsing is deliberately conservative: it prefers the configured defaults and only overrides
 * what the page states plainly, so a page this does not understand leaves the previous behaviour
 * intact rather than replacing one wrong guess with another.
 */
object RouterLoginPage {

    /**
     * @param path the endpoint the login form posts to.
     * @param userField name of the username field in that form.
     * @param passwordField name of the password field.
     * @param passwordBase64 true when the page encodes the password before sending it.
     */
    data class Endpoint(
        val path: String,
        val userField: String,
        val passwordField: String,
        val passwordBase64: Boolean,
    )

    /** Matches `goform_set_cmd_process` however the page quotes or names it. */
    private val SET_CMD = Regex("""([\w./-]*goform_set_cmd_process)""", RegexOption.IGNORE_CASE)

    /**
     * A path the device's own JavaScript names as part of an API.
     *
     * Only ever a string the bundle already contains. The app cannot invent a firmware's endpoints,
     * so this recognises the forms an embedded web UI uses and takes them verbatim.
     */
    private val BUNDLE_PATH = Regex(
        """["'](/[\w/.-]*(?:goform[\w/.-]*|set_cmd_process|get_cmd_process|cgi-bin/[\w/.-]+))["']""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The scripts a single-page shell loads, in the order worth fetching.
     *
     * The shell that firmware 1.12.8 serves names `js/chunk-vendors.js` (a framework bundle) and
     * `js/app.js` (the device's own code). The device's code is the one that knows its API, so it
     * is tried first; the framework bundle is not worth the bytes.
     */
    fun scriptSources(html: String): List<String> {
        val sources = Regex("""<script[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            // A shell may load from a CDN; only the device's own scripts can name its API, and an
            // off-host URL would be a request the app has no business making.
            .filterNot { it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("//") }
            .filterNot { it.startsWith("data:") }
            // The real shell writes `js/app.js` and `./assist-entry.js`. A relative src resolves
            // against the page, which is the root, and the path must carry its slash or it would be
            // concatenated onto the host's port.
            .map { if (it.startsWith("/")) it else "/" + it.removePrefix("./") }
            .distinct()
            .toList()
        return sources.sortedBy { it.contains("vendor", ignoreCase = true) }
    }

    /**
     * The API paths a bundle names, most login-like first, or empty when it names none.
     *
     * A `*_set_cmd_process` path is the login endpoint on a goform device, so it is preferred; a
     * plain `goform` path is next; anything else under a `cgi-bin` prefix is a last resort. Ordering
     * matters because the caller posts credentials to whatever comes first.
     */
    fun endpointsInBundle(script: String): List<String> {
        val paths = BUNDLE_PATH.findAll(script).map { it.groupValues[1] }.toSet()
        return paths.sortedWith(
            compareByDescending<String> { it.contains("set_cmd_process", ignoreCase = true) }
                .thenByDescending { it.contains("goform", ignoreCase = true) }
                .thenBy { it },
        )
    }

    /**
     * A field naming convention: `name="user"`, `name='user'`, `name=user`, or `"user":`.
     *
     * The attribute form is why the plain `key:` match was not enough — a login form names its
     * fields in HTML, not in JavaScript.
     */
    private fun fieldPattern(key: String) = Regex(
        """(?:name\s*=\s*["']?$key\b|["']$key["']\s*[=:])""",
        RegexOption.IGNORE_CASE,
    )

    /** The field names and encoding to keep when the page does not state them. */
    data class Defaults(
        val userField: String = "user",
        val passwordField: String = "password",
    )

    /**
     * Reads the endpoint from [html], or null when the page does not name one.
     *
     * @param defaults field names and encoding to keep when the page does not state them.
     */
    fun parse(html: String, defaults: Defaults = Defaults()): Endpoint? {
        val match = SET_CMD.find(html) ?: return null
        val raw = match.groupValues[1]
        if (raw.isEmpty()) return null
        // A page may write the name without a leading slash; the authority is prepended later, so
        // the path needs one.
        val path = if (raw.startsWith("/")) raw else "/$raw"

        return Endpoint(
            path = path,
            userField = when {
                fieldPattern("username").containsMatchIn(html) -> "username"
                fieldPattern("user").containsMatchIn(html) -> "user"
                else -> defaults.userField
            },
            passwordField = when {
                fieldPattern("passwd").containsMatchIn(html) -> "passwd"
                fieldPattern("password").containsMatchIn(html) -> "password"
                else -> defaults.passwordField
            },
            // This firmware base64-encodes the password before posting it; a page that mentions
            // base64 is doing the same thing.
            passwordBase64 = html.contains("base64", ignoreCase = true),
        )
    }
}