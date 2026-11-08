package com.slmtune.app.core

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * SSH for "Мои серверы" (sshj + Bouncy Castle). The app's own ed25519 key lives in its private folder;
 * a server's host key is pinned only after the user confirms its fingerprint, and checked on every connect.
 */
object Ssh {
    private var ready = false

    /** Android ships a cut-down "BC" provider without Ed25519/X25519: put the full one first. */
    @Synchronized
    fun init() {
        if (ready) return
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        ready = true
    }

    data class Target(val host: String, val port: Int, val user: String, val hostKey: String, val password: String? = null)

    class Failed(message: String) : Exception(message)

    // ---------------------------------------------------------------- the app's key

    /** Creates the key on first use; returns the public key line ("ssh-ed25519 AAAA… slmtune"). */
    fun ensureAppKey(dir: File): String {
        val priv = File(dir, "id_ed25519")
        val pub = File(dir, "id_ed25519.pub")
        if (!priv.exists() || !pub.exists()) {
            dir.mkdirs()
            val key = Ed25519PrivateKeyParameters(SecureRandom())
            val seed = key.encoded
            val pk = key.generatePublicKey().encoded
            val blob = sshString("ssh-ed25519".toByteArray()) + sshString(pk)
            priv.writeText(openSshPrivate(seed, pk, blob))
            pub.writeText("ssh-ed25519 " + Base64.getEncoder().encodeToString(blob) + " slmtune\n")
        }
        return pub.readText().trim()
    }

    private fun sshString(b: ByteArray): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(b.size); write(b) } }.toByteArray()

    /** openssh-key-v1, unencrypted (the file is in the app's private folder). */
    private fun openSshPrivate(seed: ByteArray, pk: ByteArray, blob: ByteArray): String {
        val check = SecureRandom().nextInt()
        val inner = ByteArrayOutputStream()
        DataOutputStream(inner).apply {
            writeInt(check); writeInt(check)
            write(sshString("ssh-ed25519".toByteArray())); write(sshString(pk)); write(sshString(seed + pk)); write(sshString("slmtune".toByteArray()))
        }
        var pad = 1
        while (inner.size() % 8 != 0) inner.write(pad++)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write("openssh-key-v1".toByteArray()); write(0)
            write(sshString("none".toByteArray())); write(sshString("none".toByteArray())); write(sshString(ByteArray(0)))
            writeInt(1); write(sshString(blob)); write(sshString(inner.toByteArray()))
        }
        val b64 = Base64.getEncoder().encodeToString(out.toByteArray()).chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    // ---------------------------------------------------------------- host keys

    fun keyLine(key: PublicKey): String =
        KeyType.fromKey(key).toString() + " " + Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putPublicKey(key).compactData)

    fun fingerprint(keyLine: String): String {
        val blob = Base64.getDecoder().decode(keyLine.substringAfter(' ').substringBefore(' '))
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    }

    /** The server's host key, for the user to confirm before anything else is sent. */
    fun scan(host: String, port: Int): String {
        init()
        var seen: String? = null
        val ssh = SSHClient()
        ssh.connectTimeout = 15_000
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean { seen = keyLine(key); return true }
            override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
        })
        try {
            ssh.connect(host, port)
        } catch (e: Exception) {
            if (seen == null) throw Failed(explain(e))
        } finally {
            runCatching { ssh.disconnect() }
        }
        return seen ?: throw Failed("Сервер не прислал ключ SSH.")
    }

    fun explain(e: Throwable): String {
        val m = (e.message ?: e.toString()).lowercase()
        return when {
            e is java.net.UnknownHostException -> "Адрес сервера не найден. Проверьте, что он написан правильно."
            "refused" in m -> "Сервер отклонил подключение: на этом порту нет SSH. Проверьте порт (обычно 22)."
            "timed out" in m || "timeout" in m -> "Сервер не ответил: адрес недоступен или порт SSH закрыт файрволом хостинга."
            "reset" in m || "closed" in m -> "Сервер оборвал подключение. Возможно, SSH блокирует провайдер — попробуйте с включённым VPN."
            "auth" in m -> "Сервер не принял вход: проверьте пользователя и пароль."
            "host key" in m || "verify" in m -> "Ключ сервера изменился. Если вы не переустанавливали сервер, кто-то может подменять подключение."
            else -> "SSH: " + (e.message ?: e.toString())
        }
    }

    // ---------------------------------------------------------------- running scripts

    /** Runs a bash script on the server (as root, via sudo for other users) and streams its output. */
    fun run(t: Target, keyDir: File, script: String, onLine: (String) -> Unit = {}, timeoutSec: Long = 900): Pair<Int, String> {
        init()
        val ssh = SSHClient()
        ssh.connectTimeout = 15_000
        ssh.timeout = 60_000
        val pinned = t.hostKey
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey) = keyLine(key) == pinned
            override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = listOf(pinned.substringBefore(' '))
        })
        val out = StringBuilder()
        try {
            ssh.connect(t.host, t.port)
            ssh.connection.keepAlive.keepAliveInterval = 15
            if (!t.password.isNullOrEmpty()) ssh.authPassword(t.user, t.password)
            else ssh.authPublickey(t.user, ssh.loadKeys(File(keyDir, "id_ed25519").readText(), null, null))
            ssh.startSession().use { session ->
                val cmd = session.exec(if (t.user == "root") "bash -s" else "sudo -n bash -s")
                cmd.outputStream.write(script.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
                cmd.outputStream.flush()
                cmd.outputStream.close()
                fun pump(s: java.io.InputStream) = Thread {
                    runCatching {
                        s.bufferedReader(Charsets.UTF_8).forEachLine { l -> synchronized(out) { out.appendLine(l) }; onLine(l) }
                    }
                }.apply { isDaemon = true; start() }
                val a = pump(cmd.inputStream)
                val b = pump(cmd.errorStream)
                cmd.join(timeoutSec, TimeUnit.SECONDS)
                a.join(5000); b.join(5000)
                val code = cmd.exitStatus ?: throw Failed("Сервер не ответил вовремя.")
                return code to synchronized(out) { out.toString() }
            }
        } catch (e: Failed) {
            throw e
        } catch (e: Exception) {
            throw Failed(explain(e))
        } finally {
            runCatching { ssh.disconnect() }
        }
    }
}
