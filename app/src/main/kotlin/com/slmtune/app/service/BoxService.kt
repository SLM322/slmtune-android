package com.slmtune.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.slmtune.app.Application
import com.slmtune.app.MainActivity
import com.slmtune.app.R
import com.slmtune.app.core.ConfigBuilder
import com.slmtune.app.core.ExtraCores
import com.slmtune.app.core.Lists
import com.slmtune.app.core.Store
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the whole app knows about the connection (the UI, the tile and the notification follow it). */
object VpnState {
    enum class Status { Stopped, Starting, Started, Stopping }

    data class Speed(val down: Long = 0, val up: Long = 0, val totalDown: Long = 0, val totalUp: Long = 0)

    val status = MutableStateFlow(Status.Stopped)
    val error = MutableStateFlow<String?>(null)
    val speed = MutableStateFlow(Speed())
    val since = MutableStateFlow(0L)
    val serverName = MutableStateFlow("")
    /** Local SOCKS/HTTP port of sing-box for the app's own requests (0 = not running). */
    val mixedPort = MutableStateFlow(0)
    /** What the separate cores are doing ("Tor подключается: 40%"), empty when none. */
    val coreStatus = MutableStateFlow("")

    val statusFlow: StateFlow<Status> get() = status
}

/**
 * Starts sing-box (libbox CommandServer) inside the VPN service, keeps the foreground notification with the
 * speed, and stops everything cleanly. Lifecycle modelled on sing-box-for-android's BoxService (GPL-3.0).
 */
class BoxService(private val service: Service, private val platform: PlatformInterfaceWrapper) : CommandServerHandler {
    companion object {
        const val ACTION_STOP = "com.slmtune.app.STOP"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            VpnState.error.value = null
            ContextCompat.startForegroundService(context, Intent(context, VPNService::class.java))
        }

        fun stop(context: Context) {
            context.sendBroadcast(Intent(ACTION_STOP).setPackage(context.packageName))
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null
    private var commandServer: CommandServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var speedJob: Job? = null
    private var receiverRegistered = false
    private val cores by lazy { CoreRunner(service, scope) }
    private var backends: Map<String, Int> = emptyMap()
    private var watchJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_STOP) scope.launch { stopService() }
        }
    }

    fun onStartCommand(): Int {
        if (VpnState.status.value != VpnState.Status.Stopped) return Service.START_NOT_STICKY
        VpnState.status.value = VpnState.Status.Starting
        showNotification(service.getString(R.string.status_connecting), "")
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(service, receiver, IntentFilter(ACTION_STOP), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        scope.launch { startService() }
        return Service.START_NOT_STICKY
    }

    private suspend fun startService() {
        try {
            val settings = Store.settings.value
            val server = Store.activeServer() ?: error(service.getString(R.string.error_no_server))
            VpnState.serverName.value = server.name
            // Separate cores first (Xray, Hysteria, Tor, TrustTunnel): sing-box points at their SOCKS ports.
            backends = cores.start(settings, ExtraCores.backends(settings, server)) { msg ->
                VpnState.coreStatus.value = msg
                scope.launch(Dispatchers.Main) { showNotification(server.name, msg) }
            }
            VpnState.coreStatus.value = ""
            val mixed = CoreRunner.freePort()
            val config = ConfigBuilder.build(settings, server, Lists.dir(Application.application), backends, mixed)
            DefaultNetworkMonitor.start()
            val cs = CommandServer(this, platform)
            cs.start()
            commandServer = cs
            cs.startOrReloadService(config, OverrideOptions().apply {
                // "Приложения": only the chosen ones through the VPN, or everything except them.
                // The separate cores run under the app's own uid and can't protect their sockets: the app
                // stays outside the tunnel then, or their traffic would loop back into it.
                val self = if (backends.isEmpty()) listOf(service.packageName) else emptyList()
                val packages = settings.perAppPackages.filter { it != service.packageName }
                when (settings.perAppMode) {
                    "only" -> includePackage = PlatformInterfaceWrapper.StringArray((packages + self).iterator())
                    "bypass" -> excludePackage = PlatformInterfaceWrapper.StringArray((packages + listOf(service.packageName) - self.toSet()).iterator())
                    else -> if (backends.isNotEmpty()) excludePackage = PlatformInterfaceWrapper.StringArray(listOf(service.packageName).iterator())
                }
            })
            VpnState.mixedPort.value = mixed
            VpnState.status.value = VpnState.Status.Started
            VpnState.since.value = System.currentTimeMillis()
            showNotification(server.name, "")
            startSpeed()
            if (backends.isNotEmpty()) watchJob = scope.launch {
                while (isActive) {
                    delay(2000)
                    if (!cores.alive()) {
                        VpnState.error.value = "Отдельное ядро остановилось. " + synchronized(cores.log) { cores.log.takeLast(2).joinToString(" ") }
                        stopService()
                        break
                    }
                }
            }
        } catch (e: Exception) {
            VpnState.error.value = e.message ?: e.toString()
            stopService()
        }
    }

    /** The app's own traffic is the tunnel's traffic: the uid counters give the speed without asking the core. */
    private fun startSpeed() {
        speedJob?.cancel()
        speedJob = scope.launch {
            val uid = Process.myUid()
            val startRx = TrafficStats.getUidRxBytes(uid)
            val startTx = TrafficStats.getUidTxBytes(uid)
            var lastRx = startRx
            var lastTx = startTx
            while (isActive) {
                delay(1000)
                val rx = TrafficStats.getUidRxBytes(uid)
                val tx = TrafficStats.getUidTxBytes(uid)
                val speed = VpnState.Speed(rx - lastRx, tx - lastTx, rx - startRx, tx - startTx)
                VpnState.speed.value = speed
                lastRx = rx
                lastTx = tx
                withContext(Dispatchers.Main) {
                    showNotification(VpnState.serverName.value, "↓ ${human(speed.down)}/с   ↑ ${human(speed.up)}/с")
                }
            }
        }
    }

    suspend fun stopService() {
        if (VpnState.status.value == VpnState.Status.Stopped) return
        VpnState.status.value = VpnState.Status.Stopping
        speedJob?.cancel()
        watchJob?.cancel()
        VpnState.mixedPort.value = 0
        VpnState.coreStatus.value = ""
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { fileDescriptor?.close() }
        fileDescriptor = null
        cores.stop()
        backends = emptyMap()
        DefaultNetworkMonitor.stop()
        withContext(Dispatchers.Main) {
            if (receiverRegistered) {
                runCatching { service.unregisterReceiver(receiver) }
                receiverRegistered = false
            }
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            service.stopSelf()
        }
        VpnState.speed.value = VpnState.Speed()
        VpnState.status.value = VpnState.Status.Stopped
    }

    fun onRevoke() {
        scope.launch { stopService() }
    }

    fun onDestroy() {
        scope.launch { stopService() }
    }

    // ---- notification ----

    private fun showNotification(title: String, text: String) {
        val nm = service.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, service.getString(R.string.channel_vpn), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(service, 0, Intent(service, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(service, 1, Intent(ACTION_STOP).setPackage(service.packageName), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, service.getString(R.string.action_disconnect), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        ServiceCompat.startForeground(
            service, NOTIFICATION_ID, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    // ---- CommandServerHandler ----

    override fun serviceStop() {
        scope.launch { stopService() }
    }

    override fun serviceReload() {
        scope.launch {
            val settings = Store.settings.value
            val server = Store.activeServer() ?: return@launch
            runCatching { commandServer?.startOrReloadService(ConfigBuilder.build(settings, server, Lists.dir(Application.application), backends, VpnState.mixedPort.value), OverrideOptions()) }
                .onFailure { VpnState.error.value = it.message }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(enabled: Boolean) {
    }

    override fun triggerNativeCrash() {
    }

    override fun writeDebugMessage(message: String) {
    }

    override fun connectSSHAgent(): Int = error("not supported")
}

fun human(bytes: Long): String {
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (i == 0) "${bytes} ${units[0]}" else String.format(java.util.Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}
