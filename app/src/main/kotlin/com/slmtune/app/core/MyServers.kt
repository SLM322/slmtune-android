package com.slmtune.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import java.util.UUID

/** Where "Мой сервер" connects (the same fields as the Windows version). The password is never stored. */
@Serializable
data class MyServer(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "root",
    /** The pinned host key: "ssh-ed25519 AAAA…". Empty until the user confirmed the fingerprint. */
    val hostKey: String = "",
    /** The app's key is in the server's authorized_keys: no password needed any more. */
    val keyInstalled: Boolean = false,
) {
    val title: String get() = name.ifBlank { host }
}

/**
 * Bash scripts sent over SSH: copied verbatim from the Windows version (the .sh files in assets/server, generated from
 * ServerSetup.cs) so both apps set up a server exactly the same way. "{{…}}" are the C# template holes.
 */
object ServerScripts {
    /** Reads assets/server/<name>.sh; set by the app (assets) and by the tests (the source folder). */
    var source: (String) -> String = { error("ServerScripts.source not set") }

    private fun s(name: String) = source(name)
    fun b64(v: String): String = Base64.getEncoder().encodeToString(v.toByteArray(Charsets.UTF_8))

    val probe get() = s("Probe")
    val restart get() = s("Restart")
    val updateSingBox get() = s("UpdateSingBox")
    val harden get() = s("Pkg") + s("Harden")
    val swapAndTime get() = s("SwapAndTime")
    val autoUpdates get() = s("Pkg") + s("AutoUpdates")
    val netCheck get() = s("NetCheck")
    val ttRemove get() = s("TtRemove")
    const val REBOOT = "set +e\necho \"[slm] rebooting in 2 seconds\"\nnohup sh -c 'sleep 2; systemctl reboot' >/dev/null 2>&1 &\necho \"tools=ok\"\n"
    const val LOGS = "systemctl --no-pager status sing-box 2>&1 | head -12; journalctl -u sing-box -n 30 --no-pager 2>&1; " +
        "if [ -f /opt/trusttunnel/slm-managed ]; then systemctl --no-pager status trusttunnel 2>&1 | head -8; journalctl -u trusttunnel -n 20 --no-pager 2>&1; fi"

    fun ensureSingBox(realityKeys: Boolean) = s("EnsureSingBox") +
        if (realityKeys) "\nsing-box generate reality-keypair | sed -e 's/^PrivateKey: */reality_private=/' -e 's/^PublicKey: */reality_public=/'\n" else "\n"

    fun installKey(publicKey: String) = s("InstallKey").replace("{{publicKey}}", publicKey)

    fun resetUsage(id: Int) = "set -e\npython3 /etc/sing-box/slm-usage.py reset $id\necho \"reset=ok\"\n"

    fun accounting(nft: String) = s("Accounting")
        .replace("{{B64(UsageScript)}}", b64(s("UsageScript").trimEnd('\n') + "\n"))
        .replace("{{B64(nft)}}", b64(nft))
        .replace("{{B64(AcctUnit)}}", b64(s("AcctUnit").trimEnd('\n') + "\n"))
        .replace("{{B64(UsageUnit)}}", b64(s("UsageUnit").trimEnd('\n') + "\n"))
        .replace("{{B64(UsageTimer)}}", b64(s("UsageTimer").trimEnd('\n') + "\n"))

    /** Uploads the config (checked by sing-box first), restarts it, opens/closes firewall ports, BBR, accounting. */
    fun apply(configJson: String, metaJson: String, open: List<String>, close: List<String>, bbr: Boolean, singBox: Boolean = true, nft: String? = null): String {
        val sb = StringBuilder(s("Apply"))
        if (singBox) sb.append(s("Apply2").replace("{{B64(configJson)}}", b64(configJson)))
        sb.append(s("Apply3").replace("{{B64(metaJson)}}", b64(metaJson)))
        close.forEach { sb.append("closep '$it'\n") }
        open.forEach { sb.append("openp '$it'\n") }
        sb.append("if [ \"\$FWD\" = running ]; then firewall-cmd -q --reload; fi\n")
        if (bbr) sb.append(s("Apply4"))
        if (nft != null) sb.append(accounting(nft))
        sb.append("echo \"apply=ok\"\n")
        return sb.toString()
    }

    fun ttApply(vpn: String, hosts: String, creds: String, cert: String, key: String) = s("TtApply")
        .replace("{{B64(certPem)}}", b64(cert)).replace("{{B64(keyPem)}}", b64(key))
        .replace("{{B64(vpnToml)}}", b64(vpn)).replace("{{B64(hostsToml)}}", b64(hosts))
        .replace("{{B64(credentialsToml)}}", b64(creds))
}

/** What the server reported (ServerScripts.probe). */
class ServerInfo {
    var os = ""; var arch = ""; var singBox = ""; var singBoxActive = ""; var congestion = ""; var ufw = ""; var firewalld = ""; var ttActive = ""
    var ttInstalled = false; var ttManaged = false; var accounting = false
    val listening = mutableListOf<Triple<String, Int, String>>()
    var config: JsonObject? = null; var meta: JsonObject? = null; var usage: JsonObject? = null
    var load = 0.0; var cpus = 0
    var memUsed = 0L; var memTotal = 0L; var diskUsed = 0L; var diskTotal = 0L; var uptimeSec = 0L

    companion object {
        fun parse(output: String): ServerInfo {
            val info = ServerInfo()
            fun obj(v: String) = runCatching { Store.json.parseToJsonElement(String(Base64.getDecoder().decode(v), Charsets.UTF_8)).jsonObject }.getOrNull()
            fun pair(v: String): Pair<Long, Long> = v.split('/').let { p -> if (p.size == 2) (p[0].toLongOrNull() ?: 0L) to (p[1].toLongOrNull() ?: 0L) else 0L to 0L }
            for (raw in output.split('\n')) {
                val line = raw.trimEnd('\r')
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val k = line.substring(0, eq)
                val v = line.substring(eq + 1).trim()
                when (k) {
                    "os" -> info.os = v; "arch" -> info.arch = v; "singbox" -> info.singBox = v; "singbox_active" -> info.singBoxActive = v
                    "cc" -> info.congestion = v; "ufw" -> info.ufw = v; "firewalld" -> info.firewalld = v
                    "tt_installed" -> info.ttInstalled = v == "1"; "tt_managed" -> info.ttManaged = v == "1"; "tt_active" -> info.ttActive = v
                    "config" -> info.config = obj(v); "meta" -> info.meta = obj(v); "usage" -> info.usage = obj(v)
                    "acct" -> info.accounting = v == "on"
                    "load" -> info.load = v.toDoubleOrNull() ?: 0.0; "cpus" -> info.cpus = v.toIntOrNull() ?: 0
                    "mem" -> pair(v).let { info.memUsed = it.first; info.memTotal = it.second }
                    "disk" -> pair(v).let { info.diskUsed = it.first; info.diskTotal = it.second }
                    "uptime" -> info.uptimeSec = v.toLongOrNull() ?: 0
                    "listen" -> {
                        // "tcp 0.0.0.0:443 users:(("sing-box",pid=1,fd=3))"
                        val parts = v.split(' ', limit = 3).filter { it.isNotEmpty() }
                        if (parts.size < 2) continue
                        val port = parts[1].substringAfterLast(':').toIntOrNull() ?: continue
                        val proc = if (parts.size > 2 && parts[2].contains('"')) parts[2].substringAfter('"').substringBefore('"') else ""
                        val proto = if (parts[0].startsWith("udp")) "udp" else "tcp"
                        if (info.listening.none { it.first == proto && it.second == port }) info.listening += Triple(proto, port, proc)
                    }
                }
            }
            return info
        }
    }

    /** Who holds the port, unless it is our own sing-box (which is about to be reconfigured anyway). */
    fun portOwner(port: Int, proto: String): String? {
        val l = listening.firstOrNull { it.second == port && it.first == proto } ?: return null
        return if (l.third == "sing-box") null else l.third.ifEmpty { "?" }
    }
}

data class MyUser(
    var name: String = "", var uuid: String = "", var pass: String = "", var ssKey: String = "",
    /** Stable number: the user's packet mark and counter name on the server. */
    var id: Int = 0,
    var off: Boolean = false,
    /** Traffic limit in bytes, 0 = none. */
    var limit: Long = 0,
    /** Last valid day "2026-11-30" (UTC), "" = no end. */
    var expires: String = "",
    var note: String = "",
) {
    companion object {
        private val rnd = SecureRandom()
        fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
        fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun create(name: String, id: Int) = MyUser(name = name, id = id, uuid = UUID.randomUUID().toString(),
            pass = b64url(bytes(18)), ssKey = Base64.getEncoder().encodeToString(bytes(16)))
    }
}

/**
 * Our part of the server's sing-box config (port of MyServerConfig from the Windows version). The truth
 * lives on the server: /etc/sing-box/slm-meta.json (users, keys, ports) → the inbounds tagged slm-*;
 * anything else in config.json is kept as it is.
 */
class MyServerConfig {
    companion object {
        const val VLESS = "slm-vless"; const val HY2 = "slm-hy2"; const val SS = "slm-ss"; const val NAIVE = "slm-naive"
        const val TROJAN = "slm-trojan"; const val TUIC = "slm-tuic"; const val ANYTLS = "slm-anytls"
        const val VLESS_WS = "slm-vless-ws"; const val VLESS_GRPC = "slm-vless-grpc"; const val SOCKS = "slm-socks"
        const val STLS = "slm-stls"; const val STLS_INNER = "slm-stls-ss"
        private val ourTags = setOf(VLESS, HY2, SS, NAIVE, TROJAN, TUIC, ANYTLS, VLESS_WS, VLESS_GRPC, SOCKS, STLS, STLS_INNER)
        const val USER_OUT = "slm-u-"
        const val MARK_BASE = 21000
        const val SS_METHOD = "2022-blake3-aes-128-gcm"

        /** Meta keys of every protocol, in the Windows order. */
        val protoKeys = listOf("vless", "hy2", "ss", "naive", "tt", "trojan", "tuic", "anytls", "vless_ws", "vless_grpc", "socks", "shadowtls")

        fun from(info: ServerInfo): MyServerConfig {
            val c = MyServerConfig()
            c.base = info.config ?: JsonObject(emptyMap())
            info.meta?.let { m ->
                m["users"]?.let { it as? JsonArray }?.forEach { u ->
                    val o = u as? JsonObject ?: return@forEach
                    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
                    c.users += MyUser(str("name"), str("uuid"), str("pass"), str("ss"), (o["id"] as? JsonPrimitive)?.intOrNull ?: 0,
                        (o["off"] as? JsonPrimitive)?.booleanOrNull ?: false, (o["limit"] as? JsonPrimitive)?.longOrNull ?: 0, str("exp"), str("note"))
                }
                c.users.filter { it.id <= 0 }.forEach { it.id = c.nextUserId() }
                for (k in protoKeys) (m[k] as? JsonObject)?.let { c.proto[k] = it.toMutableMap() }
            }
            c.usage = info.usage
            if (!info.ttManaged) c.proto.remove("tt")
            c.singBoxVersion = info.singBox
            return c
        }

        fun mark(u: MyUser) = MARK_BASE + u.id

        /** A free name that points at the IP (sslip.io), or the host itself when it is already a domain. */
        fun defaultDomain(host: String): String =
            if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)) host.replace('.', '-') + ".sslip.io" else host

        fun supportsAnyTls(version: String) = ver(version)?.let { it >= 1 * 1000 + 12 } ?: true
        private fun ver(v: String): Int? = v.split('-')[0].split('.').let { p -> if (p.size >= 2) (p[0].toIntOrNull() ?: return null) * 1000 + (p[1].toIntOrNull() ?: return null) else null }
        private fun needsInlineAcme(v: String) = ver(v)?.let { it < 1014 } ?: false

        /** A self-signed ECDSA P-256 certificate (Hysteria2, TrustTunnel); the client pins exactly this one. */
        fun selfSigned(cn: String): Pair<String, String> {
            val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val kp = kpg.generateKeyPair()
            val name = org.bouncycastle.asn1.x500.X500Name("CN=$cn")
            val now = System.currentTimeMillis()
            val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                name, BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE), Date(now - 86_400_000L), Date(now + 3650L * 86_400_000L), name, kp.public,
            )
            builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false,
                org.bouncycastle.asn1.x509.GeneralNames(org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.dNSName, cn)))
            val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
            val der = builder.build(signer).encoded
            return pem("CERTIFICATE", der) to pem("PRIVATE KEY", kp.private.encoded)
        }

        fun pem(type: String, der: ByteArray) =
            "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

        fun certSha256Hex(certPem: String): String {
            val b64 = certPem.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END").filterNot { it.isWhitespace() }
            return MyUser.hex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(b64)))
        }

        private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
    }

    val users = mutableListOf<MyUser>()
    /** Installed protocols by meta key ("vless", "hy2", …); absent = not installed. */
    val proto = linkedMapOf<String, MutableMap<String, JsonElement>>()
    private var usage: JsonObject? = null
    var singBoxVersion = ""
    private var base = JsonObject(emptyMap())

    private fun s(k: String, f: String) = (proto[k]?.get(f) as? JsonPrimitive)?.contentOrNull ?: ""
    fun port(k: String): Int = (proto[k]?.get("port") as? JsonPrimitive)?.intOrNull ?: defaultPort(k)
    fun has(k: String) = proto.containsKey(k)

    fun defaultPort(k: String) = when (k) {
        "vless", "hy2", "naive" -> 443; "ss" -> 8388; "tt" -> 9443; "trojan", "tuic" -> 8443; "anytls" -> 2096
        "vless_ws" -> 2087; "vless_grpc" -> 2053; "socks" -> 1080; "shadowtls" -> 8444; else -> 443
    }

    val hasSingBoxProtocols get() = proto.keys.any { it != "tt" }
    val anyProtocol get() = proto.isNotEmpty()
    val needsAcme get() = listOf("naive", "trojan", "tuic", "anytls", "vless_ws", "vless_grpc").any { has(it) }
    fun domain(host: String) = listOf("tt", "naive", "trojan", "tuic", "anytls", "vless_ws", "vless_grpc").map { s(it, "domain") }.firstOrNull { it.isNotEmpty() } ?: defaultDomain(host)
    val realitySni get() = s("vless", "sni").ifEmpty { "www.microsoft.com" }
    val shadowTlsSni get() = s("shadowtls", "sni").ifEmpty { "www.microsoft.com" }
    val active get() = users.filter { !it.off }
    fun nextUserId() = (users.maxOfOrNull { it.id } ?: 0).coerceAtLeast(0) + 1

    /** Bytes the server counted for the user (sing-box protocols only). */
    fun usage(u: MyUser): Pair<Long, Long> {
        val e = usage?.get("users")?.let { it as? JsonObject }?.get(u.id.toString()) as? JsonObject
        fun n(k: String) = (e?.get(k) as? JsonPrimitive)?.longOrNull ?: 0
        return n("up") to n("down")
    }

    /** "off" by hand, "expired" / "limit" by the server, otherwise "on". */
    fun state(u: MyUser, todayUtc: String): String {
        if (u.off) return "off"
        if (u.expires.isNotEmpty() && todayUtc > u.expires) return "expired"
        val (up, down) = usage(u)
        return if (u.limit > 0 && up + down >= u.limit) "limit" else "on"
    }

    /** Firewall entries ("443/tcp") of what is installed. */
    fun ports(): List<String> = buildList {
        if (has("vless")) add("${port("vless")}/tcp")
        if (has("hy2")) add("${port("hy2")}/udp")
        if (has("ss")) { add("${port("ss")}/tcp"); add("${port("ss")}/udp") }
        if (has("naive")) add("${port("naive")}/tcp")
        if (has("tt")) { add("${port("tt")}/tcp"); add("${port("tt")}/udp") }
        if (has("trojan")) add("${port("trojan")}/tcp")
        if (has("tuic")) add("${port("tuic")}/udp")
        if (has("anytls")) add("${port("anytls")}/tcp")
        if (has("vless_ws")) add("${port("vless_ws")}/tcp")
        if (has("vless_grpc")) add("${port("vless_grpc")}/tcp")
        if (has("socks")) { add("${port("socks")}/tcp"); add("${port("socks")}/udp") }
        if (has("shadowtls")) add("${port("shadowtls")}/tcp")
    }

    private fun obj(k: String) = proto.getOrPut(k) { mutableMapOf() }
    private fun withDomain(k: String, port: Int, domain: String) = obj(k).apply { put("port", JsonPrimitive(port)); put("domain", JsonPrimitive(domain)) }

    fun remove(k: String) { proto.remove(k) }
    fun setTrojan(port: Int, domain: String) { withDomain("trojan", port, domain) }
    fun setTuic(port: Int, domain: String) { withDomain("tuic", port, domain) }
    fun setAnyTls(port: Int, domain: String) { withDomain("anytls", port, domain) }
    fun setNaive(port: Int, domain: String) { withDomain("naive", port, domain) }
    fun setVlessWs(port: Int, domain: String) {
        val o = withDomain("vless_ws", port, domain)
        if (s("vless_ws", "path").isEmpty()) o["path"] = JsonPrimitive("/" + MyUser.hex(MyUser.bytes(6)))
    }
    fun setVlessGrpc(port: Int, domain: String) {
        val o = withDomain("vless_grpc", port, domain)
        if (s("vless_grpc", "service").isEmpty()) o["service"] = JsonPrimitive("g" + MyUser.hex(MyUser.bytes(5)))
    }
    fun setShadowTls(port: Int, sni: String) {
        val o = proto.getOrPut("shadowtls") {
            mutableMapOf("key" to JsonPrimitive(Base64.getEncoder().encodeToString(MyUser.bytes(16))), "inner" to JsonPrimitive(20000 + SecureRandom().nextInt(20000)))
        }
        o["port"] = JsonPrimitive(port); o["sni"] = JsonPrimitive(sni)
    }
    fun setSocks(port: Int) { obj("socks")["port"] = JsonPrimitive(port) }
    fun setTt(port: Int, domain: String) {
        if (!has("tt") || s("tt", "domain") != domain) {
            val (cert, key) = selfSigned(domain)
            proto["tt"] = mutableMapOf("cert" to JsonPrimitive(cert), "key" to JsonPrimitive(key))
        }
        obj("tt")["port"] = JsonPrimitive(port); obj("tt")["domain"] = JsonPrimitive(domain)
    }
    fun setVless(port: Int, sni: String, privateKey: String?, publicKey: String?) {
        val o = proto.getOrPut("vless") { mutableMapOf("sid" to JsonPrimitive(MyUser.hex(MyUser.bytes(8)))) }
        o["port"] = JsonPrimitive(port); o["sni"] = JsonPrimitive(sni)
        if (privateKey != null) o["priv"] = JsonPrimitive(privateKey)
        if (publicKey != null) o["pub"] = JsonPrimitive(publicKey)
    }
    val vlessNeedsKeys get() = !has("vless") || s("vless", "priv").isEmpty()
    fun setHy2(port: Int) {
        if (!has("hy2")) {
            val sni = "www.bing.com"
            val (cert, key) = selfSigned(sni)
            proto["hy2"] = mutableMapOf("sni" to JsonPrimitive(sni), "cert" to JsonPrimitive(cert), "key" to JsonPrimitive(key), "obfs" to JsonPrimitive(MyUser.b64url(MyUser.bytes(18))))
        }
        obj("hy2")["port"] = JsonPrimitive(port)
    }
    fun setSs(port: Int) {
        val o = proto.getOrPut("ss") { mutableMapOf("key" to JsonPrimitive(Base64.getEncoder().encodeToString(MyUser.bytes(16)))) }
        o["port"] = JsonPrimitive(port)
    }

    fun addUser(name: String) {
        require(users.none { it.name.equals(name, true) }) { "Такой пользователь уже есть." }
        users += MyUser.create(name, nextUserId())
    }

    // ---- TrustTunnel endpoint files ----
    fun ttVpnToml() = ServerScripts.source("TtVpnToml").replace("{{TtPort}}", port("tt").toString())
    fun ttHostsToml() = ServerScripts.source("TtHostsToml").replace("{{S(Tt!, \"domain\")}}", s("tt", "domain"))
    fun ttCredentialsToml() = active.joinToString("") { "[[client]]\nusername = ${tq(it.name)}\npassword = ${tq(it.pass)}\n\n" }
    val ttCertPem get() = s("tt", "cert")
    val ttKeyPem get() = s("tt", "key")
    private fun tq(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The nftables ruleset behind traffic accounting (counted per packet mark, "blocked" set dropped). */
    fun nftRules(): String = buildString {
        append("table inet slm\ndelete table inet slm\ntable inet slm {\n\tset blocked { type mark; }\n")
        users.forEach { append("\tcounter up_${it.id} { packets 0 bytes 0 }\n\tcounter down_${it.id} { packets 0 bytes 0 }\n") }
        append("\tchain out {\n\t\ttype filter hook output priority 0; policy accept;\n")
        append("\t\tmeta mark $MARK_BASE-${MARK_BASE + 8999} ct mark set meta mark\n\t\tmeta mark @blocked drop\n")
        users.forEach { append("\t\tmeta mark ${mark(it)} counter name \"up_${it.id}\"\n") }
        append("\t}\n\tchain in {\n\t\ttype filter hook input priority 0; policy accept;\n\t\tct mark @blocked drop\n")
        users.forEach { append("\t\tct mark ${mark(it)} counter name \"down_${it.id}\"\n") }
        append("\t}\n}\n")
    }

    fun metaJson(): String = Store.json.encodeToString(JsonObject.serializer(), buildJsonObject {
        putJsonArray("users") {
            users.forEach { u ->
                add(buildJsonObject {
                    put("name", u.name); put("uuid", u.uuid); put("pass", u.pass); put("ss", u.ssKey)
                    put("id", u.id); put("off", u.off); put("limit", u.limit); put("exp", u.expires); put("note", u.note)
                })
            }
        }
        for (k in listOf("trojan", "tuic", "anytls", "vless_ws", "vless_grpc", "socks", "shadowtls", "vless", "hy2", "ss", "naive", "tt"))
            proto[k]?.let { put(k, JsonObject(it)) }
    })

    /** The whole config.json: foreign inbounds kept, ours rebuilt from the meta. */
    fun configJson(): String {
        val cfg = base.toMutableMap()
        if (cfg["log"] == null) cfg["log"] = buildJsonObject { put("level", "warn"); put("timestamp", true) }
        fun tagOf(e: JsonElement?) = ((e as? JsonObject)?.get("tag") as? JsonPrimitive)?.contentOrNull
        val inbounds = (cfg["inbounds"] as? JsonArray ?: JsonArray(emptyList())).filter { tagOf(it) !in ourTags }.toMutableList()
        fun usersOf(f: (MyUser) -> JsonObject) = JsonArray(active.map(f))
        fun acmeTls(domain: String, vararg alpn: String) = buildJsonObject {
            put("enabled", true); put("server_name", domain)
            if (alpn.isNotEmpty()) put("alpn", JsonArray(alpn.map { JsonPrimitive(it) }))
            val acme = buildJsonObject {
                if (!needsInlineAcme(singBoxVersion)) put("type", "acme")
                putJsonArray("domain") { add(JsonPrimitive(domain)) }
                put("data_directory", "/var/lib/sing-box/acme")
            }
            if (needsInlineAcme(singBoxVersion)) put("acme", acme) else put("certificate_provider", acme)
        }
        fun inbound(type: String, tag: String, port: Int, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("type", type); put("tag", tag); put("listen", "::"); put("listen_port", port); body()
        }
        if (has("vless")) inbounds += inbound("vless", VLESS, port("vless")) {
            put("users", usersOf { u -> buildJsonObject { put("name", u.name); put("uuid", u.uuid); put("flow", "xtls-rprx-vision") } })
            putJsonObject("tls") {
                put("enabled", true); put("server_name", realitySni)
                putJsonObject("reality") {
                    put("enabled", true)
                    putJsonObject("handshake") { put("server", realitySni); put("server_port", 443) }
                    put("private_key", s("vless", "priv"))
                    putJsonArray("short_id") { add(JsonPrimitive(s("vless", "sid"))) }
                }
            }
        }
        if (has("hy2")) inbounds += inbound("hysteria2", HY2, port("hy2")) {
            put("users", usersOf { u -> buildJsonObject { put("name", u.name); put("password", u.pass) } })
            putJsonObject("obfs") { put("type", "salamander"); put("password", s("hy2", "obfs")) }
            putJsonObject("tls") {
                put("enabled", true); put("server_name", s("hy2", "sni")); putJsonArray("alpn") { add(JsonPrimitive("h3")) }
                put("certificate", s("hy2", "cert")); put("key", s("hy2", "key"))
            }
        }
        if (has("ss")) inbounds += inbound("shadowsocks", SS, port("ss")) {
            put("method", SS_METHOD); put("password", s("ss", "key"))
            put("users", usersOf { u -> buildJsonObject { put("name", u.name); put("password", u.ssKey) } })
        }
        if (has("naive")) inbounds += inbound("naive", NAIVE, port("naive")) {
            put("users", usersOf { u -> buildJsonObject { put("username", u.name); put("password", u.pass) } })
            put("tls", acmeTls(s("naive", "domain")))
        }
        if (has("trojan")) inbounds += inbound("trojan", TROJAN, port("trojan")) {
            put("users", usersOf { u -> buildJsonObject { put("name", u.name); put("password", u.pass) } })
            put("tls", acmeTls(s("trojan", "domain")))
        }
        if (has("tuic")) inbounds += inbound("tuic", TUIC, port("tuic")) {
            put("users", usersOf { u -> buildJsonObject { put("name", u.name); put("uuid", u.uuid); put("password", u.pass) } })
            put("congestion_control", "bbr")
            put("tls", acmeTls(s("tuic", "domain"), "h3"))
        }
        if (has("anytls")) inbounds += inbound("anytls", ANYTLS, port("anytls")) {
            put("users", usersOf { u -> buildJsonObject { put("name", u.name); put("password", u.pass) } })
            put("tls", acmeTls(s("anytls", "domain")))
        }
        val vlessUsers = { usersOf { u -> buildJsonObject { put("name", u.name); put("uuid", u.uuid) } } }
        if (has("vless_ws")) inbounds += inbound("vless", VLESS_WS, port("vless_ws")) {
            put("users", vlessUsers())
            putJsonObject("transport") { put("type", "ws"); put("path", s("vless_ws", "path")) }
            put("tls", acmeTls(s("vless_ws", "domain"), "http/1.1"))
        }
        if (has("vless_grpc")) inbounds += inbound("vless", VLESS_GRPC, port("vless_grpc")) {
            put("users", vlessUsers())
            putJsonObject("transport") { put("type", "grpc"); put("service_name", s("vless_grpc", "service")) }
            put("tls", acmeTls(s("vless_grpc", "domain"), "h2"))
        }
        if (has("shadowtls")) {
            inbounds += inbound("shadowtls", STLS, port("shadowtls")) {
                put("version", 3)
                put("users", usersOf { u -> buildJsonObject { put("name", u.name); put("password", u.pass) } })
                putJsonObject("handshake") { put("server", shadowTlsSni); put("server_port", 443) }
                put("strict_mode", true)
                put("detour", STLS_INNER)
            }
            inbounds += buildJsonObject {
                put("type", "shadowsocks"); put("tag", STLS_INNER); put("listen", "127.0.0.1")
                put("listen_port", (proto["shadowtls"]?.get("inner") as? JsonPrimitive)?.intOrNull ?: 28388)
                put("method", SS_METHOD); put("password", s("shadowtls", "key"))
                put("users", usersOf { u -> buildJsonObject { put("name", u.name); put("password", u.ssKey) } })
            }
        }
        if (has("socks")) inbounds += inbound("socks", SOCKS, port("socks")) {
            put("users", usersOf { u -> buildJsonObject { put("username", u.name); put("password", u.pass) } })
        }
        cfg["inbounds"] = JsonArray(inbounds)

        // Outbounds: foreign ones kept, ours ("slm-u-ID", one per user, each with its packet mark) rebuilt.
        fun ours(e: JsonElement?, key: String) = ((e as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull?.startsWith(USER_OUT) == true
        val outbounds = (cfg["outbounds"] as? JsonArray ?: JsonArray(emptyList())).filter { !ours(it, "tag") }.toMutableList()
        if (outbounds.isEmpty()) outbounds += buildJsonObject { put("type", "direct"); put("tag", "direct") }
        val route = (cfg["route"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val rules = (route["rules"] as? JsonArray ?: JsonArray(emptyList())).filter { !ours(it, "outbound") }.toMutableList()
        val ourIn = inbounds.mapNotNull { tagOf(it) }.filter { it in ourTags }
        if (ourIn.isNotEmpty()) active.forEachIndexed { i, u ->
            outbounds += buildJsonObject { put("type", "direct"); put("tag", USER_OUT + u.id); put("routing_mark", mark(u)) }
            // No "action": plain "outbound" rules are understood by every sing-box version.
            rules.add(i, buildJsonObject {
                put("inbound", JsonArray(ourIn.map { JsonPrimitive(it) })); putJsonArray("auth_user") { add(JsonPrimitive(u.name)) }; put("outbound", USER_OUT + u.id)
            })
        }
        cfg["outbounds"] = JsonArray(outbounds)
        if (rules.isNotEmpty()) { route["rules"] = JsonArray(rules); cfg["route"] = JsonObject(route) }
        else if (cfg["route"] != null) { route.remove("rules"); if (route.isNotEmpty()) cfg["route"] = JsonObject(route) else cfg.remove("route") }
        return Store.json.encodeToString(JsonObject.serializer(), JsonObject(cfg))
    }

    /** Share links for one user (what the app imports and what other clients understand). */
    fun links(host: String, u: MyUser, label: String): List<Pair<String, String>> {
        val h = if (host.contains(':')) "[$host]" else host
        fun frag(p: String) = "#" + enc("$label · $p" + if (u.name == "me") "" else " · ${u.name}")
        val list = mutableListOf<Pair<String, String>>()
        if (has("vless")) list += "VLESS Reality" to "vless://${u.uuid}@$h:${port("vless")}?encryption=none&flow=xtls-rprx-vision&security=reality&sni=$realitySni&fp=chrome&pbk=${s("vless", "pub")}&sid=${s("vless", "sid")}&type=tcp${frag("VLESS Reality")}"
        if (has("hy2")) list += "Hysteria2" to "hysteria2://${u.pass}@$h:${port("hy2")}?sni=${s("hy2", "sni")}&alpn=h3&obfs=salamander&obfs-password=${enc(s("hy2", "obfs"))}&insecure=1&pinSHA256=${certSha256Hex(s("hy2", "cert"))}${frag("Hysteria2")}"
        if (has("ss")) list += "Shadowsocks 2022" to "ss://$SS_METHOD:${enc(s("ss", "key") + ":" + u.ssKey)}@$h:${port("ss")}${frag("Shadowsocks 2022")}"
        if (has("naive")) list += "NaiveProxy" to "naive+https://${enc(u.name)}:${enc(u.pass)}@${s("naive", "domain")}:${port("naive")}${frag("NaiveProxy")}"
        if (has("tt")) list += "TrustTunnel" to ExtraCores.ttLink(ttProfile(host, u, label))
        if (has("trojan")) list += "Trojan" to "trojan://${enc(u.pass)}@${s("trojan", "domain")}:${port("trojan")}?security=tls&sni=${s("trojan", "domain")}&type=tcp${frag("Trojan")}"
        if (has("tuic")) list += "TUIC" to "tuic://${u.uuid}:${enc(u.pass)}@${s("tuic", "domain")}:${port("tuic")}?congestion_control=bbr&alpn=h3&sni=${s("tuic", "domain")}${frag("TUIC")}"
        if (has("anytls")) list += "AnyTLS" to "anytls://${enc(u.pass)}@${s("anytls", "domain")}:${port("anytls")}?security=tls&sni=${s("anytls", "domain")}${frag("AnyTLS")}"
        if (has("vless_ws")) s("vless_ws", "domain").let { d ->
            list += "VLESS WS" to "vless://${u.uuid}@$d:${port("vless_ws")}?encryption=none&security=tls&sni=$d&fp=chrome&type=ws&host=$d&path=${enc(s("vless_ws", "path"))}${frag("VLESS WS")}"
        }
        if (has("vless_grpc")) s("vless_grpc", "domain").let { d ->
            list += "VLESS gRPC" to "vless://${u.uuid}@$d:${port("vless_grpc")}?encryption=none&security=tls&sni=$d&fp=chrome&alpn=h2&type=grpc&serviceName=${s("vless_grpc", "service")}${frag("VLESS gRPC")}"
        }
        if (has("shadowtls")) {
            val plugin = "shadow-tls;host=$shadowTlsSni;password=${u.pass};version=3"
            list += "ShadowTLS" to "ss://$SS_METHOD:${enc(s("shadowtls", "key") + ":" + u.ssKey)}@$h:${port("shadowtls")}?plugin=${enc(plugin)}${frag("ShadowTLS")}"
        }
        if (has("socks")) list += "SOCKS5" to "socks5://${enc(u.name)}:${enc(u.pass)}@$h:${port("socks")}${frag("SOCKS5")}"
        return list
    }

    fun telegramLink(host: String, u: MyUser): String? =
        if (!has("socks")) null else "tg://socks?server=$host&port=${port("socks")}&user=${enc(u.name)}&pass=${enc(u.pass)}"

    /** TrustTunnel: server by IP, our domain as the TLS name, the pinned self-signed certificate. */
    fun ttProfile(host: String, u: MyUser, label: String) = Server(
        kind = "tt", name = "$label · TrustTunnel" + if (u.name == "me") "" else " · ${u.name}",
        server = s("tt", "domain"), port = port("tt"), userId = u.name, password = u.pass,
        params = mapOf("addresses" to (if (host.contains(':')) "[$host]" else host) + ":" + port("tt"), "cert" to s("tt", "cert"), "upstream" to "http2", "ipv6" to "0"),
    )

    /** Servers for the app: Hysteria2 gets the exact certificate instead of "insecure". */
    fun profiles(host: String, u: MyUser, label: String): List<Server> = links(host, u, label).map { (title, link) ->
        if (title == "TrustTunnel") ttProfile(host, u, label).copy(link = link)
        else ShareLinks.parse(link).let { p ->
            if (p.kind == "hysteria2" && has("hy2")) p.copy(params = p.params - "insecure" + ("cert" to s("hy2", "cert"))) else p
        }
    }
}
