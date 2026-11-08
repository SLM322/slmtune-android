package com.slmtune.app.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.slmtune.app.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AppEntry(val pkg: String, val label: String, val system: Boolean, val icon: ImageBitmap?)

/** "Приложения": Android's own per-app VPN — which apps go through the tunnel and which bypass it. */
@Composable
fun AppsScreen() {
    val settings by Store.settings.collectAsState()
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != context.packageName && pm.checkPermission(android.Manifest.permission.INTERNET, it.packageName) == PackageManager.PERMISSION_GRANTED }
                .map { ai ->
                    AppEntry(ai.packageName, pm.getApplicationLabel(ai).toString(), (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        runCatching { pm.getApplicationIcon(ai).toBitmap(64, 64).asImageBitmap() }.getOrNull())
                }
                .sortedBy { it.label.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Text("Приложения", Modifier.padding(top = 14.dp, bottom = 8.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Slm.Text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            listOf("off" to "Все через VPN", "only" to "Только отмеченные", "bypass" to "Отмеченные мимо").forEach { (id, t) ->
                FilterChip(settings.perAppMode == id, { Store.update { it.copy(perAppMode = id) } }, label = { Text(t, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Slm.Tint))
            }
        }
        Hint(
            when (settings.perAppMode) {
                "only" -> "Через VPN идут только отмеченные приложения, остальные — напрямую."
                "bypass" -> "Отмеченные приложения (банки, госуслуги) идут мимо VPN, остальные — через него."
                else -> "Все приложения через VPN, правила маршрутов решают, что идёт напрямую."
            } + " Действует со следующего подключения.",
            Modifier.padding(4.dp),
        )
        if (settings.perAppMode != "off") {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Поиск") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showSystem = !showSystem }) {
                Checkbox(showSystem, { showSystem = it }); Text("Показывать системные", color = Slm.Text2, fontSize = 13.sp)
            }
            val list = apps
            if (list == null) Hint("Загружаю список приложений…")
            else LazyColumn(Modifier.fillMaxSize()) {
                val chosen = settings.perAppPackages.toSet()
                val shown = list.filter { (showSystem || !it.system || it.pkg in chosen) && (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) }
                    .sortedByDescending { it.pkg in chosen }
                items(shown, key = { it.pkg }) { a ->
                    val on = a.pkg in chosen
                    Row(Modifier.fillMaxWidth().clickable { toggle(a.pkg) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        a.icon?.let { Image(it, null, Modifier.size(36.dp)) } ?: Spacer(Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, color = Slm.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(a.pkg, fontSize = 11.sp, color = Slm.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Checkbox(on, { toggle(a.pkg) }, colors = CheckboxDefaults.colors(checkedColor = Slm.Accent))
                    }
                }
            }
        }
    }
}

private fun toggle(pkg: String) = Store.update { s ->
    s.copy(perAppPackages = if (pkg in s.perAppPackages) s.perAppPackages - pkg else s.perAppPackages + pkg)
}
