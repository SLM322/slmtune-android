package com.slmtune.app.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Servers → sing-box outbounds (port of ShareLinks.BuildSingBoxOutbound from the Windows version).
 * The result is a set: the outbound tagged [tag] plus helpers it needs (a ShadowTLS layer, cascade hops,
 * group members), and WireGuard as an endpoint.
 */
object Outbounds {
    /** [backends]: server id → local SOCKS port of its separate core (Xray, Hysteria, Tor, TrustTunnel). */
    class Built(
        val outbounds: MutableList<JsonObject> = mutableListOf(),
        val endpoints: MutableList<JsonObject> = mutableListOf(),
        val backends: Map<String, Int> = emptyMap(),
    )

    private fun truthy(v: String) = v == "1" || v.equals("true", true)
    private fun csv(v: String) = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private fun arr(items: List<String>) = JsonArray(items.map { JsonPrimitive(it) })

    private fun tls(s: Server, force: Boolean): JsonObject? {
        val sec = s.param("security").lowercase()
        if (!force && sec !in setOf("tls", "reality", "xtls")) return null
        val sni = s.param("sni", s.param("peer", s.param("host")))
        val fp = s.param("fp")
        return buildJsonObject {
            put("enabled", true)
            if (sni.isNotEmpty()) put("server_name", sni)
            val pinned = s.param("cert")
            if (pinned.isNotEmpty()) put("certificate", pinned)
            else if (truthy(s.param("allowInsecure")) || truthy(s.param("insecure")) || truthy(s.param("allow_insecure"))) put("insecure", true)
            val alpn = s.param("alpn")
            if (alpn.isNotEmpty()) put("alpn", arr(csv(alpn)))
            else if (s.kind == "tuic") put("alpn", arr(listOf("h3")))
            if (sec == "reality" || fp.isNotEmpty()) putJsonObject("utls") { put("enabled", true); put("fingerprint", fp.ifEmpty { "chrome" }) }
            if (sec == "reality") putJsonObject("reality") {
                put("enabled", true); put("public_key", s.param("pbk")); put("short_id", s.param("sid"))
            }
        }
    }

    private fun transport(s: Server): JsonObject? {
        val host = s.param("host")
        val path = s.param("path")
        return when (s.param("type", "tcp").lowercase()) {
            "ws" -> buildJsonObject {
                put("type", "ws"); put("path", path.ifEmpty { "/" })
                putJsonObject("headers") { if (host.isNotEmpty()) put("Host", host) }
            }
            "grpc" -> buildJsonObject { put("type", "grpc"); put("service_name", s.param("serviceName", path)) }
            "http", "h2" -> buildJsonObject { put("type", "http"); put("path", path.ifEmpty { "/" }); put("host", arr(csv(host))) }
            "httpupgrade" -> buildJsonObject { put("type", "httpupgrade"); put("path", path.ifEmpty { "/" }); put("host", host) }
            else -> null
        }
    }

    /** One server; [tag] is what routing points at. */
    fun build(s: Server, tag: String, into: Built = Built(), all: List<Server> = emptyList()): Built {
        into.backends[s.id]?.let { port ->
            into.outbounds += buildJsonObject {
                put("type", "socks"); put("tag", tag); put("server", "127.0.0.1"); put("server_port", port); put("version", "5")
            }
            return into
        }
        when (s.kind) {
            "tt", "tor" -> error("${if (s.kind == "tt") "TrustTunnel" else "Tor"} работает только в полной версии SLMtune.")
            "vless" -> if (ShareLinks.needsXray(s.params)) error("VLESS XHTTP и VLESS Encryption работают через Xray — он есть в полной версии SLMtune.")
            "group" -> {
                // Members first, then a urltest over them. "fallback" keeps the chosen one until it fails.
                val members = s.members.mapNotNull { id -> all.firstOrNull { it.id == id && it.kind != "group" } }
                require(members.isNotEmpty()) { "В группе нет серверов." }
                val tags = members.mapIndexed { i, m -> "$tag-m$i".also { build(m, it, into, all) } }
                into.outbounds += buildJsonObject {
                    put("type", "urltest"); put("tag", tag); put("outbounds", arr(tags))
                    put("url", "https://www.gstatic.com/generate_204"); put("interval", "2m")
                    put("tolerance", if (s.param("mode") == "fallback") 5000 else 80)
                }
                return into
            }
            "wireguard" -> {
                into.endpoints += wireguard(s, tag)
                return into
            }
        }
        val o = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "tag" to JsonPrimitive(tag), "server" to JsonPrimitive(s.server), "server_port" to JsonPrimitive(s.port),
        )
        fun p(k: String, v: Any?) {
            when (v) {
                null -> {}
                is String -> o[k] = JsonPrimitive(v)
                is Int -> o[k] = JsonPrimitive(v)
                is Boolean -> o[k] = JsonPrimitive(v)
                is kotlinx.serialization.json.JsonElement -> o[k] = v
            }
        }
        var withTransport = false
        when (s.kind) {
            "socks" -> { p("type", "socks"); p("version", "5"); if (s.userId.isNotEmpty()) { p("username", s.userId); p("password", s.password) } }
            "hysteria" -> {
                p("type", "hysteria")
                p("up_mbps", s.param("upmbps").toIntOrNull()?.takeIf { it > 0 } ?: 20)
                p("down_mbps", s.param("downmbps").toIntOrNull()?.takeIf { it > 0 } ?: 100)
                if (s.password.isNotEmpty()) p("auth_str", s.password)
                if (s.param("obfsParam").isNotEmpty()) p("obfs", s.param("obfsParam"))
                p("tls", tls(s, true))
            }
            "vless" -> {
                p("type", "vless"); p("uuid", s.userId); if (s.param("flow").isNotEmpty()) p("flow", s.param("flow")); p("packet_encoding", "xudp")
                withTransport = true
            }
            "vmess" -> {
                p("type", "vmess"); p("uuid", s.userId); p("security", s.param("scy", "auto")); p("alter_id", s.param("aid").toIntOrNull() ?: 0)
                withTransport = true
            }
            "trojan" -> { p("type", "trojan"); p("password", s.password); withTransport = true }
            "ss" -> {
                p("type", "shadowsocks"); p("method", s.userId); p("password", s.password)
                val plugin = s.param("plugin")
                if (plugin.startsWith("shadow-tls", true)) {
                    // ShadowTLS v3: the TLS layer is its own outbound, Shadowsocks goes through it.
                    val opts = plugin.split(';').drop(1).mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
                    val stls = "$tag-stls"
                    into.outbounds += buildJsonObject {
                        put("type", "shadowtls"); put("tag", stls); put("server", s.server); put("server_port", s.port)
                        put("version", opts["version"]?.toIntOrNull() ?: 3); put("password", opts["password"] ?: "")
                        putJsonObject("tls") {
                            put("enabled", true); put("server_name", opts["host"] ?: "www.microsoft.com")
                            putJsonObject("utls") { put("enabled", true); put("fingerprint", "chrome") }
                        }
                    }
                    o.remove("server"); o.remove("server_port")
                    p("detour", stls)
                } else if (plugin.isNotEmpty()) {
                    val semi = plugin.indexOf(';')
                    p("plugin", if (semi < 0) plugin else plugin.substring(0, semi))
                    if (semi >= 0) p("plugin_opts", plugin.substring(semi + 1))
                }
            }
            "hysteria2" -> {
                p("type", "hysteria2"); p("password", s.password)
                val obfs = s.param("obfs")
                if (obfs.isNotEmpty()) p("obfs", buildJsonObject { put("type", obfs); put("password", s.param("obfs-password")) })
                val hop = s.param("mport", s.param("ports"))
                if (hop.isNotEmpty()) { o.remove("server_port"); p("server_ports", arr(csv(hop.replace('-', ':')))) }
                p("tls", tls(s, true))
            }
            "tuic" -> {
                p("type", "tuic"); p("uuid", s.userId); p("password", s.password)
                p("congestion_control", s.param("congestion_control", "bbr")); p("tls", tls(s, true))
            }
            "anytls" -> { p("type", "anytls"); p("password", s.password); p("tls", tls(s, true)) }
            "naive" -> {
                p("type", "naive"); p("username", s.userId); p("password", s.password)
                if (s.param("quic") == "1") p("quic", true)
                p("tls", buildJsonObject { put("enabled", true); put("server_name", s.param("sni", s.server)) })
            }
            else -> throw ShareLinks.Unsupported("Протокол ${s.kind} не поддерживается на Android.")
        }
        if (withTransport) {
            tls(s, false)?.let { p("tls", it) }
            transport(s)?.let { p("transport", it) }
        }
        into.outbounds += JsonObject(o)
        return into
    }

    /** WireGuard / WARP as a sing-box endpoint (1.11+). */
    private fun wireguard(s: Server, tag: String): JsonObject = buildJsonObject {
        put("type", "wireguard"); put("tag", tag)
        put("mtu", s.param("mtu").toIntOrNull() ?: 1280)
        put("address", arr(csv(s.param("address"))))
        put("private_key", s.password)
        putJsonArray("peers") {
            add(buildJsonObject {
                put("address", s.server); put("port", s.port)
                put("public_key", s.param("publickey"))
                if (s.param("presharedkey").isNotEmpty()) put("pre_shared_key", s.param("presharedkey"))
                put("allowed_ips", arr(csv(s.param("allowedips", "0.0.0.0/0, ::/0"))))
                put("persistent_keepalive_interval", s.param("keepalive").toIntOrNull() ?: 25)
                val reserved = csv(s.param("reserved")).mapNotNull { it.toIntOrNull() }
                if (reserved.size == 3) put("reserved", buildJsonArray { reserved.forEach { add(JsonPrimitive(it)) } })
            })
        }
    }

    /**
     * The server chain for [s]: its own "через" hops (each may have its own), or the global entry.
     * Returns entry first. Loops and missing servers end the chain; at most 4 hops.
     */
    fun hops(settings: Settings, s: Server): List<Server> {
        val chain = mutableListOf<Server>()
        val seen = mutableSetOf(s.id)
        var cur = s
        while (chain.size < 4) {
            var via = cur.viaId
            if (via.isEmpty() && cur === s) via = settings.cascadeEntryId
            if (via.isEmpty() || !seen.add(via)) break
            val relay = settings.servers.firstOrNull { it.id == via } ?: break
            chain.add(0, relay)
            cur = relay
        }
        return chain
    }

    /** The active server with its cascade: hop0 → hop1 → … → "proxy". */
    fun chain(settings: Settings, s: Server, backends: Map<String, Int> = emptyMap()): Built {
        val built = Built(backends = backends)
        val hops = hops(settings, s)
        // A separate core dials the server itself, so it can't go through another hop: only the first one.
        (hops.drop(1) + if (hops.isNotEmpty()) listOf(s) else emptyList()).firstOrNull { it.id in backends }?.let {
            error("«${it.title}» работает через отдельное ядро и в каскаде может быть только первым.")
        }
        hops.forEachIndexed { i, h -> build(h, "hop$i", built, settings.servers) }
        build(s, "proxy", built, settings.servers)
        // Each hop goes through the previous one.
        if (hops.isNotEmpty()) {
            fun withDetour(o: JsonObject, detour: String) = JsonObject(o + ("detour" to JsonPrimitive(detour)))
            for (i in 1..hops.size) {
                val tag = if (i == hops.size) "proxy" else "hop$i"
                val prev = "hop${i - 1}"
                built.outbounds.replaceAll { o -> if ((o["tag"] as? JsonPrimitive)?.content == tag && o["detour"] == null) withDetour(o, prev) else o }
                built.endpoints.replaceAll { o -> if ((o["tag"] as? JsonPrimitive)?.content == tag) withDetour(o, prev) else o }
            }
        }
        return built
    }
}
