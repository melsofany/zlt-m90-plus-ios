package com.zltm90plus.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Answers "is the phone on a Wi-Fi network that could be the router?" without performing any
 * network call. The router IP itself is not assumed: the user may have changed the LAN subnet,
 * so this only reports Wi-Fi presence and delegates address validation to the API layer.
 */
object LocalNetworkChecker {

    data class WifiStatus(
        val connectedToWifi: Boolean,
        val networkName: String?,
        val localIpv4: String?,
    )

    fun current(context: Context): WifiStatus {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return WifiStatus(false, null, null)
        val network = manager.activeNetwork ?: return WifiStatus(false, null, null)
        val capabilities = manager.getNetworkCapabilities(network) ?: return WifiStatus(false, null, null)

        val onWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val localIp = manager.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull {
                it.address is java.net.Inet4Address &&
                    !it.address.isLoopbackAddress &&
                    !it.address.isLinkLocalAddress
            }
            ?.address
            ?.hostAddress

        return WifiStatus(onWifi, ssid(context, manager, network, onWifi), localIp)
    }

    /**
     * `NetworkCapabilities.transportInfo` only exists from API 29, so older devices still have to
     * read the SSID from `WifiManager`. Both paths need `ACCESS_FINE_LOCATION` to return a real
     * name; without it Android hands back an obfuscated value, which is treated as unknown.
     */
    private fun ssid(
        context: Context,
        manager: ConnectivityManager,
        network: android.net.Network,
        onWifi: Boolean,
    ): String? {
        if (!onWifi) return null
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (manager.getNetworkCapabilities(network)?.transportInfo as? android.net.wifi.WifiInfo)?.ssid
        } else {
            @Suppress("DEPRECATION")
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.connectionInfo
                ?.ssid
        }
        // Without location permission Android returns a redacted name; treat it as unknown.
        return raw?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    /**
     * Reads the phone's default gateway, which on the device's own Wi-Fi is the device's admin
     * address. Android knows this address, so the app does not have to guess a factory default
     * (the M90 Plus ships on 192.168.8.1, 192.168.0.1, 192.168.1.1, 192.168.70.1 or
     * 192.168.100.1 depending on the carrier build, and any of those guesses is wrong on the
     * other builds).
     */
    fun currentGatewayIpv4(context: Context): String? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return manager.getLinkProperties(network)?.routes
            ?.firstOrNull { it.isDefaultRoute }
            ?.gateway
            ?.takeIf { it is java.net.Inet4Address }
            ?.hostAddress
    }

    /** True when the given router host shares a /24 with the phone, i.e. plausibly reachable. */
    fun isPlausiblyLocal(routerHost: String, localIpv4: String?): Boolean {
        if (localIpv4 == null) return true
        val routerPrefix = ipv4Prefix(routerHost) ?: return true
        val localPrefix = ipv4Prefix(localIpv4) ?: return true
        return routerPrefix == localPrefix
    }

    /**
     * Gateway addresses worth probing when the user does not know the device address, ordered by
     * likelihood: the phone's own /24 first (a phone on the device's Wi-Fi is on the device's
     * LAN), then the factory defaults shipped across ZLT M90 Plus carrier builds.
     *
     * The list is only a search order — the caller must probe each candidate, because a wrong
     * guess presented as a found device is worse than saying nothing was found.
     */
    fun discoveryCandidates(localIpv4: String?, gatewayIpv4: String? = null): List<String> {
        val prefix = localIpv4?.let(::ipv4Prefix)
        val fromLocalSubnet = prefix?.let { listOf("$it.1", "$it.254") }.orEmpty()
        // The gateway the phone actually uses is the strongest candidate: it is where the device
        // is, whatever subnet the carrier build picked.
        val knownGateway = gatewayIpv4?.takeIf { ipv4Prefix(it) != null }?.let(::listOf).orEmpty()
        return (knownGateway + fromLocalSubnet + COMMON_GATEWAYS).distinct()
    }

    /**
     * ZLT M90 Plus firmware does not publish its LAN address consistently, and it differs by
     * carrier build, so the search covers the addresses these devices are known to ship with.
     */
    private val COMMON_GATEWAYS = listOf(
        "192.168.0.1",
        "192.168.1.1",
        "192.168.8.1",
        "192.168.10.1",
        "192.168.70.1",
        "192.168.100.1",
        "192.168.1.99",
        "10.0.0.1",
    )

    private fun ipv4Prefix(host: String): String? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        return "${octets[0]}.${octets[1]}.${octets[2]}"
    }
}