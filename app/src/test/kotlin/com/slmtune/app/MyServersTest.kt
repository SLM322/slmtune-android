package com.slmtune.app

import com.slmtune.app.core.MyServerConfig
import com.slmtune.app.core.ServerInfo
import com.slmtune.app.core.ServerScripts
import com.slmtune.app.core.ShareLinks
import com.slmtune.app.core.Ssh
import com.slmtune.app.core.Store
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/** "Мои серверы": the server config, the links it hands out, the scripts and the app's SSH key. */
class MyServersTest {
    @Before
    fun scripts() {
        ServerScripts.source = { File("src/main/assets/server/$it.sh").readText() }
    }

    private fun singBox(): File? =
        File(System.getenv("SLM_SINGBOX") ?: "G:/trusttunnel-gui/dist/app/core/sing-box/sing-box.exe").takeIf { it.exists() }

    private fun everything(): MyServerConfig {
        val probe = "os=Ubuntu 24.04\narch=x86_64\nsingbox=1.14.4\nlisten=tcp 0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))\nprobe=ok\n"
        val c = MyServerConfig.from(ServerInfo.parse(probe))
        val d = MyServerConfig.defaultDomain("203.0.113.7")
        c.setVless(443, "www.microsoft.com", "aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8taGU", "cHVibGljLXB1YmxpYy1wdWJsaWMtcHVibGljLXB1Ymw")
        c.setHy2(443); c.setSs(8388); c.setNaive(4443, d); c.setTt(9444, d); c.setTrojan(8443, d); c.setTuic(8443, d)
        c.setAnyTls(2096, d); c.setVlessWs(2087, d); c.setVlessGrpc(2053, d); c.setSocks(1080); c.setShadowTls(8444, "www.microsoft.com")
        c.addUser("me"); c.addUser("friend")
        return c
    }

    @Test
    fun probeParses() {
        val info = ServerInfo.parse("os=Debian 12\nsingbox=1.14.4\nlisten=tcp 0.0.0.0:443 users:((\"nginx\",pid=5,fd=6))\nlisten=udp [::]:443 users:((\"sing-box\",pid=7,fd=8))\n" +
            "meta=" + Base64.getEncoder().encodeToString("""{"users":[{"name":"me","uuid":"u","pass":"p","ss":"s","id":3,"limit":5}],"ss":{"port":9000,"key":"k"}}""".toByteArray()) + "\nmem=100/1000\n")
        assertEquals("Debian 12", info.os)
        assertEquals("nginx", info.portOwner(443, "tcp"))
        assertEquals(null, info.portOwner(443, "udp"))
        assertEquals(100L, info.memUsed)
        val c = MyServerConfig.from(info)
        assertEquals(3, c.users.single().id)
        assertEquals(9000, c.port("ss"))
        assertEquals(listOf("9000/tcp", "9000/udp"), c.ports())
    }

    @Test
    fun serverConfigIsValidAndLinksWork() {
        val c = everything()
        val json = c.configJson()
        val root = Store.json.parseToJsonElement(json).jsonObject
        val tags = root["inbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertEquals(12, tags.size) // 11 sing-box protocols (TrustTunnel is its own server) + the inner Shadowsocks of ShadowTLS
        // Per-user marks and rules for accounting.
        assertTrue(json.contains("\"routing_mark\":21001") && json.contains("\"routing_mark\":21002"))
        singBox()?.let { exe ->
            val f = Files.createTempFile("srv", ".json").toFile().apply { writeText(json.replace(Regex(""",\s*"routing_mark":\d+"""), "")) } // Linux-only option
            val p = ProcessBuilder(exe.path, "check", "-c", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText(); p.waitFor(); f.delete()
            assertEquals("sing-box check: $out", 0, p.exitValue())
        }
        // Meta round trip: what the server keeps gives the same config back.
        val back = MyServerConfig.from(ServerInfo.parse("singbox=1.14.4\ntt_managed=1\nmeta=" + Base64.getEncoder().encodeToString(c.metaJson().toByteArray())))
        assertEquals(json, back.configJson())

        val me = c.users.first()
        val links = c.links("203.0.113.7", me, "Мой сервер")
        assertEquals(12, links.size)
        for ((title, link) in links) {
            val s = ShareLinks.parse(link)
            assertTrue("$title: ${s.server}", s.server.isNotEmpty())
        }
        val profiles = c.profiles("203.0.113.7", me, "Мой сервер")
        val hy = profiles.first { it.kind == "hysteria2" }
        assertTrue(hy.param("cert").contains("BEGIN CERTIFICATE") && hy.param("insecure").isEmpty())
        val tt = profiles.first { it.kind == "tt" }
        assertEquals("203-0-113-7.sslip.io", tt.server)
        assertEquals("203.0.113.7:9444", tt.param("addresses"))
        assertEquals(tt.param("cert"), ShareLinks.parse(tt.link).param("cert"))
        assertTrue(c.ttCredentialsToml().contains("username = \"friend\""))
        assertTrue(c.ttVpnToml().contains("listen_address = \"0.0.0.0:9444\""))
        assertTrue(c.telegramLink("203.0.113.7", me)!!.startsWith("tg://socks?server=203.0.113.7&port=1080"))

        // A user switched off disappears from every protocol.
        c.users[1].off = true
        assertFalse(c.configJson().contains("\"friend\""))
    }

    @Test
    fun scriptsHaveNoHolesLeft() {
        val c = everything()
        val all = listOf(
            ServerScripts.probe, ServerScripts.ensureSingBox(true), ServerScripts.installKey("ssh-ed25519 AAAA slmtune"),
            ServerScripts.apply(c.configJson(), c.metaJson(), c.ports(), listOf("1234/tcp"), true, nft = c.nftRules()),
            ServerScripts.ttApply(c.ttVpnToml(), c.ttHostsToml(), c.ttCredentialsToml(), c.ttCertPem, c.ttKeyPem),
            ServerScripts.harden, ServerScripts.swapAndTime, ServerScripts.autoUpdates, ServerScripts.netCheck, ServerScripts.updateSingBox, ServerScripts.restart,
        )
        all.forEach { assertFalse(it, Regex("""\{\{[A-Za-z]""").containsMatchIn(it)) }
        val apply = all[3]
        assertTrue(apply.contains("openp '443/tcp'") && apply.contains("closep '1234/tcp'") && apply.contains("sing-box check -c config.json.new"))
        // The uploaded config decodes back to exactly what was built.
        val b64 = Regex("""echo '([A-Za-z0-9+/=]+)' \| base64 -d > config\.json\.new""").find(apply)!!.groupValues[1]
        assertEquals(c.configJson(), String(Base64.getDecoder().decode(b64)))
    }

    @Test
    fun appKeyLoadsInSshj() {
        Ssh.init()
        val dir = Files.createTempDirectory("ssh").toFile()
        val pub = Ssh.ensureAppKey(dir)
        assertTrue(pub.startsWith("ssh-ed25519 ") && pub.endsWith(" slmtune"))
        val kp = SSHClient().loadKeys(File(dir, "id_ed25519").readText(), null, null)
        assertEquals(pub.substringBeforeLast(' '), Ssh.keyLine(kp.public))
        assertEquals(pub, Ssh.ensureAppKey(dir)) // the same key next time
        assertTrue(Ssh.fingerprint(pub).startsWith("SHA256:"))
        dir.deleteRecursively()
    }

    /** Live SSH against a test server: SLM_SSH_TEST="port|authorized_keys file|host key .pub" (root / testpass). */
    @Test
    fun liveSsh() {
        val spec = System.getenv("SLM_SSH_TEST") ?: return
        val (port, auth, hostPub) = spec.split('|')
        val dir = Files.createTempDirectory("ssh").toFile()
        val key = Ssh.scan("127.0.0.1", port.toInt())
        assertEquals(File(hostPub).readText().trim().substringBeforeLast(' '), key)
        // Password login, stdout + stderr + exit code, stdin carries the script.
        val t = Ssh.Target("127.0.0.1", port.toInt(), "root", key, "testpass")
        val lines = mutableListOf<String>()
        val (code, out) = Ssh.run(t, dir, "echo hello\necho oops >&2\nexit 3\n", { lines += it })
        assertEquals(3, code)
        assertTrue(out, out.contains("hello") && out.contains("oops") && lines.contains("hello"))
        // A wrong password and a changed host key are refused.
        assertTrue(runCatching { Ssh.run(t.copy(password = "nope"), dir, "echo x") }.isFailure)
        assertTrue(runCatching { Ssh.run(t.copy(hostKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"), dir, "echo x") }.isFailure)
        // The app's key, once on the server, logs in without a password; the probe script runs.
        File(auth).writeText(Ssh.ensureAppKey(dir) + "\n")
        val (c2, o2) = Ssh.run(t.copy(password = null), dir, ServerScripts.probe)
        assertEquals(o2, 0, c2)
        assertTrue(o2.contains("probe=ok"))
        File(auth).delete()
        dir.deleteRecursively()
    }
}
