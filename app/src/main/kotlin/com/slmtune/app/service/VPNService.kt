// openTun adapted from sing-box-for-android (SagerNet, GPL-3.0), bg/VPNService.kt at 1.14.4.
package com.slmtune.app.service

import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.runBlocking

class VPNService : VpnService(), PlatformInterfaceWrapper {
    private val box = BoxService(this, this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = box.onStartCommand()

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        box.onDestroy()
        super.onDestroy()
    }

    override fun onRevoke() {
        // Another VPN took over, or the user switched it off in the system settings.
        runBlocking { box.stopService() }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")
        val builder = Builder().setSession("SLMtune").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val a = inet4Address.next()
            builder.addAddress(a.address(), a.prefix())
        }
        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val a = inet6Address.next()
            builder.addAddress(a.address(), a.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val r4 = options.inet4RouteAddress
                if (r4.hasNext()) {
                    while (r4.hasNext()) builder.addRoute(r4.next().toIpPrefix())
                } else if (options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }
                val r6 = options.inet6RouteAddress
                if (r6.hasNext()) {
                    while (r6.hasNext()) builder.addRoute(r6.next().toIpPrefix())
                } else if (options.inet6Address.hasNext()) {
                    builder.addRoute("::", 0)
                }
                val x4 = options.inet4RouteExcludeAddress
                while (x4.hasNext()) builder.excludeRoute(x4.next().toIpPrefix())
                val x6 = options.inet6RouteExcludeAddress
                while (x6.hasNext()) builder.excludeRoute(x6.next().toIpPrefix())
            } else {
                val r4 = options.inet4RouteRange
                while (r4.hasNext()) {
                    val a = r4.next()
                    builder.addRoute(a.address(), a.prefix())
                }
                val r6 = options.inet6RouteRange
                while (r6.hasNext()) {
                    val a = r6.next()
                    builder.addRoute(a.address(), a.prefix())
                }
            }

            // Per-app: from OverrideOptions set by BoxService ("Приложения" screen).
            val include = options.includePackage
            while (include.hasNext()) {
                try {
                    builder.addAllowedApplication(include.next())
                } catch (_: NameNotFoundException) {
                }
            }
            val exclude = options.excludePackage
            while (exclude.hasNext()) {
                try {
                    builder.addDisallowedApplication(exclude.next())
                } catch (_: NameNotFoundException) {
                }
            }
        }

        val pfd = builder.establish() ?: error("android: the application is not prepared or is revoked")
        box.fileDescriptor = pfd
        return pfd.fd
    }

    override fun sendNotification(notification: Notification) {
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
    }
}
