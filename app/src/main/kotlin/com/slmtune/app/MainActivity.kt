package com.slmtune.app

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.slmtune.app.core.Importer
import com.slmtune.app.core.Lists
import com.slmtune.app.core.Store
import com.slmtune.app.core.Subscription
import com.slmtune.app.core.Subscriptions
import com.slmtune.app.service.BoxService
import com.slmtune.app.service.VpnState
import com.slmtune.app.ui.AppsScreen
import com.slmtune.app.ui.HomeScreen
import com.slmtune.app.ui.MoreScreen
import com.slmtune.app.ui.QrActivity
import com.slmtune.app.ui.RoutesScreen
import com.slmtune.app.ui.ServersScreen
import com.slmtune.app.ui.Slm
import com.slmtune.app.ui.SlmTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** A subscription URL waiting for the user's "add" (from the clipboard, a QR code or slmtune://). */
    private val askSubscription = mutableStateOf<String?>(null)
    /** "Мои серверы" opened from the "Ещё" screen (full-screen, over the tabs). */
    val myServersOpen = mutableStateOf(false)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) startVpn() else toast("Без разрешения на VPN подключиться нельзя.")
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val qrScan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(QrActivity.RESULT)?.let { importText(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark app: light status and navigation bar icons.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { SlmTheme { Root() } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when {
            intent.getBooleanExtra("connect", false) -> toggleVpn()
            intent.action == Intent.ACTION_VIEW && intent.dataString?.startsWith("slmtune://") == true -> importText(intent.dataString!!)
            intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { importText(it) }
        }
    }

    fun toggleVpn() {
        when (VpnState.status.value) {
            VpnState.Status.Started -> BoxService.stop(this)
            VpnState.Status.Stopped -> {
                val ask = VpnService.prepare(this)
                if (ask != null) vpnPermission.launch(ask) else startVpn()
            }
            else -> {}
        }
    }

    private fun startVpn() {
        if (Store.activeServer() == null) {
            toast("Сначала добавьте сервер: вкладка «Серверы» → «+».")
            return
        }
        lifecycleScope.launch {
            // Preset lists must be on disk before the core reads them (only missing / day-old ones are fetched).
            runCatching { Lists.update(this@MainActivity, Store.settings.value) }
            BoxService.start(this@MainActivity)
        }
    }

    fun scanQr() = qrScan.launch(Intent(this, QrActivity::class.java))

    fun importText(text: String) {
        when (val r = Importer.importText(text)) {
            is Importer.Result.AskSubscription -> askSubscription.value = r.url
            is Importer.Result.Nothing -> toast(r.why)
            is Importer.Result.Servers -> toast(
                if (r.failed.isEmpty()) "Добавлено серверов: ${r.added}"
                else "Добавлено ${r.added}, не подошло ${r.failed.size}: ${r.failed.first()}",
            )
        }
    }

    fun addSubscription(url: String, name: String = "") {
        val sub = Subscription(url = url, name = name)
        Store.update { it.copy(subscriptions = it.subscriptions + sub) }
        refreshSubscription(sub)
    }

    fun refreshSubscription(sub: Subscription) {
        lifecycleScope.launch {
            runCatching { Subscriptions.fetch(sub) }
                .onSuccess { r ->
                    Store.update { s ->
                        val others = s.servers.filter { it.subscriptionId != sub.id }
                        val all = others + r.servers
                        s.copy(
                            servers = all,
                            subscriptions = s.subscriptions.map { if (it.id == sub.id) r.sub else it },
                            activeId = if (all.any { it.id == s.activeId }) s.activeId else all.firstOrNull()?.id ?: "",
                        )
                    }
                    toast("«${r.sub.name}»: серверов ${r.servers.size}" + (if (r.skipped > 0) ", пропущено ${r.skipped}" else ""))
                }
                .onFailure { e ->
                    Store.update { s -> s.copy(subscriptions = s.subscriptions.map { if (it.id == sub.id) it.copy(lastError = e.message) else it }) }
                    toast("Подписка не обновилась: ${e.message}")
                }
        }
    }

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    @Composable
    private fun Root() {
        var tab by remember { mutableIntStateOf(0) }
        val tabs = listOf("Главная" to Icons.Filled.Home, "Серверы" to Icons.Filled.Dns, "Маршруты" to Icons.Filled.Route, "Приложения" to Icons.Filled.Apps, "Ещё" to Icons.Filled.Tune)
        Scaffold(
            containerColor = Slm.Ink,
            bottomBar = {
                NavigationBar(containerColor = Slm.Card) {
                    tabs.forEachIndexed { i, (title, icon) ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, title) }, label = { Text(title, maxLines = 1, softWrap = false, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Slm.Tint, selectedIconColor = Slm.Accent2, selectedTextColor = Slm.Text),
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                if (myServersOpen.value) com.slmtune.app.ui.MyServersScreen(this@MainActivity) { myServersOpen.value = false }
                else when (tab) {
                    0 -> HomeScreen(this@MainActivity, onServers = { tab = 1 })
                    1 -> ServersScreen(this@MainActivity)
                    2 -> RoutesScreen()
                    3 -> AppsScreen()
                    else -> MoreScreen(this@MainActivity)
                }
            }
        }
        val sub = askSubscription.value
        if (sub != null) AlertDialog(
            onDismissRequest = { askSubscription.value = null },
            title = { Text("Добавить подписку?") },
            text = { Text(sub) },
            confirmButton = { TextButton({ askSubscription.value = null; addSubscription(sub) }) { Text("Добавить") } },
            dismissButton = { TextButton({ askSubscription.value = null }) { Text("Отмена") } },
        )
    }
}
