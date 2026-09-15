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