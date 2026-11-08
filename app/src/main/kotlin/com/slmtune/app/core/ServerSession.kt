package com.slmtune.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One "Мой сервер" while its screen is open: SSH runs, the probe, applying protocols and users, tools.
 * Same checks and order as ServerWindow in the Windows version. The password lives only in memory.
 */
class ServerSession(var server: MyServer, private val keyDir: File) {
    var password: String? = null
    val log = MutableStateFlow<List<String>>(emptyList())
    val info = MutableStateFlow<ServerInfo?>(null)
    val cfg = MutableStateFlow<MyServerConfig?>(null)
    /** Bumped after every change so the screen redraws the (mutable) config. */
    val version = MutableStateFlow(0)

    companion object {
        val protocols = listOf(
            "vless" to "VLESS Reality", "hy2" to "Hysteria2", "ss" to "Shadowsocks 2022", "naive" to "NaiveProxy", "tt" to "TrustTunnel",
            "trojan" to "Trojan", "tuic" to "TUIC", "anytls" to "AnyTLS", "vless_ws" to "VLESS WebSocket", "vless_grpc" to "VLESS gRPC",
            "socks" to "SOCKS5 (для Telegram)", "shadowtls" to "ShadowTLS",
        )
        /** Need a domain with a certificate (Let's Encrypt; TrustTunnel uses its own pinned one). */
        val withDomain = setOf("naive", "tt", "trojan", "tuic", "anytls", "vless_ws", "vless_grpc")
        private val acmeKeys = setOf("naive", "trojan", "tuic", "anytls", "vless_ws", "vless_grpc")
        private val domainRx = Regex("^[a-z0-9.-]+\\.[a-z]{2,}$", RegexOption.IGNORE_CASE)

        fun value(output: String, key: String) = output.lines().lastOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()

        /** Long machine lines (base64 configs, port lists) stay out of the visible log. */
        fun loggable(l: String) = !(l.startsWith("config=") || l.startsWith("meta=") || l.startsWith("usage=") || l.startsWith("listen=") || l.length > 400)
    }

    fun addLog(line: String) {
        log.value = (log.value + line).takeLast(300)
    }

    private fun target() = Ssh.Target(server.host, server.port, server.user, server.hostKey, password?.takeIf { !server.keyInstalled || it.isNotEmpty() })

    suspend fun run(script: String, title: String): String = withContext(Dispatchers.IO) {
        addLog("— $title")
        val t = target()
        if (t.password.isNullOrEmpty() && !server.keyInstalled) throw Ssh.Failed("Введите пароль сервера: ключ программы ещё не на сервере.")
        val (code, output) = Ssh.run(t, keyDir, script, { l -> if (loggable(l)) addLog(l) })
        if (code != 0) throw Ssh.Failed("Команда на сервере завершилась с ошибкой (код $code) — подробности в журнале.")
        output
    }

    suspend fun probe() {
        val out = run(ServerScripts.probe, "проверяю сервер")
        val i = ServerInfo.parse(out)
        info.value = i
        cfg.value = MyServerConfig.from(i)
        version.value++
        addLog("Сервер: ${i.os} · ${i.arch}" + if (i.singBox.isNotEmpty()) " · sing-box ${i.singBox}" else " · sing-box не установлен")
    }

    /** Puts the app's key into authorized_keys (with the password), so later logins need nothing. */
    suspend fun installKey(): Boolean {
        val pub = Ssh.ensureAppKey(keyDir)
        val out = run(ServerScripts.installKey(pub), "кладу ключ программы на сервер")
        return out.contains("key=ok")
    }

    data class Plan(val ports: Map<String, Int>, val domain: String, val sni: String, val bbr: Boolean)

    /** What's wrong with the plan, in words for the user, or null. */
    fun check(p: Plan): String? {
        val i = info.value ?: return "Сначала подключитесь к серверу."
        val c = cfg.value ?: return "Сначала подключитесь к серверу."
        if (p.ports.values.any { it !in 1..65535 }) return "Неверный порт."
        val keys = p.ports.keys
        val acme = keys.any { it in acmeKeys }
        if ((acme || "tt" in keys) && !domainRx.matches(p.domain))
            return "Для TrustTunnel, NaiveProxy, Trojan, TUIC, AnyTLS и VLESS WS/gRPC нужен домен, например 1-2-3-4.sslip.io."
        if (keys.isEmpty() && !c.anyProtocol) return "Отметьте хотя бы один протокол."
        if ("anytls" in keys && i.singBox.isNotEmpty() && !MyServerConfig.supportsAnyTls(i.singBox))
            return "AnyTLS появился в sing-box 1.12, а на сервере стоит ${i.singBox}. Обновите sing-box в «Инструментах» или снимите AnyTLS."
        val wanted = mutableListOf<Pair<Int, String>>()
        for ((k, port) in p.ports) when (k) {
            "hy2", "tuic" -> wanted += port to "udp"
            "ss", "tt", "socks" -> { wanted += port to "tcp"; wanted += port to "udp" }
            else -> wanted += port to "tcp"
        }
        for ((port, proto) in wanted) {
            val owner = i.portOwner(port, proto) ?: continue
            if (owner.startsWith("trusttunnel") && i.ttManaged && "tt" in keys && port == p.ports["tt"]) continue
            return "Порт $port/$proto на сервере занят программой $owner. Выберите другой."
        }
        if (wanted.size != wanted.toSet().size) return "Два протокола на одном порту. Выберите разные порты."
        if (("vless" in keys || "shadowtls" in keys) && !domainRx.matches(p.sni)) return "Сайт для маскировки — доменное имя, например www.microsoft.com."
        if (acme && !c.needsAcme) i.portOwner(80, "tcp")?.let { return "Порт 80 на сервере занят программой $it — Let's Encrypt не сможет выдать сертификат." }
        return null
    }

    suspend fun apply(p: Plan) {
        val i = info.value!!
        val c = cfg.value!!
        val oldPorts = c.ports().toSet()
        val hadTt = c.has("tt")
        val hadAcme = c.needsAcme
        val keys = p.ports.keys
        val acme = keys.any { it in acmeKeys }
        val needKeys = "vless" in keys && c.vlessNeedsKeys
        val wantSingBox = keys.any { it != "tt" }
        var priv: String? = null
        var pub: String? = null
        if (wantSingBox && (i.singBox.isEmpty() || needKeys)) {
            val out = run(ServerScripts.ensureSingBox(needKeys), "ставлю sing-box")
            c.singBoxVersion = value(out, "singbox") ?: c.singBoxVersion
            priv = value(out, "reality_private"); pub = value(out, "reality_public")
            if (needKeys && (priv.isNullOrEmpty() || pub.isNullOrEmpty())) throw Ssh.Failed("sing-box не выдал ключи Reality — подробности в журнале.")
            if ("anytls" in keys && !MyServerConfig.supportsAnyTls(c.singBoxVersion)) throw Ssh.Failed("AnyTLS появился в sing-box 1.12, а на сервере стоит ${c.singBoxVersion}.")
        }
        for ((k, _) in protocols) {
            val port = p.ports[k]
            if (port == null) { c.remove(k); continue }
            when (k) {
                "vless" -> c.setVless(port, p.sni, priv, pub)
                "hy2" -> c.setHy2(port)
                "ss" -> c.setSs(port)
                "naive" -> c.setNaive(port, p.domain)
                "tt" -> c.setTt(port, p.domain)
                "trojan" -> c.setTrojan(port, p.domain)
                "tuic" -> c.setTuic(port, p.domain)
                "anytls" -> c.setAnyTls(port, p.domain)
                "vless_ws" -> c.setVlessWs(port, p.domain)
                "vless_grpc" -> c.setVlessGrpc(port, p.domain)
                "socks" -> c.setSocks(port)
                "shadowtls" -> c.setShadowTls(port, p.sni)
            }
        }
        if (c.users.isEmpty()) c.addUser("me")
        val newPorts = c.ports().toSet()
        val open = (newPorts - oldPorts).toMutableList()
        if (acme && !hadAcme) open += "80/tcp" // Let's Encrypt; never closed (others may need it)
        val singBox = c.hasSingBoxProtocols || i.singBox.isNotEmpty()
        run(applyScript(open, (oldPorts - newPorts).toList(), p.bbr, singBox), "применяю настройки на сервере")
        if (c.has("tt")) run(ServerScripts.ttApply(c.ttVpnToml(), c.ttHostsToml(), c.ttCredentialsToml(), c.ttCertPem, c.ttKeyPem), "ставлю и настраиваю TrustTunnel")
        else if (hadTt) run(ServerScripts.ttRemove, "выключаю TrustTunnel")
        probe()
        if (acme && !hadAcme) addLog("Сертификат Let's Encrypt придёт в течение минуты — до этого протоколы с сертификатом не подключатся.")
    }

    private fun applyScript(open: List<String>, close: List<String>, bbr: Boolean, singBox: Boolean): String {
        val c = cfg.value!!
        return ServerScripts.apply(c.configJson(), c.metaJson(), open, close, bbr, singBox, if (c.hasSingBoxProtocols) c.nftRules() else null)
    }

    /** Users changed: every protocol gets the new list (sing-box restarts, connections re-establish). */
    suspend fun applyUsers() {
        val c = cfg.value!!
        run(applyScript(emptyList(), emptyList(), false, c.hasSingBoxProtocols), "обновляю пользователей")
        if (c.has("tt")) run(ServerScripts.ttApply(c.ttVpnToml(), c.ttHostsToml(), c.ttCredentialsToml(), c.ttCertPem, c.ttKeyPem), "обновляю пользователей TrustTunnel")
        probe()
    }

    /** Only limits, dates or notes changed: nothing restarts, nobody is disconnected. */
    suspend fun applyLimits() {
        run(applyScript(emptyList(), emptyList(), false, false), "сохраняю лимиты")
        probe()
    }

    suspend fun resetUsage(u: MyUser) {
        run(ServerScripts.resetUsage(u.id), "обнуляю трафик ${u.name}")
        probe()
    }
}
