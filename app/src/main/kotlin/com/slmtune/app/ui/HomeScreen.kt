package com.slmtune.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slmtune.app.MainActivity
import com.slmtune.app.core.ShareLinks
import com.slmtune.app.core.Store
import com.slmtune.app.service.VpnState
import com.slmtune.app.service.human
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(activity: MainActivity, onServers: () -> Unit) {
    val status by VpnState.status.collectAsState()
    val error by VpnState.error.collectAsState()
    val speed by VpnState.speed.collectAsState()
    val since by VpnState.since.collectAsState()
    val coreStatus by VpnState.coreStatus.collectAsState()
    val settings by Store.settings.collectAsState()
    val server = settings.servers.firstOrNull { it.id == settings.activeId } ?: settings.servers.firstOrNull()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) { while (status == VpnState.Status.Started) { now = System.currentTimeMillis(); delay(1000) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SLMtune", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Slm.Text)
            Spacer(Modifier.width(10.dp))
            Text("4.1 beta", fontSize = 12.sp, color = Slm.Text3)
        }

        // ---- the big button ----
        val on = status == VpnState.Status.Started
        val busy = status == VpnState.Status.Starting || status == VpnState.Status.Stopping
        Box(Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(168.dp).clip(CircleShape)
                    .background(if (on) Brush.linearGradient(listOf(Slm.Card2, Slm.Card)) else Slm.Gradient)
                    .border(2.dp, if (on) Slm.Ok else if (busy) Slm.Warn else Slm.Accent2, CircleShape)
                    .clickable(enabled = !busy) { activity.toggleVpn() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(54.dp), tint = if (on) Slm.Ok else androidx.compose.ui.graphics.Color.White)
                    Text(
                        when (status) {
                            VpnState.Status.Started -> "ОТКЛЮЧИТЬ"; VpnState.Status.Stopped -> "ПОДКЛЮЧИТЬ"
                            VpnState.Status.Starting -> "ПОДКЛЮЧЕНИЕ…"; VpnState.Status.Stopping -> "ОТКЛЮЧЕНИЕ…"
                        },
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) Slm.Text else androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
        }
        Text(
            when (status) {
                VpnState.Status.Started -> "Подключено · " + duration(now - since)
                VpnState.Status.Stopped -> "Отключено"
                else -> coreStatus.ifEmpty { "…" }
            },
            Modifier.fillMaxWidth(), color = if (on) Slm.Ok else Slm.Text2, fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (on) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
            Text("↓ ${human(speed.down)}/с", color = Slm.Ok, fontSize = 13.sp)
            Spacer(Modifier.width(18.dp))
            Text("↑ ${human(speed.up)}/с", color = Slm.Accent2, fontSize = 13.sp)
            Spacer(Modifier.width(18.dp))
            Text("всего ${human(speed.totalDown + speed.totalUp)}", color = Slm.Text3, fontSize = 13.sp)
        }
        error?.let {
            Text(it, Modifier.fillMaxWidth().padding(top = 10.dp).background(Slm.Tint, RoundedCornerShape(10.dp)).padding(12.dp), color = Slm.Err, fontSize = 13.sp)
        }
        // What the internet sees now: the exit IP and country through the tunnel (a working VPN shows the server's).
        var exit by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(status) {
            exit = null
            if (status == VpnState.Status.Started) {
                delay(1500)
                // Slow tunnels (Tor's first circuits) may need a few tries.
                for (attempt in 1..3) {
                    exit = withContext(Dispatchers.IO) { exitInfo() }
                    if (exit?.startsWith("Нет") != true || status != VpnState.Status.Started) break
                    delay(3000)
                }
            }
        }
        if (on) Text(
            exit ?: "Проверяю, через какой адрес вы в интернете…",
            Modifier.fillMaxWidth().padding(top = 6.dp), color = if (exit?.startsWith("Нет") == true) Slm.Err else Slm.Text2, fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        // ---- server ----
        SectionTitle("Сервер")
        Row(
            Modifier.fillMaxWidth().background(Slm.Card, RoundedCornerShape(14.dp)).clickable { onServers() }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(server?.title ?: "Сервер не выбран", fontWeight = FontWeight.SemiBold, color = Slm.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(server?.let { ShareLinks.describe(it) } ?: "Добавьте сервер: ссылка, QR-код, подписка или WARP", fontSize = 12.sp, color = Slm.Text2)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = Slm.Text3)
        }

        // ---- preset ----
        SectionTitle("Что через VPN")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "Всё", "except_ru" to "Кроме РФ", "blocked" to "Заблокированное").forEach { (id, title) ->
                FilterChip(
                    selected = settings.preset == id, onClick = { Store.update { it.copy(preset = id) } }, label = { Text(title) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Slm.Tint, selectedLabelColor = Slm.Text),
                )
            }
        }
        Hint(
            when (settings.preset) {
                "all" -> "Весь трафик идёт через сервер."
                "except_ru" -> "Российские сайты и сервисы — напрямую, остальное через VPN."
                else -> "Через VPN только сайты, заблокированные в России. Остальное напрямую."
            } + (if (on) " Применится при следующем подключении." else ""),
            Modifier.padding(start = 4.dp, top = 4.dp),
        )
        SlmCard(Modifier.padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ИИ через VPN", color = Slm.Text, fontWeight = FontWeight.SemiBold)
                    Hint("ChatGPT, Claude, Gemini, Copilot и другие всегда через VPN, при любом пресете")
                }
                Switch(settings.aiViaVpn, { v -> Store.update { it.copy(aiViaVpn = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = Slm.Accent))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** "Вы в интернете как 104.28.x.x · NL · WARP" from Cloudflare's trace page (the app's own traffic goes through the tunnel). */
fun exitInfo(): String = runCatching {
    // Through sing-box's local inbound: with separate cores running the app itself is outside the tunnel.
    val port = com.slmtune.app.service.VpnState.mixedPort.value
    val proxy = if (port > 0) java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port)) else java.net.Proxy.NO_PROXY
    val c = java.net.URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection(proxy) as java.net.HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 15000
    val kv = c.inputStream.use { String(it.readBytes()) }.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    "Вы в интернете как ${kv["ip"]} · ${kv["loc"]}" + if (kv["warp"] == "on" || kv["warp"] == "plus") " · WARP" else ""
}.getOrElse { "Нет ответа через VPN: ${it.message}" }

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
