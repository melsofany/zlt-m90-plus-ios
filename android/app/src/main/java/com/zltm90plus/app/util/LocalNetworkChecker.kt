package com.zltm90plus.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Answers "is the phone on a Wi-Fi network that could be the router?" without performing any
 * network call. The router IP itself is not assumed: the user may have changed the LAN subnet,
 * so this only reports Wi-Fi presence and delegates address validation to the API layer.
 */
object LocalNetworkChecker {

    data class WifiInfo(
        val connectedToWifi: Boolean,
        val networkName: String?,
        val localIpv4: String?,
    )

    fun current(context: Context): WifiInfo {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return WifiInfo(false, null, null)
        val network = manager.activeNetwork ?: return WifiInfo(false, null, null)
        val capabilities = manager.getNetworkCapabilities(network) ?: return WifiInfo(false, null, null)

        val onWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val linkProperties = manager.getLinkProperties(network)
        val localIp = linkProperties?.linkAddresses
            ?.firstOrNull { it.address is java.net.Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress }
            ?.address
            ?.hostAddress

        val ssid = if (onWifi) {
            @Suppress("DEPRECATION")
            runCatching {
                manager.activeNetwork?.let { active ->
                    (manager.getNetworkCapabilities(active)
                        ?.transportInfo as? android.net.wifi.WifiInfo)?.ssid
                }
            }.getOrNull()?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        } else {
            null
        }

        return WifiInfo(onWifi, ssid, localIp)
    }

    /** True when the given router host shares a /24 with the phone, i.e. plausibly reachable. */
    fun isPlausiblyLocal(routerHost: String, localIpv4: String?): Boolean {
        if (localIpv4 == null) return true
        val routerPrefix = ipv4Prefix(routerHost) ?: return true
        val localPrefix = ipv4Prefix(localIpv4) ?: return true
        return routerPrefix == localPrefix
    }

    private fun ipv4Prefix(host: String): String? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        return "${octets[0]}.${octets[1]}.${octets[2]}"
    }
}