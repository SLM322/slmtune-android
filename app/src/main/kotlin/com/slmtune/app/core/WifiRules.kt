package com.slmtune.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiInfo
import android.os.Build
import com.slmtune.app.service.BoxService
import com.slmtune.app.service.VpnState

/**
 * "Дома без VPN": when the phone joins a Wi-Fi network, connect or disconnect by the rules.
 * Works while SLMtune's process is alive (the VPN notification or the tile keep it). Reading the network
 * name needs the location permission on Android 8.1+; without it every network counts as "other".
 * Acts only on a change of network, so a manual connect or disconnect is never overridden.
 */
object WifiRules {
    private var lastSsid: String? = null

    fun start(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    onWifi(context, (caps.transportInfo as? WifiInfo)?.ssid)
                }
                override fun onLost(network: Network) { lastSsid = null }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                @Suppress("DEPRECATION")
                override fun onAvailable(network: Network) {
                    onWifi(context, context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java).connectionInfo?.ssid)
                }
                override fun onLost(network: Network) { lastSsid = null }
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    private fun onWifi(context: Context, raw: String?) {
        val ssid = raw?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" } ?: ""
        if (ssid == lastSsid) return
        lastSsid = ssid
        val s = Store.settings.value
        if (!s.wifiRulesOn) return
        val home = ssid.isNotEmpty() && s.wifiHome.any { it.equals(ssid, true) }
        val running = VpnState.status.value == VpnState.Status.Started
        when {
            home && running -> BoxService.stop(context)
            !home && s.wifiOtherConnect && !running && VpnService.prepare(context) == null -> BoxService.start(context)
        }
    }

    /** The current Wi-Fi name for "Добавить текущую сеть" (needs the location permission). */
    fun currentSsid(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (caps.transportInfo as? WifiInfo)?.ssid
        else @Suppress("DEPRECATION") context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java).connectionInfo?.ssid
        return ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }
}
