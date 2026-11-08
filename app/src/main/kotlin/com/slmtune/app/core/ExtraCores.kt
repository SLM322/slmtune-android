package com.slmtune.app.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The cores of the full build that run as separate processes behind sing-box, each as a local SOCKS backend
 * (the same scheme as the Windows version): Xray for VLESS XHTTP and VLESS Encryption, the official Hysteria 2
 * client, Tor with bridges and the TrustTunnel client. sing-box keeps the VPN, the rules and DNS.
 */
object ExtraCores {
    const val XRAY = "xray"
    const val HYSTERIA = "hysteria"
    const val TOR = "tor"
    const val TRUSTTUNNEL = "trusttunnel"

    val titles = mapOf(XRAY to "Xray", HYSTERIA to "Hysteria 2", TOR to "Tor", TRUSTTUNNEL to "TrustTunnel")

    /** Which separate core a server needs, or null when sing-box handles it itself. */
    fun engine(s: Server, settings: Settings): String? = when {
        s.kind == "tt" -> TRUSTTUNNEL
        s.kind == "tor" -> TOR
        s.kind == "vless" && ShareLinks.needsXray(s.params) -> XRAY
        s.kind == "hysteria2" && settings.hysteriaOfficial -> HYSTERIA
        else -> null
    }

    /** Every server of the connection that needs its own core: the cascade and the group members. */
    fun backends(settings: Settings, server: Server): List<Server> {
        val list = Outbounds.hops(settings, server) + server
        val expanded = list.flatMap { s -> if (s.kind == "group") s.members.mapNotNull { id -> settings.servers.firstOrNull { it.id == id } } else listOf(s) }
        return expanded.filter { engine(it, settings) != null }.distinctBy { it.id }
    }

    // ------------------------------------------------------------------ Xray

    /** SOCKS inbound on 127.0.0.1:port, VLESS outbound from the link (port of ShareLinks.ToXrayConfig). */
    fun xrayConfig(s: Server, port: Int, address: String = s.server): String {
        var net = s.param("type", "tcp").lowercase()
        if (net == "splithttp") net = "xhttp"
        if (net == "tcp") net = "raw"
        val sec = s.param("security", "none").lowercase()
        // The address may be pre-resolved (Go programs on Android have no system DNS): keep the name for TLS.
        val sni = s.param("sni", s.param("host")).ifEmpty { s.server }
        val fp = s.param("fp", "chrome")
        val stream = buildJsonObject {
            put("network", net)
            put("security", sec)
            when (sec) {
                "reality" -> putJsonObject("realitySettings") {
                    put("serverName", sni); put("fingerprint", fp); put("publicKey", s.param("pbk"))
                    put("shortId", s.param("sid")); put("spiderX", s.param("spx", "/"))
                    s.param("pqv").takeIf { it.isNotEmpty() }?.let { put("mldsa65Verify", it) }
                }
                "tls" -> putJsonObject("tlsSettings") {
                    put("serverName", sni); put("fingerprint", fp)
                    put("allowInsecure", s.param("allowInsecure").let { it == "1" || it.equals("true", true) })
                    val alpn = s.param("alpn").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (alpn.isNotEmpty()) put("alpn", JsonArray(alpn.map { JsonPrimitive(it) }))
                }
            }
            when (net) {
                "xhttp" -> putJsonObject("xhttpSettings") {
                    put("path", s.param("path", "/")); put("mode", s.param("mode", "auto"))
                    if (s.param("host").isNotEmpty()) put("host", s.param("host"))
                    put("xPaddingBytes", "100-1000")
                    // "extra" from the link (JSON with downloadSettings, xmux…) goes in as is.
                    s.param("extra").takeIf { it.startsWith("{") }?.let { e ->
                        runCatching { Store.json.parseToJsonElement(e).jsonObject }.getOrNull()?.let { put("extra", it) }
                    }
                }
                "grpc" -> putJsonObject("grpcSettings") { put("serviceName", s.param("serviceName", s.param("path"))) }
                "ws" -> putJsonObject("wsSettings") {
                    put("path", s.param("path", "/")); putJsonObject("headers") { put("Host", s.param("host")) }
                }
                "httpupgrade" -> putJsonObject("httpupgradeSettings") { put("path", s.param("path", "/")); put("host", s.param("host")) }
            }
        }
        val user = buildJsonObject {
            put("id", s.userId)
            put("encryption", s.param("encryption", "none"))
            if (s.param("flow").isNotEmpty()) put("flow", s.param("flow"))
        }
        return Store.json.encodeToString(JsonObject.serializer(), buildJsonObject {
            putJsonObject("log") { put("loglevel", "warning") }
            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("tag", "socks-in"); put("listen", "127.0.0.1"); put("port", port); put("protocol", "socks")
                    putJsonObject("settings") { put("udp", true); put("auth", "noauth") }
                })
            }
            putJsonArray("outbounds") {
                add(buildJsonObject {
                    put("tag", "proxy"); put("protocol", "vless")
                    putJsonObject("settings") {
                        putJsonArray("vnext") {
                            add(buildJsonObject { put("address", address); put("port", s.port); putJsonArray("users") { add(user) } })
                        }
                    }
                    put("streamSettings", stream)
                })
            }
        })
    }

    // ------------------------------------------------------------------ Hysteria 2

    /** config for "hysteria client" (JSON); [caPath] is the pinned certificate of our own servers. */
    fun hysteriaConfig(s: Server, port: Int, address: String = s.server, caPath: String? = null): String {
        val host = if (address.contains(':')) "[$address]" else address
        val hop = s.param("mport", s.param("ports"))
        return Store.json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("server", if (hop.isNotEmpty()) "$host:${hop.replace(' ', ',')}" else "$host:${s.port}")
            put("auth", s.password)
            putJsonObject("socks5") { put("listen", "127.0.0.1:$port"); put("disableUDP", false) }
            put("fastOpen", true)
            put("lazy", false)
            putJsonObject("tls") {
                put("sni", s.param("sni", s.server))
                if (caPath != null) put("ca", caPath)
                else {
                    if (s.param("pinSHA256").isNotEmpty()) put("pinSHA256", s.param("pinSHA256"))
                    if (s.param("insecure") == "1" || s.param("insecure") == "true") put("insecure", true)
                }
            }
            if (s.param("obfs") == "salamander") putJsonObject("obfs") {
                put("type", "salamander"); putJsonObject("salamander") { put("password", s.param("obfs-password")) }
            }
            if (hop.isNotEmpty()) putJsonObject("transport") { putJsonObject("udp") { put("hopInterval", "30s") } }
        })
    }

    // ------------------------------------------------------------------ Tor

    val torTransports = listOf(
        "snowflake" to "Snowflake — через WebRTC-волонтёров, работает почти везде",
        "webtunnel" to "WebTunnel — выглядит как обычный HTTPS-сайт (мосты из @GetBridgesBot)",
        "obfs4" to "obfs4 — случайный шум, встроенные мосты",
        "meek" to "meek — через CDN Microsoft Azure, медленно, но надёжно",
        "none" to "Без мостов — если Tor у провайдера не заблокирован",
        "custom" to "Свои мосты (с bridges.torproject.org или из @GetBridgesBot)",
    )

    fun torProfile(transport: String, bridges: String = ""): Server = Server(
        kind = "tor", server = "tor", port = 9050,
        name = "Tor · " + (torTransports.firstOrNull { it.first == transport }?.second?.substringBefore(' ') ?: transport),
        params = mapOf("transport" to transport, "bridges" to bridges.replace("\r", "").trim()),
    )

    /**
     * torrc. The transport plugin lines and the built-in bridges come from the bundle's own pt_config.json;
     * [ptDir] is the folder with liblyrebird.so (the app's native library folder).
     */
    fun torrc(s: Server, port: Int, dataDir: String, ptDir: String, ptConfig: String): String {
        val sb = StringBuilder()
        sb.appendLine("SocksPort 127.0.0.1:$port")
        sb.appendLine("DataDirectory $dataDir")
        sb.appendLine("Log notice stdout")
        sb.appendLine("AvoidDiskWrites 1")
        sb.appendLine("ClientOnly 1")
        val transport = s.param("transport", "snowflake")
        if (transport == "none") return sb.toString()

        val root = Store.json.parseToJsonElement(ptConfig).jsonObject
        val plugins = root["pluggableTransports"]?.jsonObject?.values?.map { it.jsonPrimitive.content }
            ?.map { it.replace("\${pt_path}lyrebird", "$ptDir/liblyrebird.so").replace("\${pt_path}", "$ptDir/") }
            ?.filter { it.startsWith("ClientTransportPlugin") && it.contains("lyrebird") } ?: emptyList()
        val builtIn = root["bridges"]?.jsonObject?.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content }.filter { it.isNotEmpty() } } ?: emptyMap()
        val bridges = if (transport == "custom" || transport == "webtunnel")
            s.param("bridges").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { if (it.startsWith("Bridge ", true)) it.substring(7) else it }
        else builtIn[if (transport == "meek") "meek-azure" else transport] ?: builtIn[transport] ?: emptyList()
        if (bridges.isEmpty()) error(
            if (transport == "custom" || transport == "webtunnel") "Добавьте мосты Tor: их выдают на bridges.torproject.org и в Telegram-боте @GetBridgesBot."
            else "В сборке Tor нет встроенных мостов $transport. Укажите свои мосты."
        )
        sb.appendLine("UseBridges 1")
        plugins.forEach { sb.appendLine(it) }
        bridges.forEach { sb.appendLine("Bridge $it") }
        return sb.toString()
    }

    // ------------------------------------------------------------------ TrustTunnel

    /**
     * tt:// deep link: "tt://?" + base64url(TLV), tag and length are QUIC-style varints. Tags (checked against
     * the official setup_wizard): 0 version, 1 hostname, 2 address (repeated), 3 custom SNI, 4 has IPv6,
     * 5 username, 6 password, 7 skip verification, 8 certificate (DER, repeated), 9 upstream (1 HTTP/2, 2 HTTP/3),
     * 10 anti-DPI, 11 client random.
     */
    fun parseTt(link: String): Server {
        val name = link.substringAfter('#', "").let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.trim()
        val b64 = link.substringBefore('#').substringAfter("tt://").trimStart('?').trim()
        if (b64.length > 16 * 1024) throw ShareLinks.Unsupported("Ссылка слишком длинная.")
        val data = runCatching { Base64.getUrlDecoder().decode(b64.trimEnd('=')) }.getOrElse { throw ShareLinks.Unsupported("Ссылка TrustTunnel не читается.") }
        var i = 0
        fun varint(): Int {
            if (i >= data.size) throw ShareLinks.Unsupported("Ссылка TrustTunnel обрезана.")
            val first = data[i].toInt() and 0xff
            val len = 1 shl (first shr 6)
            if (i + len > data.size) throw ShareLinks.Unsupported("Ссылка TrustTunnel обрезана.")
            var v = (first and 0x3f).toLong()
            for (k in 1 until len) v = (v shl 8) or (data[i + k].toLong() and 0xff)
            i += len
            if (v > Int.MAX_VALUE) throw ShareLinks.Unsupported("Ссылка TrustTunnel повреждена.")
            return v.toInt()
        }
        var host = ""; var user = ""; var pass = ""; var sni = ""; var random = ""
        var ipv6 = true; var skip = false; var antiDpi = false; var upstream = "http2"
        val addresses = mutableListOf<String>()
        val certs = mutableListOf<ByteArray>()
        while (i < data.size) {
            val tag = varint()
            val len = varint()
            if (i + len > data.size) throw ShareLinks.Unsupported("Ссылка TrustTunnel обрезана.")
            val v = data.copyOfRange(i, i + len)
            i += len
            val str = String(v, Charsets.UTF_8)
            fun flag() = v.isNotEmpty() && v[0].toInt() != 0
            when (tag) {
                1 -> host = str
                2 -> addresses += str
                3 -> sni = str
                4 -> ipv6 = flag()
                5 -> user = str
                6 -> pass = str
                7 -> skip = flag()
                8 -> certs += v
                9 -> upstream = if (v.isNotEmpty() && v[0].toInt() == 2) "http3" else "http2"
                10 -> antiDpi = flag()
                11 -> random = str
            }
        }
        if (host.isEmpty() || addresses.isEmpty() || user.isEmpty()) throw ShareLinks.Unsupported("В ссылке TrustTunnel нет адреса или логина.")
        val pem = certs.joinToString("") { der ->
            "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END CERTIFICATE-----\n"
        }
        val first = addresses.first()
        return Server(
            kind = "tt", name = name.ifEmpty { host }, server = host,
            port = first.substringAfterLast(':').toIntOrNull() ?: 443, userId = user, password = pass,
            params = buildMap {
                put("addresses", addresses.joinToString("\n"))
                if (sni.isNotEmpty()) put("sni", sni)
                if (!ipv6) put("ipv6", "0")
                if (skip) put("skip", "1")
                if (pem.isNotEmpty()) put("cert", pem)
                put("upstream", upstream)
                if (antiDpi) put("antidpi", "1")
                if (random.isNotEmpty()) put("random", random)
            },
            link = link,
        )
    }

    /** The reverse of [parseTt], for "Поделиться". */
    fun ttLink(s: Server): String {
        val out = ByteArrayOutputStream()
        fun varint(v: Int) {
            when {
                v < 0x40 -> out.write(v)
                v < 0x4000 -> { out.write(0x40 or (v shr 8)); out.write(v and 0xff) }
                else -> { out.write(0x80 or (v shr 24)); out.write((v shr 16) and 0xff); out.write((v shr 8) and 0xff); out.write(v and 0xff) }
            }
        }
        fun put(tag: Int, v: ByteArray) { varint(tag); varint(v.size); out.write(v) }
        fun str(tag: Int, v: String) = put(tag, v.toByteArray(Charsets.UTF_8))
        put(0, byteArrayOf(1))
        str(1, s.server)
        str(5, s.userId)
        str(6, s.password)
        s.param("addresses").lines().filter { it.isNotBlank() }.forEach { str(2, it.trim()) }
        if (s.param("sni").isNotEmpty()) str(3, s.param("sni"))
        if (s.param("ipv6") == "0") put(4, byteArrayOf(0))
        if (s.param("skip") == "1") put(7, byteArrayOf(1))
        Regex("-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL).findAll(s.param("cert")).forEach { m ->
            runCatching { Base64.getMimeDecoder().decode(m.groupValues[1].trim()) }.getOrNull()?.let { put(8, it) }
        }
        if (s.param("upstream") == "http3") put(9, byteArrayOf(2))
        if (s.param("antidpi") == "1") put(10, byteArrayOf(1))
        if (s.param("random").isNotEmpty()) str(11, s.param("random"))
        val name = if (s.name.isNotEmpty() && s.name != s.server) "#" + java.net.URLEncoder.encode(s.name, "UTF-8").replace("+", "%20") else ""
        return "tt://?" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray()) + name
    }

    private fun tq(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    private fun tarr(v: List<String>) = v.joinToString(", ", "[", "]") { tq(it) }

    /**
     * trusttunnel_client.toml as a plain SOCKS backend (port of ConfigBuilder.BuildBackend). [addresses] may be
     * pre-resolved: the Linux build has no Android DNS.
     */
    fun ttConfig(s: Server, port: Int, addresses: List<String> = s.param("addresses").lines().filter { it.isNotBlank() }): String = buildString {
        appendLine("loglevel = \"info\"")
        appendLine("vpn_mode = \"general\"")
        appendLine("killswitch_enabled = false")
        appendLine("killswitch_allow_ports = []")
        appendLine("post_quantum_group_enabled = true")
        appendLine("exclusions = []")
        appendLine()
        appendLine("[endpoint]")
        appendLine("hostname = ${tq(s.server)}")
        appendLine("addresses = ${tarr(addresses)}")
        if (s.param("sni").isNotEmpty()) appendLine("custom_sni = ${tq(s.param("sni"))}")
        appendLine("has_ipv6 = ${s.param("ipv6") != "0"}")
        appendLine("username = ${tq(s.userId)}")
        appendLine("password = ${tq(s.password)}")
        appendLine("client_random = ${tq(s.param("random"))}")
        appendLine("skip_verification = ${s.param("skip") == "1"}")
        appendLine("certificate = ${tq(s.param("cert"))}")
        appendLine("upstream_protocol = ${tq(s.param("upstream", "http2"))}")
        appendLine("tls_profile = \"chrome\"")
        appendLine("anti_dpi = ${s.param("antidpi") == "1"}")
        appendLine("dns_upstreams = [\"tls://1.1.1.1\", \"tls://8.8.8.8\"]")
        appendLine()
        appendLine("[listener.socks]")
        appendLine("address = ${tq("127.0.0.1:$port")}")
    }
}
