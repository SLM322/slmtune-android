package com.slmtune.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slmtune.app.MainActivity
import com.slmtune.app.core.MyServer
import com.slmtune.app.core.MyServerConfig
import com.slmtune.app.core.MyUser
import com.slmtune.app.core.ServerSession
import com.slmtune.app.core.Ssh
import com.slmtune.app.core.Store
import com.slmtune.app.service.human
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** "Мои серверы": set up your own VPS over SSH, the same as in the Windows version. */
@Composable
fun MyServersScreen(activity: MainActivity, onClose: () -> Unit) {
    val settings by Store.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val keyDir = remember { File(activity.filesDir, "ssh") }
    var open by remember { mutableStateOf<ServerSession?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<MyServer, String>?>(null) } // server + password while its key is confirmed
    var busy by remember { mutableStateOf(false) }

    open?.let { session ->
        ServerDetail(activity, session) { open = null }
        return
    }
    BackHandler { onClose() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Slm.Text) }
            Text("Мои серверы", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Slm.Text, modifier = Modifier.weight(1f))
            IconButton({ adding = true }) { Icon(Icons.Filled.Add, "Добавить", tint = Slm.Accent2) }
        }
        Hint("Свой VPS: программа ставит sing-box и TrustTunnel, открывает порты, заводит пользователей и выдаёт им ссылки. Нужен сервер на Linux (Ubuntu, Debian) с доступом root по SSH.")
        Spacer(Modifier.height(10.dp))
        if (settings.myServers.isEmpty()) SlmCard { Hint("Серверов пока нет. Нажмите «+» и введите адрес, пользователя и пароль из письма хостинга.") }
        settings.myServers.forEach { s ->
            SlmCard(Modifier.padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth().clickable { open = ServerSession(s, keyDir) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, color = Slm.Text, fontWeight = FontWeight.SemiBold)
                        Hint("${s.user}@${s.host}:${s.port}" + if (s.keyInstalled) " · вход по ключу" else " · вход по паролю")
                    }
                    var menu by remember { mutableStateOf(false) }
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = Slm.Text2) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Убрать из списка") }, {
                            menu = false
                            Store.update { st -> st.copy(myServers = st.myServers.filter { it.id != s.id }) }
                        })
                    }
                }
            }
        }
        if (busy) Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), color = Slm.Accent2, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp)); Text("Получаю ключ сервера…", color = Slm.Text2)
        }
        Spacer(Modifier.height(30.dp))
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("22") }
        var user by remember { mutableStateOf("root") }
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Новый сервер") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(host, { host = it.trim() }, label = { Text("IP или адрес") }, singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Порт SSH") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(user, { user = it.trim() }, label = { Text("Пользователь") }, singleLine = true)
                    OutlinedTextField(pass, { pass = it }, label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(name, { name = it }, label = { Text("Название (необязательно)") }, singleLine = true)
                    Hint("Пароль нужен один раз: программа положит на сервер свой ключ и дальше входит по нему. Пароль не сохраняется.")
                }
            },
            confirmButton = {
                TextButton({
                    val p = port.toIntOrNull() ?: 22
                    if (host.isEmpty()) { activity.toast("Введите адрес сервера."); return@TextButton }
                    adding = false
                    busy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { Ssh.scan(host, p) } }
                            .onSuccess { key -> confirm = MyServer(name = name.trim(), host = host, port = p, user = user.ifEmpty { "root" }, hostKey = key) to pass }
                            .onFailure { activity.toast(it.message ?: "SSH не ответил") }
                        busy = false
                    }
                }) { Text("Далее") }
            },
            dismissButton = { TextButton({ adding = false }) { Text("Отмена") } },
        )
    }

    confirm?.let { (srv, pass) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Доверять серверу?") },
            text = {
                Column {
                    Text("Первое подключение к ${srv.host}:${srv.port}. Отпечаток ключа сервера:", color = Slm.Text2)
                    Text(Ssh.fingerprint(srv.hostKey) + " (" + srv.hostKey.substringBefore(' ') + ")", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = Slm.Text, modifier = Modifier.padding(vertical = 8.dp))
                    Hint("Если сомневаетесь — сверьте его в консоли хостинга: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub")
                }
            },
            confirmButton = {
                TextButton({
                    confirm = null
                    Store.update { st -> st.copy(myServers = st.myServers + srv) }
                    open = ServerSession(srv, keyDir).also { it.password = pass.ifEmpty { null } }
                }) { Text("Доверять") }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ServerDetail(activity: MainActivity, session: ServerSession, onBack: () -> Unit) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    val info by session.info.collectAsState()
    val cfg by session.cfg.collectAsState()
    @Suppress("UNUSED_VARIABLE") val version by session.version.collectAsState()
    val log by session.log.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(session.server) }
    var pass by remember { mutableStateOf(session.password ?: "") }
    var userFor by remember { mutableStateOf<MyUser?>(null) }
    var newUser by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    fun save(s: MyServer) {
        server = s; session.server = s
        Store.update { st -> st.copy(myServers = st.myServers.map { if (it.id == s.id) s else it }) }
    }

    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        session.password = pass.ifEmpty { null }
        scope.launch {
            try { action() } catch (e: Exception) {
                session.addLog("Ошибка: " + (e.message ?: e.toString()))
                activity.toast(e.message ?: e.toString())
            }
            busy = false
        }
    }

    fun connect() = act {
        if (!server.keyInstalled && pass.isNotEmpty()) {
            // Not fatal: without the key the password keeps working for this visit.
            runCatching { session.installKey() }
                .onSuccess { ok -> if (ok) { save(server.copy(keyInstalled = true)); session.addLog("Ключ программы на сервере: дальше вход без пароля.") } }
                .onFailure { session.addLog("Ключ программы положить не удалось (${it.message}). Вход остаётся по паролю.") }
        }
        session.probe()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Slm.Text) }
            Column(Modifier.weight(1f)) {
                Text(server.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Slm.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hint("${server.user}@${server.host}:${server.port}")
            }
            if (busy) CircularProgressIndicator(Modifier.width(22.dp).height(22.dp), color = Slm.Accent2, strokeWidth = 2.dp)
        }

        // ---- connection ----
        SlmCard(Modifier.padding(top = 8.dp)) {
            if (!server.keyInstalled) {
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Пароль SSH") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Hint("После входа программа положит на сервер свой ключ, и пароль больше не понадобится.")
            } else Hint("Вход по ключу программы.")
            Button({ connect() }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Slm.Accent)) {
                Text(if (info == null) "Подключиться" else "Обновить")
            }
            info?.let { i ->
                Text(i.os, color = Slm.Text, modifier = Modifier.padding(top = 8.dp))
                Hint(listOfNotNull(
                    i.arch,
                    if (i.singBox.isNotEmpty()) "sing-box ${i.singBox} (${i.singBoxActive})" else "sing-box не установлен",
                    if (i.ttInstalled) "TrustTunnel (${i.ttActive})" else null,
                    if (i.cpus > 0) "нагрузка ${"%.2f".format(i.load)} на ${i.cpus} ядр." else null,
                    if (i.memTotal > 0) "память ${i.memUsed}/${i.memTotal} МБ" else null,
                    if (i.diskTotal > 0) "диск ${i.diskUsed * 100 / i.diskTotal}%" else null,
                    if (i.uptimeSec > 0) "работает ${i.uptimeSec / 86400} д." else null,
                ).joinToString(" · "))
            }
        }

        val c = cfg
        if (c != null) {
            ProtocolsCard(session, c, busy) { plan ->
                session.check(plan)?.let { activity.toast(it); return@ProtocolsCard }
                act {
                    session.apply(plan)
                    ask = "Готово. Добавить протоколы в список серверов SLMtune?" to { addToApp(activity, session, c.active.firstOrNull() ?: c.users.first()) }
                }
            }

            // ---- users ----
            SectionTitle("Пользователи")
            SlmCard {
                val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                if (c.users.isEmpty()) Hint("Пользователи появятся после «Применить». Первый — «me», это вы.")
                c.users.forEach { u ->
                    val (up, down) = c.usage(u)
                    val state = when (c.state(u, today)) { "off" -> "выключен"; "expired" -> "срок истёк"; "limit" -> "лимит исчерпан"; else -> "активен" }
                    Row(Modifier.fillMaxWidth().clickable { userFor = u }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(u.name, color = if (u.off) Slm.Text3 else Slm.Text, fontWeight = FontWeight.SemiBold)
                            Hint(state + " · " + (if (!(info?.accounting ?: false) && up + down == 0L) "—" else human(up + down)) +
                                (if (u.limit > 0) " из ${human(u.limit)}" else "") + (if (u.expires.isNotEmpty()) " · до ${u.expires}" else "") +
                                (if (u.note.isNotEmpty()) " · ${u.note}" else ""))
                        }
                    }
                }
                OutlinedButton({ newUser = true }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !busy && c.anyProtocol) { Text("Добавить пользователя") }
            }

            // ---- tools ----
            SectionTitle("Инструменты")
            SlmCard {
                listOf(
                    Triple("Обновить sing-box", "до последней версии", com.slmtune.app.core.ServerScripts::updateSingBox),
                    Triple("Защита SSH", "fail2ban и вход только по ключу", com.slmtune.app.core.ServerScripts::harden),
                    Triple("Swap и время", "1 ГБ swap, синхронизация часов", com.slmtune.app.core.ServerScripts::swapAndTime),
                    Triple("Автообновления", "обновления безопасности сами", com.slmtune.app.core.ServerScripts::autoUpdates),
                    Triple("Проверка сети", "скорость и блокировки ChatGPT, Claude, Netflix…", com.slmtune.app.core.ServerScripts::netCheck),
                    Triple("Перезапустить службы", "sing-box и TrustTunnel", com.slmtune.app.core.ServerScripts::restart),
                ).forEach { (title, note, script) ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        if (title == "Защита SSH" && !server.keyInstalled) { activity.toast("Сначала войдите по паролю, чтобы ключ программы оказался на сервере."); return@clickable }
                        act { session.run(script.get(), title.lowercase()); session.probe() }
                    }.padding(vertical = 8.dp)) {
                        Column { Text(title, color = Slm.Text); Hint(note) }
                    }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { act { session.run(com.slmtune.app.core.ServerScripts.LOGS, "журнал служб") } }.padding(vertical = 8.dp)) {
                    Column { Text("Журнал служб", color = Slm.Text); Hint("последние сообщения sing-box и TrustTunnel") }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                    ask = "Перезагрузить сервер? VPN пропадёт примерно на минуту." to { act { session.run(com.slmtune.app.core.ServerScripts.REBOOT, "перезагрузка") } }
                }.padding(vertical = 8.dp)) {
                    Column { Text("Перезагрузить сервер", color = Slm.Err); Hint("VPN пропадёт примерно на минуту") }
                }
            }
        }

        // ---- log ----
        if (log.isNotEmpty()) {
            SectionTitle("Журнал")
            SlmCard {
                Text(log.takeLast(60).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Slm.Text2, lineHeight = 14.sp)
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (newUser) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newUser = false },
            title = { Text("Новый пользователь") },
            text = { OutlinedTextField(name, { name = it.filter { ch -> ch.isLetterOrDigit() || ch in "-_." } }, label = { Text("Имя (латиница)") }, singleLine = true) },
            confirmButton = {
                TextButton({
                    val c = session.cfg.value ?: return@TextButton
                    runCatching { c.addUser(name.ifEmpty { "user${c.nextUserId()}" }) }.onFailure { activity.toast(it.message ?: ""); return@TextButton }
                    newUser = false
                    act { session.applyUsers() }
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton({ newUser = false }) { Text("Отмена") } },
        )
    }

    userFor?.let { u -> UserDialog(activity, session, u, busy, { userFor = null }, ::act) }

    ask?.let { (q, yes) ->
        AlertDialog(
            onDismissRequest = { ask = null },
            text = { Text(q) },
            confirmButton = { TextButton({ ask = null; yes() }) { Text("Да") } },
            dismissButton = { TextButton({ ask = null }) { Text("Нет") } },
        )
    }
}

@Composable
private fun ProtocolsCard(session: ServerSession, c: MyServerConfig, busy: Boolean, onApply: (ServerSession.Plan) -> Unit) {
    val checked = remember(c) { mutableStateMapOf<String, Boolean>().apply { ServerSession.protocols.forEach { (k, _) -> put(k, c.has(k)) } } }
    val ports = remember(c) { mutableStateMapOf<String, String>().apply { ServerSession.protocols.forEach { (k, _) -> put(k, c.port(k).toString()) } } }
    var domain by remember(c) { mutableStateOf(c.domain(session.server.host)) }
    var sni by remember(c) { mutableStateOf(c.realitySni) }
    var bbr by remember { mutableStateOf(true) }
    SectionTitle("Протоколы")
    SlmCard {
        Hint("Отметьте, что поставить. Снятая галочка убирает протокол с сервера. Самые надёжные в РФ: VLESS Reality, Hysteria2, TrustTunnel.")
        ServerSession.protocols.forEach { (k, title) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(checked[k] == true, { checked[k] = it }, colors = CheckboxDefaults.colors(checkedColor = Slm.Accent))
                Text(title, color = Slm.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                OutlinedTextField(ports[k] ?: "", { ports[k] = it.filter(Char::isDigit).take(5) }, Modifier.width(92.dp), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Slm.Text), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }
        if (checked.any { it.value && it.key in ServerSession.withDomain })
            OutlinedTextField(domain, { domain = it.trim().lowercase() }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Домен (для сертификата)") }, singleLine = true)
        if (checked["vless"] == true || checked["shadowtls"] == true)
            OutlinedTextField(sni, { sni = it.trim() }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Сайт для маскировки") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(bbr, { bbr = it }, colors = CheckboxDefaults.colors(checkedColor = Slm.Accent)); Text("Ускорение TCP (BBR)", color = Slm.Text, fontSize = 14.sp)
        }
        Button({
            onApply(ServerSession.Plan(checked.filter { it.value }.keys.associateWith { ports[it]?.toIntOrNull() ?: 0 }, domain, sni, bbr))
        }, Modifier.fillMaxWidth(), enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Slm.Accent)) { Text("Применить") }
    }
}

@Composable
private fun UserDialog(activity: MainActivity, session: ServerSession, u: MyUser, busy: Boolean, onClose: () -> Unit, act: (suspend () -> Unit) -> Unit) {
    val c = session.cfg.value ?: return
    var limit by remember { mutableStateOf(if (u.limit > 0) (u.limit / (1L shl 30)).toString() else "") }
    var expires by remember { mutableStateOf(u.expires) }
    var note by remember { mutableStateOf(u.note) }
    var links by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(u.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(limit, { limit = it.filter(Char::isDigit) }, label = { Text("Лимит, ГБ (пусто — без лимита)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(expires, { expires = it.trim() }, label = { Text("Действует до (ГГГГ-ММ-ДД)") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Заметка") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton({ links = true }) { Text("Ссылки") }
                    OutlinedButton({ addToApp(activity, session, u); onClose() }) { Text("В SLMtune") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton({ u.off = !u.off; onClose(); act { session.applyUsers() } }, enabled = !busy) { Text(if (u.off) "Включить" else "Выключить") }
                    TextButton({ onClose(); act { session.resetUsage(u) } }, enabled = !busy) { Text("Обнулить трафик") }
                }
                if (u.name != "me") TextButton({ c.users.remove(u); onClose(); act { session.applyUsers() } }, enabled = !busy) { Text("Удалить", color = Slm.Err) }
            }
        },
        confirmButton = {
            TextButton({
                if (expires.isNotEmpty() && !Regex("""^\d{4}-\d{2}-\d{2}$""").matches(expires)) { activity.toast("Дата в виде 2026-12-31."); return@TextButton }
                u.limit = (limit.toLongOrNull() ?: 0L) * (1L shl 30); u.expires = expires; u.note = note.trim()
                onClose()
                act { session.applyLimits() }
            }, enabled = !busy) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClose) { Text("Закрыть") } },
    )
    if (links) {
        val list = c.links(session.server.host, u, session.server.title.ifEmpty { "Мой сервер" }) +
            listOfNotNull(c.telegramLink(session.server.host, u)?.let { "Telegram-прокси" to it })
        AlertDialog(
            onDismissRequest = { links = false },
            title = { Text("Ссылки для ${u.name}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Hint("Нажмите, чтобы скопировать. «Поделиться всеми» отправит их одним сообщением.")
                    list.forEach { (title, link) ->
                        Column(Modifier.fillMaxWidth().clickable {
                            activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, link))
                            activity.toast("Скопировано: $title")
                        }.padding(vertical = 6.dp)) {
                            Text(title, color = Slm.Text, fontWeight = FontWeight.SemiBold)
                            Text(link, color = Slm.Text3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, list.joinToString("\n\n") { "${it.first}:\n${it.second}" }), "Ссылки"))
                }) { Text("Поделиться всеми") }
            },
            dismissButton = { TextButton({ links = false }) { Text("Закрыть") } },
        )
    }
}

/** The user's protocols into the app's own server list (Hysteria2 with the pinned certificate). */
private fun addToApp(activity: MainActivity, session: ServerSession, u: MyUser) {
    val c = session.cfg.value ?: return
    val label = session.server.title
    val list = runCatching { c.profiles(session.server.host, u, label) }.getOrElse { activity.toast(it.message ?: ""); return }
    // Replace what this server gave before, so links stay current after a change.
    Store.update { st ->
        val prefix = "$label · "
        val kept = st.servers.filter { !(it.name.startsWith(prefix) && it.subscriptionId.isEmpty() && it.kind != "group") }
        st.copy(servers = kept + list, activeId = st.activeId.takeIf { id -> kept.any { it.id == id } } ?: list.firstOrNull()?.id ?: st.activeId)
    }
    activity.toast("В список добавлено: ${list.size}")
}
