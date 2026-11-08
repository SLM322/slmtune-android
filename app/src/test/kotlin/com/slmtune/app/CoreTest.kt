package com.slmtune.app

import com.slmtune.app.core.ConfigBuilder
import com.slmtune.app.core.CustomRule
import com.slmtune.app.core.ExtraCores
import com.slmtune.app.core.Lists
import com.slmtune.app.core.Outbounds
import com.slmtune.app.core.Server
import com.slmtune.app.core.Settings
import com.slmtune.app.core.ShareLinks
import com.slmtune.app.core.Store
import com.slmtune.app.core.Subscriptions
import com.slmtune.app.core.Subscription
import com.slmtune.app.core.X25519
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Plain JVM checks of the parts that decide whether a connection works: link parsing and the sing-box config.
 * When the Windows build's sing-box is next door (SLM_SINGBOX or the default path), every config is also
 * handed to `sing-box check` — the same core version as on the phone.
 */
class CoreTest {
    private val links = listOf(
        "vless://1b671a64-40d5-491e-99b0-da01ff1f3341@1.2.3.4:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw&sid=6ba85179e30d4fc2&type=tcp#Reality",
        "vless://1b671a64-40d5-491e-99b0-da01ff1f3341@w.example.com:2087?encryption=none&security=tls&sni=w.example.com&fp=chrome&type=ws&host=w.example.com&path=%2Fabc#WS",
        "vless://1b671a64-40d5-491e-99b0-da01ff1f3341@g.example.com:2053?encryption=none&security=tls&sni=g.example.com&alpn=h2&type=grpc&serviceName=gabc#gRPC",
        "trojan://pass@t.example.com:443?security=tls&sni=t.example.com&type=tcp#Trojan",
        "hysteria2://pass@h.example.com:443?sni=h.example.com&obfs=salamander&obfs-password=x&insecure=1#Hy2",
        "hy2://pass@h2.example.com:443?sni=h2.example.com&mport=20000-30000#Hy2-hop",
        "tuic://1b671a64-40d5-491e-99b0-da01ff1f3341:pass@u.example.com:443?alpn=h3&sni=u.example.com&congestion_control=bbr#TUIC",
        "anytls://pass@a.example.com:2096?security=tls&sni=a.example.com#AnyTLS",
        "ss://2022-blake3-aes-128-gcm:QUJDREVGR0hJSktMTU5PUA%3D%3D%3AUVJTVFVWV1hZWjAxMjM0NQ%3D%3D@5.6.7.8:8388#SS2022",
        "ss://2022-blake3-aes-128-gcm:QUJDREVGR0hJSktMTU5PUA%3D%3D%3AUVJTVFVWV1hZWjAxMjM0NQ%3D%3D@5.6.7.8:8444?plugin=shadow-tls%3Bhost%3Dwww.microsoft.com%3Bpassword%3Dp%3Bversion%3D3#ShadowTLS",
        "naive+https://user:pass@n.example.com:443#Naive",
        "socks5://user:pass@9.9.9.9:1080#SOCKS",
        "vmess://" + java.util.Base64.getEncoder().encodeToString("""{"v":"2","ps":"VMess","add":"m.example.com","port":"443","id":"1b671a64-40d5-491e-99b0-da01ff1f3341","net":"ws","tls":"tls","sni":"m.example.com","path":"/v","host":"m.example.com"}""".toByteArray()),
        "wireguard://cGFzc3dvcmRwYXNzd29yZHBhc3N3b3JkcGFzc3dvcmQ%3D@162.159.192.1:2408?publickey=bmOUmxa4tCSAjyNO1R4VUW6iNSxDUfmyFeJN7wVQXu8%3D&address=172.16.0.2%2F32&mtu=1280&reserved=1,2,3#WARP",
    )

    private fun singBox(): File? {
        val path = System.getenv("SLM_SINGBOX") ?: "G:/trusttunnel-gui/src/TrustTunnelN/bin/Debug/net9.0-windows/core/sing-box/sing-box.exe"
        return File(path).takeIf { it.exists() }
    }

    private fun check(json: String, name: String) {
        val exe = singBox() ?: return
        val dir = Files.createTempDirectory("slm-check").toFile()
        val f = File(dir, "$name.json").also { it.writeText(json) }
        // sing-box for Windows has no Android-only options; drop them for the check.
        val windowsJson = json.replace(Regex(""",?"package_name":\[[^\]]*\]"""), "")
        f.writeText(windowsJson.replace("{,", "{"))
        val p = ProcessBuilder(exe.path, "check", "-c", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        dir.deleteRecursively()
        assertEquals("sing-box check ($name): $out", 0, p.exitValue())
    }

    @Test
    fun parsesEveryLink() {
        val parsed = links.map { ShareLinks.parse(it) }
        assertEquals(listOf("vless", "vless", "vless", "trojan", "hysteria2", "hysteria2", "tuic", "anytls", "ss", "ss", "naive", "socks", "vmess", "wireguard"), parsed.map { it.kind })
        assertEquals("Reality", parsed[0].name)
        assertEquals("/abc", parsed[1].param("path"))
        assertEquals("2022-blake3-aes-128-gcm", parsed[8].userId)
        assertEquals("ABCDEFGHIJKLMNOP:QRSTUVWXYZ012345".let { java.util.Base64.getEncoder().encodeToString("ABCDEFGHIJKLMNOP".toByteArray()) + ":" + java.util.Base64.getEncoder().encodeToString("QRSTUVWXYZ012345".toByteArray()) }, parsed[8].password)
        assertTrue(parsed[9].param("plugin").startsWith("shadow-tls"))
        assertEquals("m.example.com", parsed[12].server)
        assertEquals("1,2,3", parsed[13].param("reserved"))
    }

    private val ttOfficial = "tt://?AAEBARUyMDMtMC0xMTMtMTAuc3NsaXAuaW8FCWRlbW91c2VyMQYgZTUxYzExNzQxNzAyMmI1ZWNjODY2MjhjNWQ3Y2ZhNmICGjIwMy0wLTExMy0xMC5zc2xpcC5pbzo5NDQz#DE-TrustTunnel"

    @Test
    fun trustTunnelLinkRoundTrip() {
        // The same link the Windows checks compare against the official setup_wizard.
        val s = ShareLinks.parse(ttOfficial)
        assertEquals("tt", s.kind)
        assertEquals("203-0-113-10.sslip.io", s.server)
        assertEquals("demouser1", s.userId)
        assertEquals("e51c117417022b5ecc86628c5d7cfa6b", s.password)
        assertEquals("203-0-113-10.sslip.io:9443", s.param("addresses"))
        assertEquals("DE-TrustTunnel", s.name)
        assertEquals(ttOfficial, ExtraCores.ttLink(s))
        val toml = ExtraCores.ttConfig(s, 1080)
        assertTrue(toml.contains("[listener.socks]") && toml.contains("address = \"127.0.0.1:1080\"") && toml.contains("username = \"demouser1\""))
        // A pinned certificate survives the trip (DER in the link, PEM in the config).
        val der = ByteArray(300) { (it * 7).toByte() }
        val nl = 10.toChar().toString()
        val pem = "-----BEGIN CERTIFICATE-----$nl" + java.util.Base64.getMimeEncoder(64, nl.toByteArray()).encodeToString(der) + "$nl-----END CERTIFICATE-----$nl"
        val back = ShareLinks.parse(ExtraCores.ttLink(s.copy(params = s.params + ("cert" to pem) + ("upstream" to "http3"))))
        assertEquals(pem, back.param("cert"))
        assertEquals("http3", back.param("upstream"))
        assertTrue(runCatching { ShareLinks.parse("tt://?abc") }.isFailure)
    }

    @Test
    fun separateCores() {
        val dir = Files.createTempDirectory("slm-rules").toFile()
        val xhttp = ShareLinks.parse("vless://1b671a64-40d5-491e-99b0-da01ff1f3341@x.example.com:443?type=xhttp&security=tls&sni=x.example.com&path=%2Fp&mode=packet-up#XHTTP")
        val enc = ShareLinks.parse("vless://1b671a64-40d5-491e-99b0-da01ff1f3341@1.2.3.4:443?type=tcp&security=reality&sni=a.com&pbk=Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw&sid=01&encryption=mlkem768x25519plus.native.0rtt.pOHODbGK97Q2D2FZ9DZNo1RiTqRYTugsPOttiDpTAgU#Enc")
        val tor = ExtraCores.torProfile("obfs4")
        val tt = ShareLinks.parse(ttOfficial)
        val hy = ShareLinks.parse(links[4])
        val settings = Settings(servers = listOf(xhttp, enc, tor, tt, hy), hysteriaOfficial = true)
        assertEquals(listOf("xray", "xray", "tor", "trusttunnel", "hysteria"), settings.servers.map { ExtraCores.engine(it, settings) })
        assertEquals(null, ExtraCores.engine(hy, Settings()))
        // Lite build / no core: a clear error instead of a broken config.
        assertTrue(runCatching { ConfigBuilder.build(settings, xhttp, dir) }.exceptionOrNull()!!.message!!.contains("Xray"))
        // With a core: sing-box talks SOCKS to it, and the app's own requests get a local inbound.
        for (s in settings.servers) {
            val json = ConfigBuilder.build(settings, s, dir, mapOf(s.id to 20000), 20001)
            val outs = Store.json.parseToJsonElement(json).jsonObject["outbounds"]!!.jsonArray.map { it.jsonObject }
            val proxy = outs.first { it["tag"]!!.jsonPrimitive.content == "proxy" }
            assertEquals("socks", proxy["type"]!!.jsonPrimitive.content)
            assertEquals(20000, proxy["server_port"]!!.jsonPrimitive.content.toInt())
            assertTrue(json.contains("\"mixed\""))
            check(json, "backend-${s.kind}")
        }
        // A separate core can only be the first hop of a cascade.
        val behind = xhttp.copy(viaId = hy.id)
        val cs = settings.copy(servers = settings.servers + behind)
        assertTrue(runCatching { ConfigBuilder.build(cs, behind, dir, mapOf(behind.id to 1, hy.id to 2)) }.isFailure)
        val first = ShareLinks.parse(links[0]).copy(viaId = xhttp.id)
        assertEquals(listOf(xhttp.id), ExtraCores.backends(settings.copy(servers = settings.servers + first), first).map { it.id })
        ConfigBuilder.build(settings.copy(servers = settings.servers + first), first, dir, mapOf(xhttp.id to 20000))

        // Xray accepts its config (the Windows build of the same version, when it's next door).
        val xray = File("G:/trusttunnel-gui/dist/app/core/xray/xray.exe")
        for (s in listOf(xhttp, enc)) {
            val cfg = ExtraCores.xrayConfig(s, 20000, "9.9.9.9")
            assertTrue(cfg.contains("\"serverName\":\"" + s.param("sni") + "\""))
            if (xray.exists()) {
                val f = File(dir, "xray.json").also { it.writeText(cfg) }
                val p = ProcessBuilder(xray.path, "run", "-test", "-c", f.path).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                assertEquals("xray -test: $out", 0, p.exitValue())
            }
        }
        val hyCfg = Store.json.parseToJsonElement(ExtraCores.hysteriaConfig(hy, 20000, "9.9.9.9")).jsonObject
        assertEquals("9.9.9.9:443", hyCfg["server"]!!.jsonPrimitive.content)
        assertEquals("h.example.com", hyCfg["tls"]!!.jsonObject["sni"]!!.jsonPrimitive.content)

        // torrc from the bundle's own pt_config.json (fetched by tools/fetch_cores.py).
        val pt = File("src/full/assets/tor/pt_config.json")
        if (pt.exists()) {
            val rc = ExtraCores.torrc(tor, 9050, "/data/tor", "/lib", pt.readText())
            assertTrue(rc.contains("UseBridges 1") && rc.contains("exec /lib/liblyrebird.so") && rc.contains("Bridge obfs4 "))
            val sf = ExtraCores.torrc(ExtraCores.torProfile("snowflake"), 9050, "/data/tor", "/lib", pt.readText())
            assertTrue(sf.contains("ClientTransportPlugin snowflake exec /lib/liblyrebird.so") && sf.contains("Bridge snowflake "))
            assertTrue(runCatching { ExtraCores.torrc(ExtraCores.torProfile("custom"), 9050, "/d", "/lib", pt.readText()) }.isFailure)
        }
        dir.deleteRecursively()
    }

    @Test
    fun subscriptionBodyInBase64() {
        val body = java.util.Base64.getEncoder().encodeToString(links.take(3).joinToString("\n").toByteArray())
        assertEquals(3, ShareLinks.findLinks(body).size)
    }

    @Test
    fun everyServerMakesAValidConfig() {
        val dir = Files.createTempDirectory("slm-rules").toFile()
        // Preset lists as the app writes them.
        Lists.ruleFile(dir, Lists.RU_DOMAINS).writeText("""{"version":3,"rules":[{"domain_suffix":["ru","yandex.ru"]}]}""")
        Lists.ruleFile(dir, Lists.RU_IPS).writeText("""{"version":3,"rules":[{"ip_cidr":["5.255.255.0/24"]}]}""")
        Lists.ruleFile(dir, Lists.BLOCKED).writeText("""{"version":3,"rules":[{"domain_suffix":["instagram.com"]}]}""")
        for ((i, link) in links.withIndex()) {
            val s = ShareLinks.parse(link)
            for (preset in listOf("all", "except_ru", "blocked")) {
                val settings = Settings(servers = listOf(s), activeId = s.id, preset = preset,
                    customRules = listOf(CustomRule(name = "x", action = "direct", domains = listOf("bank.example"), ips = listOf("10.1.2.3"))))
                val json = ConfigBuilder.build(settings, s, dir)
                val root = Store.json.parseToJsonElement(json).jsonObject
                assertEquals(if (preset == "blocked") "direct" else "proxy", root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
                check(json, "server$i-$preset")
            }
        }
        dir.deleteRecursively()
    }

    @Test
    fun cascadeAndGroups() {
        val dir = Files.createTempDirectory("slm-rules").toFile()
        val a = ShareLinks.parse(links[4]) // Hysteria2
        val b = ShareLinks.parse(links[7]).copy(viaId = a.id) // AnyTLS through Hysteria2
        val c = ShareLinks.parse(links[0]).copy(viaId = b.id) // Reality through both
        val g = Server(name = "group", kind = "group", server = "group", port = 1, params = mapOf("mode" to "auto"), members = listOf(a.id, c.id))
        val settings = Settings(servers = listOf(a, b, c, g), activeId = c.id)
        assertEquals(listOf(a.id, b.id), Outbounds.hops(settings, c).map { it.id })
        val json = ConfigBuilder.build(settings, c, dir)
        val outs = Store.json.parseToJsonElement(json).jsonObject["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals("hop1", outs.first { it["tag"]!!.jsonPrimitive.content == "proxy" }["detour"]!!.jsonPrimitive.content)
        assertEquals("hop0", outs.first { it["tag"]!!.jsonPrimitive.content == "hop1" }["detour"]!!.jsonPrimitive.content)
        check(json, "cascade")
        val gjson = ConfigBuilder.build(settings.copy(activeId = g.id), g, dir)
        assertTrue(gjson.contains("\"urltest\""))
        check(gjson, "group")
        // The global entry server applies to servers without their own hop.
        val entry = Settings(servers = listOf(a, ShareLinks.parse(links[3])), cascadeEntryId = a.id)
        assertEquals(listOf(a.id), Outbounds.hops(entry, entry.servers[1]).map { it.id })
        dir.deleteRecursively()
    }

    @Test
    fun x25519MatchesRfc7748() {
        // RFC 7748 §6.1: Alice's key pair.
        val priv = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val base = ByteArray(32).also { it[0] = 9 }
        val pub = X25519.scalarMult(priv, base).joinToString("") { "%02x".format(it) }
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", pub)
    }

    @Test
    fun subscriptionUserInfo() {
        val s = Subscriptions.applyUserInfo(Subscription(), "upload=1073741824; download=9663676416; total=107374182400; expire=1767139200")
        assertEquals(10737418240L, s.upload + s.download)
        assertEquals(1767139200L, s.expire)
    }

    @Test
    fun blockListsReachTheConfig() {
        val dir = Files.createTempDirectory("slm-rules").toFile()
        val ads = Lists.blocks.first()
        // A tiny real rule set is not at hand offline: only the wiring is checked (file present → rule present).
        Lists.blockFile(dir, ads).writeBytes("SRS".toByteArray())
        val s = ShareLinks.parse(links[3])
        val json = ConfigBuilder.build(Settings(servers = listOf(s), blockLists = listOf(ads.id)), s, dir)
        assertTrue(json.contains("\"block-ads\"") && json.contains("NXDOMAIN"))
        dir.deleteRecursively()
        assumeTrue(true)
    }
}
