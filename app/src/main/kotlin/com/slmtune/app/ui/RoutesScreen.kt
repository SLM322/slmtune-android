package com.slmtune.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slmtune.app.core.Catalog
import com.slmtune.app.core.CustomRule
import com.slmtune.app.core.Lists
import com.slmtune.app.core.Store

@Composable
fun RoutesScreen() {
    val settings by Store.settings.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var editRule by remember { mutableStateOf<CustomRule?>(null) }
    val tabs = listOf("ИИ", "Игры", "Звонки", "Свои", "Блок")

    Column(Modifier.fillMaxSize()) {
        Text("Маршруты", Modifier.padding(start = 14.dp, top = 14.dp, bottom = 8.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Slm.Text)
        TabRow(tab, containerColor = Slm.Ink, contentColor = Slm.Text, indicator = { pos ->
            TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = Slm.Accent)
        }) {
            tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t, fontSize = 13.sp) }) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            when (tab) {
                0, 2 -> {
                    val group = if (tab == 0) Catalog.AI else Catalog.CALL
                    item {
                        Spacer(Modifier.height(10.dp))
                        if (tab == 0) SwitchRow("ИИ через VPN", "Отмеченные сервисы и их приложения всегда идут через VPN, их адреса тоже ищутся через VPN. Российские нейросети сюда не входят.", settings.aiViaVpn) { v -> Store.update { it.copy(aiViaVpn = v) } }
                        else Hint("UDP голосовых и видеозвонков отмеченных приложений идёт через VPN (провайдеры часто его режут). Нужен сервер с UDP.")
                    }
                    items(Catalog.inGroup(group), key = { it.id }) { d -> ServiceRow(d.id, d.name, d.note, d.domains.size, d.packages.size, settings.disabledServices) }
                }
                1 -> {
                    item {
                        Spacer(Modifier.height(10.dp))
                        Text("Игры", color = Slm.Text, fontWeight = FontWeight.SemiBold)
                        listOf("direct" to "Напрямую — ниже пинг, мимо VPN", "vpn" to "Игровой UDP через VPN, загрузки напрямую", "" to "По общим правилам").forEach { (id, title) ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { Store.update { it.copy(gamesRoute = id) } }) {
                                RadioButton(settings.gamesRoute == id, { Store.update { it.copy(gamesRoute = id) } }); Text(title, color = Slm.Text, fontSize = 14.sp)
                            }
                        }
                        Hint("Игры узнаются по приложению. Снимите галочку, чтобы игра шла по общим правилам.")
                    }
                    items(Catalog.inGroup(Catalog.GAME).filter { it.packages.isNotEmpty() }, key = { it.id }) { d ->
                        ServiceRow(d.id, d.name, d.note, 0, d.packages.size, settings.disabledServices)
                    }
                }
                3 -> {
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Hint("Свои сайты, IP и приложения: через VPN, напрямую или заблокировать. Эти правила важнее всех остальных.", Modifier.weight(1f))
                            IconButton({ editRule = CustomRule() }) { Icon(Icons.Filled.Add, "Добавить", tint = Slm.Accent2) }
                        }
                    }
                    items(settings.customRules, key = { it.id }) { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editRule = r }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name.ifBlank { r.domains.firstOrNull() ?: r.packages.firstOrNull() ?: "Правило" }, color = Slm.Text)
                                Hint(when (r.action) { "direct" -> "напрямую"; "block" -> "блок"; else -> "через VPN" } + " · " +
                                    (r.domains + r.ips + r.packages).take(4).joinToString(", "))
                            }
                            Switch(r.enabled, { v -> Store.update { s -> s.copy(customRules = s.customRules.map { if (it.id == r.id) it.copy(enabled = v) else it }) } },
                                colors = SwitchDefaults.colors(checkedTrackColor = Slm.Accent))
                        }
                    }
                }
                else -> {
                    item {
                        Spacer(Modifier.height(10.dp))
                        Hint("Блокируется внутри VPN: работает, пока VPN подключён. Списки MetaCubeX, обновляются раз в сутки.")
                    }
                    items(Lists.blocks, key = { it.id }) { b ->
                        SwitchRow(b.title, b.note, b.id in settings.blockLists) { v ->
                            Store.update { s -> s.copy(blockLists = if (v) s.blockLists + b.id else s.blockLists - b.id) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }

    editRule?.let { r -> RuleDialog(r) { editRule = null } }
}

@Composable
private fun ServiceRow(id: String, name: String, note: String, sites: Int, apps: Int, disabled: List<String>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Slm.Text, fontSize = 15.sp)
            val parts = listOfNotNull(note.ifBlank { null }, if (sites > 0) "сайтов: $sites" else null, if (apps > 0) "приложений: $apps" else null)
            if (parts.isNotEmpty()) Hint(parts.joinToString(" · "))
        }
        Switch(id !in disabled, { on -> Store.update { s -> s.copy(disabledServices = if (on) s.disabledServices - id else s.disabledServices + id) } },
            colors = SwitchDefaults.colors(checkedTrackColor = Slm.Accent))
    }
}

@Composable
fun SwitchRow(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Slm.Text, fontWeight = FontWeight.SemiBold)
            if (note.isNotEmpty()) Hint(note)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Slm.Accent))
    }
}

@Composable
private fun RuleDialog(rule: CustomRule, onClose: () -> Unit) {
    var name by remember { mutableStateOf(rule.name) }
    var action by remember { mutableStateOf(rule.action) }
    var domains by remember { mutableStateOf(rule.domains.joinToString("\n")) }
    var ips by remember { mutableStateOf(rule.ips.joinToString("\n")) }
    var packages by remember { mutableStateOf(rule.packages.joinToString("\n")) }
    fun lines(t: String) = t.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    val isNew = Store.settings.value.customRules.none { it.id == rule.id }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (isNew) "Новое правило" else "Правило") },
        text = {
            LazyColumn(Modifier.height(420.dp)) {
                item {
                    OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        listOf("vpn" to "Через VPN", "direct" to "Напрямую", "block" to "Блок").forEach { (id, t) ->
                            FilterChip(action == id, { action = id }, label = { Text(t) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Slm.Tint))
                        }
                    }
                    OutlinedTextField(domains, { domains = it }, label = { Text("Сайты (поддомены тоже)") }, minLines = 2)
                    OutlinedTextField(ips, { ips = it }, label = { Text("IP и сети (1.2.3.0/24)") }, minLines = 1)
                    OutlinedTextField(packages, { packages = it }, label = { Text("Приложения (com.example.app)") }, minLines = 1)
                }
            }
        },
        confirmButton = {
            TextButton({
                val r = rule.copy(name = name.trim(), action = action, domains = lines(domains).map { it.lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/') },
                    ips = lines(ips), packages = lines(packages))
                Store.update { s -> s.copy(customRules = if (isNew) s.customRules + r else s.customRules.map { if (it.id == r.id) r else it }) }
                onClose()
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (!isNew) IconButton({ Store.update { s -> s.copy(customRules = s.customRules.filter { it.id != rule.id }) }; onClose() }) {
                    Icon(Icons.Filled.Delete, "Удалить", tint = Slm.Err)
                }
                TextButton(onClose) { Text("Отмена") }
            }
        },
    )
}
