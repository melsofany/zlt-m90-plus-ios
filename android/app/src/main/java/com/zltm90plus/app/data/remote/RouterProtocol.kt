package com.zltm90plus.app.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The router always answers on port 80; only the host is user-editable. */
private const val ROUTER_SCHEME = "http://"

/**
 * Which query interface answered. The ZLT M90 Plus family ships more than one web UI: current
 * firmware (ZLT M90 Plus, 1.12.x) serves the goform/GoAhead interface, while older builds used
 * LuCI-style REST routes. The app probes and remembers the winner instead of assuming one.
 *
 * [AUTO] means "not detected yet"; the first successful probe replaces it.
 */
enum class RouterProtocol { AUTO, GOFORM, LUCI }

/**
 * Host validation for the router address.
 *
 * Cleartext HTTP is allowed broadly in `network_security_config.xml` because a LAN address
 * cannot be expressed there as a CIDR range, so nothing else in the platform stops the app from
 * talking plain HTTP to a public host. These checks are that missing guard: the address is
 * accepted only when it is a private / link-local literal, or a single-label LAN host name.
 */
object PrivateHost {

    /** Hosts that could never be a router, and that must never receive plain-HTTP traffic. */
    private const val LOOPBACK = "localhost"

    /** Accepts `192.168.0.1`, `192.168.0.1:8080`, `http://192.168.0.1/`, or a LAN name. */
    fun isAllowed(hostInput: String): Boolean {
        val host = normalize(hostInput) ?: return false
        if (host.equals(LOOPBACK, ignoreCase = true)) return true
        val octets = ipv4Octets(host)
        if (octets != null) return isPrivateIpv4(octets)
        return isLanHostName(host)
    }

    /** Strips the scheme, any path, and the port. Returns null when nothing usable remains. */
    fun normalize(hostInput: String): String? {
        val withoutScheme = hostInput.trim()
            .removePrefix("http://")
            .removePrefix("https://")
        if (withoutScheme.isEmpty()) return null

        val authority = withoutScheme.substringBefore('/').substringBefore('?')
        if (authority.isEmpty()) return null
        if (authority.contains('@')) return null

        val host = if (authority.startsWith("[")) {
            // IPv6 literal: keep it bracketed-free so the checks below can classify it.
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
        return host.trim().lowercase().takeIf { it.isNotEmpty() }
    }

    fun isPrivateIpv4(octets: List<Int>): Boolean {
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            // Android's tethering/USB range plus the common "no DHCP" fallback.
            a == 192 && b == 0 && octets[2] == 0 -> true
            a == 169 && b == 254 -> true
            // Loopback is local by definition; also what the API tests bind to.
            a == 127 -> true
            else -> false
        }
    }

    private fun ipv4Octets(host: String): List<Int>? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        return octets.takeIf { values -> values.all { it in 0..255 } }
    }

    /** A name with no dot can only resolve inside the local network, never on the public DNS. */
    private fun isLanHostName(host: String): Boolean =
        !host.contains('.') && host.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /**
     * Reads an explicit port out of the address. The device is normally on 80, but a user running
     * a forwarded or non-standard setup can type `192.168.1.1:8080`, and the port must survive
     * into the request URL.
     */
    fun portOf(hostInput: String): Int? {
        val authority = hostInput.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore('?')
        if (authority.startsWith("[")) {
            // IPv6 literal: the port, if any, follows the closing bracket.
            val afterBracket = authority.substringAfter(']', "")
            return afterBracket.removePrefix(":").toIntOrNull()
        }
        return authority.substringAfter(':', "").toIntOrNull()?.takeIf { it in 1..65535 }
    }
}

/**
 * Builds request URLs for the router and refuses anything that is not a local address, so the
 * app cannot be pointed at an arbitrary public host.
 */
internal object RouterUrl {

    fun base(hostInput: String): String {
        val host = PrivateHost.normalize(hostInput)
            ?: throw RouterError.InvalidHost("عنوان غير صالح: $hostInput")
        if (!PrivateHost.isAllowed(host)) {
            throw RouterError.InvalidHost(host)
        }
        val port = PrivateHost.portOf(hostInput)
        return if (port != null && port != 80) "$ROUTER_SCHEME$host:$port" else ROUTER_SCHEME + host
    }

    /**
     * Appends route parameters without percent-encoding them. goform `cmd` lists are
     * comma-separated and `HttpUrl` would otherwise send `%2C`, which the firmware rejects.
     */
    fun build(baseUrl: String, path: String, params: Map<String, String> = emptyMap()): HttpUrl {
        val base = (baseUrl + path).toHttpUrl()
        if (params.isEmpty()) return base
        val encoded = params.entries.joinToString("&") { (key, value) -> "$key=$value" }
        return base.newBuilder().encodedQuery(encoded).build()
    }
}