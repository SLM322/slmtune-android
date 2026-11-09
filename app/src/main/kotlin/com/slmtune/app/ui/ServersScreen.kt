package com.slmtune.app.ui

import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slmtune.app.MainActivity
import com.slmtune.app.core.Outbounds
import com.slmtune.app.core.Server
import com.slmtune.app.core.ShareLinks
import com.slmtune.app.core.Store
import com.slmtune.app.core.Subscriptions
import com.slmtune.app.core.Warp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

@Composable
fun ServersScreen(activity: MainActivity) {
    val settings by Store.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var addMenu by remember { mutableStateOf(false) }
    var subDialog by remember { mutableStateOf(false) }
    var groupDialog by remember { mutableStateOf(false) }
    var torDialog by remember { mutableStateOf(false) }
    var cascadeFor by remember { mutableStateOf<Server?>(null) }

    fun pingAll() = scope.launch {
        val results = withContext(Dispatchers.IO) {
            settings.servers.filter { it.kind != "group" }.map { s -> async { s.id to tcpPing(s.server, s.port) } }.awaitAll().toMap()
        }
        Store.update { st -> st.copy(servers = st.servers.map { it.copy(pingMs = results[it.id] ?: it.pingMs) }) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Серверы", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Slm.Text, modifier = Modifier.weight(1f))
                    IconButton({ pingAll() }) { Icon(Icons.Filled.Speed, "Проверить пинг", tint = Slm.Text2) }
                }
                if (settings.servers.isEmpty()) Hint(
                    "Нажмите «+»: ссылка из буфера (vless://, hysteria2://, ss://…), QR-код камерой, подписка, бесплатный Cloudflare WARP. " +
                        "Ссылки slmtune:// и «Поделиться» из Telegram тоже добавляют серверы.",
                    Modifier.padding(4.dp),
                )
            }
            // Subscriptions: a header with traffic left, then their servers.
            for (sub in settings.subscriptions) {
                item(key = "sub-" + sub.id) {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sub.name.ifBlank { "Подписка" }, fontWeight = FontWeight.SemiBold, color = Slm.Text2, fontSize = 13.sp)
                            val info = sub.lastError ?: Subscriptions.describe(sub)
                            if (info.isNotEmpty()) Text(info, fontSize = 11.sp, color = if (sub.lastError != null) Slm.Err else Slm.Text3)
                        }
                        IconButton({ activity.refreshSubscription(sub) }) { Icon(Icons.Filled.Refresh, "Обновить", tint = Slm.Text3) }
                        var m by remember { mutableStateOf(false) }
                        Box {
                            IconButton({ m = true }) { Icon(Icons.Filled.MoreVert, null, tint = Slm.Text3) }
                            DropdownMenu(m, { m = false }) {
                                DropdownMenuItem({ Text("Удалить подписку и её серверы") }, {
                                    m = false
                                    Store.update { s -> s.copy(subscriptions = s.subscriptions - sub, servers = s.servers.filter { it.subscriptionId != sub.id }) }
                                })
                            }
                        }
                    }
                }
                items(settings.servers.filter { it.subscriptionId == sub.id }, key = { it.id }) { s ->
                    ServerRow(activity, s, s.id == settings.activeId || (settings.activeId.isEmpty() && s == settings.servers.first())) { cascadeFor = s }
                }
            }
            val own = settings.servers.filter { it.subscriptionId.isEmpty() }
            if (own.isNotEmpty() && settings.subscriptions.isNotEmpty()) item { SectionTitle("Свои") }
            items(own, key = { it.id }) { s ->
                ServerRow(activity, s, s.id == settings.activeId || (settings.activeId.isEmpty() && s == settings.servers.first())) { cascadeFor = s }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(18.dp)) {
            FloatingActionButton({ addMenu = true }, containerColor = Slm.Accent) { Icon(Icons.Filled.Add, "Добавить", tint = androidx.compose.ui.graphics.Color.White) }
            DropdownMenu(addMenu, { addMenu = false }) {
                DropdownMenuItem({ Text("Из буфера обмена") }, {
                    addMenu = false
                    val cb = activity.getSystemService(ClipboardManager::class.java)
                    activity.importText(cb.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString() ?: "")
                })
                DropdownMenuItem({ Text("Сканировать QR-код") }, { addMenu = false; activity.scanQr() })
                DropdownMenuItem({ Text("Подписка по ссылке") }, { addMenu = false; subDialog = true })
                DropdownMenuItem({ Text("Cloudflare WARP (бесплатно)") }, {
                    addMenu = false
                    activity.toast("Регистрирую WARP у Cloudflare…")
                    scope.launch {
                        runCatching { Warp.register() }
                            .onSuccess { Store.addServers(listOf(it)); activity.toast("Добавлен Cloudflare WARP") }
                            .onFailure { activity.toast(it.message ?: "WARP не зарегистрировался") }
                    }
                })
                DropdownMenuItem({ Text("Группа серверов (самый быстрый, резерв)") }, { addMenu = false; groupDialog = true })
                DropdownMenuItem({ Text("Tor с мостами") }, { addMenu = false; torDialog = true })
            }
        }
    }

    if (subDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { subDialog = false },
            title = { Text("Подписка") },
            text = { OutlinedTextField(url, { url = it }, label = { Text("https://…") }, singleLine = true) },
            confirmButton = { TextButton({ subDialog = false; if (url.isNotBlank()) activity.addSubscription(url.trim()) }) { Text("Добавить") } },
            dismissButton = { TextButton({ subDialog = false }) { Text("Отмена") } },
        )
    }

    if (torDialog) {
        var transport by remember { mutableStateOf("snowflake") }
        var bridges by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { torDialog = false },
            title = { Text("Tor") },
            text = {
                LazyColumn(Modifier.height(420.dp)) {
                    item { Hint("Медленно, зато бесплатно и почти не блокируется. Первое подключение занимает до минуты. Только в полной версии.") }
                    items(com.slmtune.app.core.ExtraCores.torTransports, key = { it.first }) { (id, title) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { transport = id }) {
                            RadioButton(transport == id, { transport = id }); Text(title, fontSize = 14.sp)
                        }
                    }
                    item {
                        if (transport == "custom" || transport == "webtunnel")
                            OutlinedTextField(bridges, { bridges = it }, Modifier.fillMaxWidth(), label = { Text("Мосты, по одному в строке") }, minLines = 3)
                    }
                }
            },
            confirmButton = {
                TextButton({
                    if ((transport == "custom" || transport == "webtunnel") && bridges.isBlank()) activity.toast("Вставьте мосты: bridges.torproject.org или @GetBridgesBot.")
                    else { Store.addServers(listOf(com.slmtune.app.core.ExtraCores.torProfile(transport, bridges))); torDialog = false }
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton({ torDialog = false }) { Text("Отмена") } },
        )
    }

    if (groupDialog) {
        val chosen = remember { mutableStateListOf<String>() }
        var mode by remember { mutableStateOf("auto") }
        AlertDialog(
            onDismissRequest = { groupDialog = false },
            title = { Text("Группа серверов") },
            text = {
                LazyColumn(Modifier.height(380.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == "auto", { mode = "auto" }); Text("Самый быстрый") }
                        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == "fallback", { mode = "fallback" }); Text("Резерв (держится за рабочий)") }
                        Hint("Отметьте серверы:")
                    }
                    items(settings.servers.filter { it.kind != "group" }, key = { it.id }) { s ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { if (s.id in chosen) chosen.remove(s.id) else chosen.add(s.id) }) {
                            Checkbox(s.id in chosen, { if (it) chosen.add(s.id) else chosen.remove(s.id) })
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    if (chosen.size >= 2) {
                        Store.addServers(listOf(Server(name = if (mode == "auto") "Авто: лучший сервер" else "Резерв", kind = "group", server = "group", port = 1,
                            params = mapOf("mode" to mode), members = chosen.toList())))
                        groupDialog = false
                    } else activity.toast("Отметьте хотя бы два сервера.")
                }) { Text("Создать") }
            },
            dismissButton = { TextButton({ groupDialog = false }) { Text("Отмена") } },
        )
    }

    cascadeFor?.let { target ->
        AlertDialog(
            onDismissRequest = { cascadeFor = null },
            title = { Text("Каскад: «${target.title}» через…") },
            text = {
                LazyColumn(Modifier.height(380.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { setVia(target, ""); cascadeFor = null }) {
                            RadioButton(target.viaId.isEmpty(), { setVia(target, ""); cascadeFor = null }); Text("Напрямую")
                        }
                    }
                    items(settings.servers.filter { it.id != target.id && it.kind != "group" }, key = { it.id }) { s ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { setVia(target, s.id); cascadeFor = null }) {
                            RadioButton(target.viaId == s.id, { setVia(target, s.id); cascadeFor = null })
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ cascadeFor = null }) { Text("Закрыть") } },
        )
    }
}

private fun setVia(target: Server, viaId: String) = Store.update { s -> s.copy(servers = s.servers.map { if (it.id == target.id) it.copy(viaId = viaId) else it }) }

@Composable
private fun ServerRow(activity: MainActivity, s: Server, active: Boolean, onCascade: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val settings = Store.settings.value
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(if (active) Slm.Tint else Slm.Card, RoundedCornerShape(12.dp))
            .then(if (active) Modifier.border(1.dp, Slm.Accent, RoundedCornerShape(12.dp)) else Modifier)
            .clickable { Store.update { it.copy(activeId = s.id) } }
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.title, color = Slm.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val hops = Outbounds.hops(settings, s)
            Text(
                ShareLinks.describe(s) + if (hops.isNotEmpty()) " · через " + hops.joinToString(" → ") { it.title } else "",
                fontSize = 12.sp, color = Slm.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (s.pingMs >= 0) Text("${s.pingMs} мс", fontSize = 12.sp, color = if (s.pingMs < 150) Slm.Ok else if (s.pingMs < 400) Slm.Warn else Slm.Err)
        else if (s.pingMs == -2) Text("нет", fontSize = 12.sp, color = Slm.Err)
        Spacer(Modifier.width(4.dp))
        Box {
            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = Slm.Text3) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Каскад через…") }, { menu = false; onCascade() })
                if (s.link.isNotEmpty()) DropdownMenuItem({ Text("Поделиться ссылкой") }, {
                    menu = false
                    activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.link), s.title))
                })
                DropdownMenuItem({ Text("Удалить") }, {
                    menu = false
                    Store.update { st -> st.copy(servers = st.servers.filter { it.id != s.id }, activeId = if (st.activeId == s.id) "" else st.activeId) }
                })
            }
        }
    }
}

/** TCP connect time; -2 = no answer within 3 s. */
fun tcpPing(host: String, port: Int): Int = runCatching {
    val t = System.nanoTime()
    Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
    ((System.nanoTime() - t) / 1_000_000).toInt()
}.getOrDefault(-2)
