package com.slmtune.app.core

import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder

/**
 * Share links → servers. The same formats as the Windows version: vless, vmess, trojan, ss (with the
 * shadow-tls plugin), hysteria2/hy2, hysteria, tuic, anytls, naive+https/naive+quic, socks, wireguard.
 * TrustTunnel (tt://) and Xray-only VLESS (XHTTP, VLESS Encryption) are parsed too; they run on the separate
 * cores of the full build (see ExtraCores).
 */
object ShareLinks {
    val schemes = listOf(
        "vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", "hysteria://", "tuic://", "anytls://",
        "naive+https://", "naive+quic://", "socks5://", "socks://", "wireguard://", "wg://", "tt://",
    )

    /** Every link in a text (one per line or separated by spaces), and base64 subscription bodies. */
    fun findLinks(text: String): List<String> {
        var t = text.trim()
        if (schemes.none { t.contains(it, true) }) decodeBase64(t.replace("\n", "").replace("\r", ""))?.let { t = it }
        return t.split('\n', '\r', ' ', '\t').map { it.trim() }.filter { l -> schemes.any { l.startsWith(it, true) } }
    }

    fun decodeBase64(s: String): String? = runCatching {
        val clean = s.trim().replace('-', '+').replace('_', '/')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        String(Base64.getMimeDecoder().decode(padded), Charsets.UTF_8)
    }.getOrNull()?.takeIf { d -> d.isNotEmpty() && d.all { it.code >= 9 } }

    private fun dec(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    fun parseQuery(q: String?): Map<String, String> {
        if (q.isNullOrEmpty()) return emptyMap()
        return q.trimStart('?').split('&').filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            if (i < 0) dec(it) to "" else dec(it.substring(0, i)) to dec(it.substring(i + 1))
        }
    }

    private fun splitFragment(link: String): Pair<String, String> {
        val h = link.indexOf('#')
        return if (h < 0) "" to link else dec(link.substring(h + 1)).trim() to link.substring(0, h)
    }

    class Unsupported(message: String) : Exception(message)

    fun parse(link: String): Server {
        val lower = link.lowercase()
        return when {
            lower.startsWith("tt://") -> return ExtraCores.parseTt(link)
            lower.startsWith("vmess://") -> parseVmess(link)
            lower.startsWith("ss://") -> parseShadowsocks(link)
            lower.startsWith("socks5://") || lower.startsWith("socks://") -> parseSocks(link)
            lower.startsWith("wireguard://") || lower.startsWith("wg://") -> parseWireGuard(link)
            else -> parseGeneric(link)
        }.also { validate(it) }
    }

    private fun parseGeneric(original: String): Server {
        var link = original
        var kind: String
        var quic = false
        when {
            link.startsWith("hy2://", true) -> { kind = "hysteria2"; link = "hysteria2://" + link.substring(6) }
            link.startsWith("naive+https://", true) -> { kind = "naive"; link = "naive://" + link.substring(14) }
            link.startsWith("naive+quic://", true) -> { kind = "naive"; quic = true; link = "naive://" + link.substring(13) }
            else -> kind = link.substring(0, link.indexOf("://")).lowercase()
        }
        val (name, body) = splitFragment(link)
        val uri = URI(body.replace(" ", "%20"))
        val params = parseQuery(uri.rawQuery).toMutableMap()
        if (quic) params["quic"] = "1"
        val user = uri.rawUserInfo?.let { dec(it) } ?: ""
        val host = (uri.host ?: "").trim('[', ']')
        var userId = ""
        var password = ""
        when (kind) {
            "vless" -> userId = user
            "tuic", "naive" -> {
                val c = user.indexOf(':')
                userId = if (c >= 0) user.substring(0, c) else user
                password = if (c >= 0) user.substring(c + 1) else ""
            }
            "trojan", "hysteria2", "anytls" -> password = user
            "hysteria" -> password = params["auth"] ?: user
            else -> throw Unsupported("Неизвестный протокол: $kind")
        }
        return Server(
            name = name.ifBlank { "$kind $host" }, kind = kind, server = host,
            port = if (uri.port > 0) uri.port else 443, userId = userId, password = password, params = params, link = original,
        )
    }

    fun needsXray(params: Map<String, String>): Boolean {
        val net = (params["type"] ?: "").lowercase()
        val enc = (params["encryption"] ?: "none").lowercase()
        return net == "xhttp" || net == "splithttp" || (enc.isNotEmpty() && enc != "none")
    }

    /** vmess://base64({"v":"2","ps":"name","add":"host","port":"443","id":"uuid","net":"ws",...}) */
    private fun parseVmess(link: String): Server {
        val js = decodeBase64(link.substring(8).substringBefore('#')) ?: throw Unsupported("Ссылка VMess не читается.")
        val o = Store.json.parseToJsonElement(js) as JsonObject
        fun f(k: String) = o[k]?.jsonPrimitive?.content ?: ""
        val params = mutableMapOf(
            "type" to f("net").ifBlank { "tcp" }, "security" to f("tls"), "sni" to f("sni"),
            "host" to f("host"), "path" to f("path"), "aid" to f("aid"), "scy" to f("scy").ifBlank { "auto" },
            "fp" to f("fp"), "alpn" to f("alpn"),
        ).filterValues { it.isNotEmpty() }.toMutableMap()
        // gRPC keeps its service name in "path" in vmess JSON.
        if (params["type"] == "grpc" && f("path").isNotEmpty()) params["serviceName"] = f("path")
        return Server(
            name = f("ps").ifBlank { "vmess ${f("add")}" }, kind = "vmess", server = f("add"),
            port = f("port").toIntOrNull() ?: 443, userId = f("id"), params = params, link = link,
        )
    }

    /** ss://method:password@host:port or ss://base64(method:password)@host:port, optional ?plugin=… */
    private fun parseShadowsocks(link: String): Server {
        val (name, body0) = splitFragment(link)
        var body = body0.substring(5)
        if (!body.contains('@')) decodeBase64(body.substringBefore('?').substringBefore('/'))?.let {
            body = it + (if (body.contains('?')) "?" + body.substringAfter('?') else "")
        }
        val at = body.lastIndexOf('@')
        if (at < 0) throw Unsupported("Ссылка Shadowsocks не читается.")
        var userinfo = dec(body.substring(0, at))
        if (!userinfo.contains(':')) userinfo = decodeBase64(userinfo) ?: userinfo
        val method = userinfo.substringBefore(':')
        val password = userinfo.substringAfter(':')
        val uri = URI("ss://x@" + body.substring(at + 1).replace(" ", "%20"))
        val host = (uri.host ?: "").trim('[', ']')
        return Server(
            name = name.ifBlank { "ss $host" }, kind = "ss", server = host, port = if (uri.port > 0) uri.port else 8388,
            userId = method, password = password, params = parseQuery(uri.rawQuery), link = link,
        )
    }

    private fun parseSocks(link: String): Server {
        val (name, body) = splitFragment(link)
        val uri = URI("socks://" + body.substringAfter("://"))
        var info = uri.rawUserInfo?.let { dec(it) } ?: ""
        if (info.isNotEmpty() && !info.contains(':')) decodeBase64(info)?.takeIf { it.contains(':') }?.let { info = it }
        val host = (uri.host ?: "").trim('[', ']')
        return Server(
            name = name.ifBlank { "socks $host" }, kind = "socks", server = host, port = if (uri.port > 0) uri.port else 1080,
            userId = info.substringBefore(':', ""), password = info.substringAfter(':', ""), link = link,
        )
    }

    /** v2rayN style: wireguard://PRIVATEKEY@host:port?publickey=&address=&mtu=&reserved=#name */
    private fun parseWireGuard(link: String): Server {
        val (name, body) = splitFragment(link)
        val uri = URI("wg://" + body.substringAfter("://"))
        val host = (uri.host ?: "").trim('[', ']')
        return Server(
            name = name.ifBlank { "WireGuard $host" }, kind = "wireguard", server = host, port = if (uri.port > 0) uri.port else 51820,
            password = uri.rawUserInfo?.let { dec(it) } ?: "", params = parseQuery(uri.rawQuery), link = link,
        )
    }

    private fun validate(s: Server) {
        if (s.server.isBlank()) throw Unsupported("В ссылке нет адреса сервера.")
        if (s.port !in 1..65535) throw Unsupported("Неверный порт.")
        if (s.kind in setOf("vless", "vmess", "tuic") && runCatching { java.util.UUID.fromString(s.userId) }.isFailure)
            throw Unsupported("В ссылке нет корректного UUID.")
        if (s.kind in setOf("trojan", "hysteria2", "anytls", "ss", "tuic") && s.password.isEmpty())
            throw Unsupported("В ссылке нет пароля.")
    }

    fun describe(s: Server): String {
        val name = when (s.kind) {
            "vless" -> "VLESS"; "vmess" -> "VMess"; "trojan" -> "Trojan"; "ss" -> "Shadowsocks"; "hysteria2" -> "Hysteria2"
            "hysteria" -> "Hysteria"; "tuic" -> "TUIC"; "anytls" -> "AnyTLS"; "naive" -> "NaiveProxy"; "socks" -> "SOCKS5"
            "wireguard" -> if (s.param("warp") == "1") "Cloudflare WARP" else "WireGuard"
            "tt" -> "TrustTunnel · " + if (s.param("upstream") == "http3") "HTTP/3" else "HTTP/2"
            "tor" -> "Tor · " + s.param("transport", "snowflake")
            "group" -> "Группа · " + when (s.param("mode")) { "fallback" -> "резерв"; else -> "самый быстрый" }
            else -> s.kind
        }
        val parts = mutableListOf(name)
        val net = s.param("type")
        if (net.isNotEmpty() && net != "tcp" && net != "raw") parts += net
        if (s.param("security") == "reality") parts += "Reality"
        if (s.kind == "ss" && s.param("plugin").startsWith("shadow-tls")) parts += "ShadowTLS"
        if (s.kind == "vless" && s.param("encryption", "none") != "none") parts += "Encryption"
        return parts.joinToString(" · ")
    }

    /** Whether the protocol carries UDP (games, calls). */
    fun hasUdp(s: Server): Boolean = s.kind !in setOf("naive", "socks", "tor") && !(s.kind == "ss" && s.param("plugin").startsWith("shadow-tls"))
}
