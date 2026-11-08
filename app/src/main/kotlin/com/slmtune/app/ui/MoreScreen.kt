package com.slmtune.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.slmtune.app.BuildConfig
import com.slmtune.app.MainActivity
import com.slmtune.app.core.Store
import com.slmtune.app.core.WifiRules

@Composable
fun MoreScreen(activity: MainActivity) {
    val settings by Store.settings.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Text("Ещё", Modifier.padding(top = 14.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Slm.Text)

        SectionTitle("Мои серверы")
        SlmCard {
            Row(Modifier.fillMaxWidth().clickable { activity.myServersOpen.value = true }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Свой VPS по SSH", color = Slm.Text, fontWeight = FontWeight.SemiBold)
                    Hint("VLESS Reality, Hysteria2, TrustTunnel и ещё 9 протоколов на своём сервере, пользователи с лимитами, ссылки для друзей. Серверов: ${settings.myServers.size}")
                }
                Icon(Icons.Filled.ChevronRight, null, tint = Slm.Text3)
            }
        }

        SectionTitle("Kill switch")
        SlmCard {
            Text("Блокировать интернет без VPN", color = Slm.Text, fontWeight = FontWeight.SemiBold)
            Hint("На Android это делает сама система: откройте настройки VPN → SLMtune → включите «Постоянная VPN» и «Блокировать подключения без VPN». Так трафик не уйдёт мимо туннеля, даже если приложение закрыто.")
            Button({ activity.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) }, Modifier.padding(top = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Slm.Accent)) { Text("Открыть настройки VPN") }
        }

        SectionTitle("Правила по Wi-Fi")
        SlmCard {
            SwitchRow("Включить", "В домашних сетях VPN отключается сам, в остальных — подключается (если включено ниже).", settings.wifiRulesOn) { v ->
                if (v && ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                    activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 7)
                Store.update { it.copy(wifiRulesOn = v) }
            }
            SwitchRow("В чужих сетях подключаться", "", settings.wifiOtherConnect) { v -> Store.update { it.copy(wifiOtherConnect = v) } }
            Text("Домашние сети", color = Slm.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            settings.wifiHome.forEach { ssid ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ssid, color = Slm.Text, modifier = Modifier.weight(1f))
                    IconButton({ Store.update { it.copy(wifiHome = it.wifiHome - ssid) } }) { Icon(Icons.Filled.Close, "Убрать", tint = Slm.Text3) }
                }
            }
            OutlinedButton({
                val ssid = WifiRules.currentSsid(activity)
                if (ssid == null) activity.toast("Не видно названия сети: подключитесь к Wi-Fi и разрешите доступ к местоположению.")
                else Store.update { it.copy(wifiHome = (it.wifiHome + ssid).distinct()) }
            }, Modifier.padding(top = 6.dp)) { Text("Добавить текущую сеть") }
            Hint("Правила срабатывают, пока SLMtune работает (уведомление VPN или плитка в шторке). Android показывает название сети только с доступом к местоположению.")
        }

        SectionTitle("Каскад")
        SlmCard {
            var open by remember { mutableStateOf(false) }
            val entry = settings.servers.firstOrNull { it.id == settings.cascadeEntryId }
            Text("Входной сервер для всех", color = Slm.Text, fontWeight = FontWeight.SemiBold)
            Hint("Все серверы идут через него, если у сервера не выбран свой «через» (меню ⋮ у сервера).")
            Column {
                OutlinedButton({ open = true }, Modifier.padding(top = 8.dp)) { Text(entry?.title ?: "Нет — каждый сервер сам по себе") }
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem({ Text("Нет") }, { open = false; Store.update { it.copy(cascadeEntryId = "") } })
                    settings.servers.filter { it.kind != "group" }.forEach { s ->
                        DropdownMenuItem({ Text(s.title) }, { open = false; Store.update { it.copy(cascadeEntryId = s.id) } })
                    }
                }
            }
        }

        SectionTitle("Сеть")
        SlmCard {
            SwitchRow("Локальная сеть мимо VPN", "Роутер, принтеры, трансляция на ТВ работают при включённом VPN.", settings.lanBypass) { v -> Store.update { it.copy(lanBypass = v) } }
            SwitchRow("IPv6 через VPN", "Включайте, если у сервера есть IPv6.", settings.ipv6) { v -> Store.update { it.copy(ipv6 = v) } }
            var remote by remember { mutableStateOf(settings.remoteDns) }
            var direct by remember { mutableStateOf(settings.directDns) }
            OutlinedTextField(remote, { remote = it; Store.update { s -> s.copy(remoteDns = it.trim()) } }, Modifier.fillMaxWidth(), label = { Text("DNS через VPN") }, singleLine = true)
            OutlinedTextField(direct, { direct = it; Store.update { s -> s.copy(directDns = it.trim()) } }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("DNS напрямую") }, singleLine = true)
        }

        SectionTitle("Ядра")
        SlmCard {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val versions = remember {
                runCatching { ctx.assets.open("cores.json").use { com.slmtune.app.core.Store.json.parseToJsonElement(String(it.readBytes())) } }
                    .getOrNull()?.let { it as? kotlinx.serialization.json.JsonObject }?.mapValues { (_, v) -> (v as kotlinx.serialization.json.JsonPrimitive).content }
            }
            Text("sing-box 1.14.4 — основное ядро", color = Slm.Text)
            listOf("xray" to "VLESS XHTTP, VLESS Encryption", "hysteria" to "официальный клиент Hysteria 2", "tor" to "Tor с мостами", "trusttunnel" to "TrustTunnel (ARM64, экспериментально)").forEach { (id, what) ->
                val has = com.slmtune.app.service.CoreRunner.has(ctx, id)
                Text("${com.slmtune.app.core.ExtraCores.titles[id]} ${versions?.get(id)?.substringAfterLast('/')?.removePrefix("v") ?: ""} — " + (if (has) what else "нет в этой сборке"),
                    color = if (has) Slm.Text else Slm.Text3, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (versions == null) Hint("Это лёгкая версия: только sing-box. Xray, Tor, Hysteria и TrustTunnel — в полной.", Modifier.padding(top = 6.dp))
            SwitchRow("Hysteria 2 через официальный клиент", "Вместо встроенного в sing-box. Иногда быстрее и точнее повторяет сервер.", settings.hysteriaOfficial) { v ->
                Store.update { it.copy(hysteriaOfficial = v) }
            }
        }

        SectionTitle("О программе")
        SlmCard {
            Text("SLMtune ${BuildConfig.VERSION_NAME} для Android", color = Slm.Text, fontWeight = FontWeight.SemiBold)
            Hint("Ядра: sing-box (SagerNet, GPL-3.0); в полной версии также Xray (MPL-2.0), Hysteria (MIT), Tor (BSD) с lyrebird, TrustTunnel (Apache-2.0). Приложение распространяется по лицензии GNU GPL версии 3: исходный код доступен по запросу и публикуется вместе с релизами. Часть системного кода взята из sing-box-for-android (GPL-3.0).")
            Hint("Новые версии: t.me/SLMtune", Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(30.dp))
    }
}
