package com.zltm90plus.app.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reads the redirect a device sends when it means "reach me somewhere else".
 *
 * This firmware answers an API request with a reply whose status line repeats the request line:
 *
 *     /goform/goform_set_cmd_process HTTP/1.1 301 Moved Permanently
 *
 * OkHttp rejects that as a malformed reply *before* building a `Response`, so the `Location`
 * header — the one field that says where the interface actually lives — is discarded along with
 * the rest of the reply. The bytes still carry an ordinary redirect underneath, so they are read
 * directly when the parsed reply fails. Discarding the target was why a device that was plainly
 * answering looked like a device that could not be reached.
 */
object RouterRedirect {

    /**
     * The prefix is allowed to be a request path, an absolute URL, or absent; this firmware writes
     * the path there. Status must be a 3xx one that carries a target.
     */
    private val STATUS_LINE = Regex(
        pattern = """^\s*(\S+)\s+HTTP/1\.[01]\s+30[12378]\b""",
        option = RegexOption.MULTILINE,
    )

    /**
     * @param reply a raw reply, whose status line may be malformed.
     * @return the resolved target, or null when [reply] is not a redirect this client may follow.
     */
    fun follow(base: String, reply: String): Redirect? {
        val location = location(reply) ?: return null
        return resolve(base, location)
    }

    /** The raw `Location` value from [reply], before it is resolved against anything. */
    fun location(reply: String): String? {
        val status = STATUS_LINE.find(reply) ?: return null
        return reply.substring(status.range.last + 1)
            .lineSequence()
            .takeWhile { it.isNotBlank() }
            .firstOrNull { it.substringBefore(':').trim().equals("Location", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Resolves [location] against [base] and checks it is safe to send credentials to.
     *
     * A redirect is supplied by the device, so it is treated as untrusted input: without this
     * check a device could point the login at an address of its choosing and receive the router
     * password. `PrivateHost` is the same whitelist that guards the configured host.
     */
    fun resolve(base: String, location: String): Redirect? {
        val url = base.toHttpUrlOrNull()?.resolve(location) ?: return null
        val scheme = url.scheme
        if (scheme != "http" && scheme != "https") return null
        val host = url.host
        if (!PrivateHost.isAllowed(host)) return null
        return Redirect(
            scheme = scheme,
            // Keeps the port explicit, because a redirect between schemes is usually a change of
            // port too, and dropping it would silently send the retry back to 192.168.8.1:80.
            authority = if (host.contains(':') && !host.startsWith("[")) "[$host]:${url.port}"
            else "$host:${url.port}",
        )
    }

    /**
     * Asks the device again, over a plain socket, purely to read the target it refused to give us.
     *
     * OkHttp validates the status line before it builds a `Response`, so when the status line is
     * the malformed one this firmware sends, the whole reply — `Location` included — is thrown
     * away and no parsed object can recover it. The bytes are still an ordinary redirect, so they
     * are read directly here.
     *
     * The request deliberately carries no credentials: it asks the same question the failed
     * request asked, only far enough to be told where to go. HTTP only, because the malformed
     * status line was only ever seen on the plain-HTTP port; https answers with a parseable reply
     * that this is not needed for.
     *
     * @return the target, or null when the device did not answer with a usable redirect.
     */
    fun probeHttp(authority: String, path: String, timeoutMillis: Int = 1_500): Redirect? {
        val host = PrivateHost.normalize(authority) ?: return null
        if (!PrivateHost.isAllowed(host)) return null
        val port = PrivateHost.portOf(authority) ?: 80
        val base = "http://$authority"
        return runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMillis)
                socket.soTimeout = timeoutMillis
                val request = buildString {
                    append("POST $path HTTP/1.1\r\n")
                    // The port stays in Host so a device on a non-standard port answers correctly.
                    append("Host: ${if (port == 80) host else "$host:$port"}\r\n")
                    append("User-Agent: $USER_AGENT\r\n")
                    append("Connection: close\r\n")
                    append("Content-Length: 0\r\n\r\n")
                }
                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                val reply = socket.getInputStream().readAtMost(LIMIT_BYTES)
                resolve(base, location(reply) ?: return@use null)
            }
        }.getOrNull()
    }

    /** Reads up to [limit] bytes, stopping at end of stream, without blocking for more. */
    private fun java.io.InputStream.readAtMost(limit: Int): String {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = runCatching { read(buffer, total, limit - total) }.getOrDefault(-1)
            if (read <= 0) break
            total += read
        }
        return String(buffer, 0, total, Charsets.ISO_8859_1)
    }

    data class Redirect(val scheme: String, val authority: String)

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) ZLTM90Plus"
    private const val LIMIT_BYTES = 4_096
}
