package com.slmtune.app.service

import android.content.Context
import com.slmtune.app.core.ExtraCores
import com.slmtune.app.core.Server
import com.slmtune.app.core.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Runs the separate cores of the full build. Android only executes files from the APK's native library
 * folder, so each core ships as lib<name>.so there; configs go to the app's private folder.
 */
class CoreRunner(private val context: Context, private val scope: CoroutineScope) {
    private val procs = mutableListOf<Process>()
    private val nativeDir: String get() = context.applicationInfo.nativeLibraryDir
    private val work: File get() = File(context.noBackupFilesDir, "cores").apply { mkdirs() }

    /** Last lines of every core's output, for the error message and "Журнал". */
    val log = ArrayDeque<String>()

    companion object {
        fun has(context: Context, engine: String) = File(context.applicationInfo.nativeLibraryDir, "lib$engine.so").exists()

        fun missingMessage(context: Context, engine: String): String = when {
            engine == ExtraCores.TRUSTTUNNEL && has(context, ExtraCores.XRAY) ->
                "TrustTunnel есть только для телефонов ARM64 (почти все современные). На этом устройстве он не запустится."
            else -> "${ExtraCores.titles[engine]} есть только в полной версии SLMtune (файл SLMtune-…-full.apk)."
        }

        fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }

    /** Go programs on Android have no system DNS (no /etc/resolv.conf): hand them the address, keep the name for TLS. */
    private fun resolve(host: String): String = runCatching {
        if (host.any { it.isLetter() } && !host.contains(':')) {
            val all = InetAddress.getAllByName(host)
            (all.firstOrNull { it is java.net.Inet4Address } ?: all.first()).hostAddress!!
        } else host
    }.getOrDefault(host)

    private fun resolveHostPort(a: String): String {
        val i = a.lastIndexOf(':')
        if (i <= 0 || a.startsWith("[")) return a
        return resolve(a.substring(0, i)) + a.substring(i)
    }

    /** Starts the cores for [servers]; returns server id → SOCKS port. Throws with the core's own words on failure. */
    suspend fun start(settings: Settings, servers: List<Server>, onStatus: (String) -> Unit): Map<String, Int> = withContext(Dispatchers.IO) {
        val ports = mutableMapOf<String, Int>()
        for (s in servers) {
            val engine = ExtraCores.engine(s, settings) ?: continue
            if (!has(context, engine)) error(missingMessage(context, engine))
            val port = freePort()
            val dir = File(work, s.id.take(12)).apply { mkdirs() }
            val exe = "$nativeDir/lib$engine.so"
            val args: List<String> = when (engine) {
                ExtraCores.XRAY -> {
                    File(dir, "config.json").writeText(ExtraCores.xrayConfig(s, port, resolve(s.server)))
                    listOf(exe, "run", "-c", File(dir, "config.json").path)
                }
                ExtraCores.HYSTERIA -> {
                    val ca = s.param("cert").takeIf { it.isNotEmpty() }?.let { File(dir, "ca.pem").apply { writeText(it) }.path }
                    File(dir, "config.json").writeText(ExtraCores.hysteriaConfig(s, port, resolve(s.server), ca))
                    listOf(exe, "client", "-c", File(dir, "config.json").path, "--disable-update-check")
                }
                ExtraCores.TOR -> {
                    val pt = context.assets.open("tor/pt_config.json").use { String(it.readBytes()) }
                    val data = File(dir, "data").apply { mkdirs() }
                    File(dir, "torrc").writeText(ExtraCores.torrc(s, port, data.path, nativeDir, pt))
                    listOf(exe, "-f", File(dir, "torrc").path, "--defaults-torrc", File(dir, "none").path, "--ignore-missing-torrc")
                }
                else -> {
                    val addrs = s.param("addresses").lines().filter { it.isNotBlank() }.map { resolveHostPort(it.trim()) }
                    File(dir, "client.toml").writeText(ExtraCores.ttConfig(s, port, addrs))
                    listOf(exe, "-c", File(dir, "client.toml").path, "-l", "info")
                }
            }
            onStatus("Запускаю ${ExtraCores.titles[engine]}…")
            val pb = ProcessBuilder(args).directory(dir).redirectErrorStream(true)
            pb.environment()["HOME"] = dir.path
            pb.environment()["TMPDIR"] = context.cacheDir.path
            pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
            val p = pb.start()
            procs += p
            val tag = ExtraCores.titles[engine]
            var torProgress = -1
            scope.launch(Dispatchers.IO) {
                // The stream closes when the core is stopped: that ends the loop, it's not an error.
                runCatching { p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) { log.addLast("[$tag] $line"); while (log.size > 200) log.removeFirst() }
                    if (engine == ExtraCores.TOR) Regex("Bootstrapped ([0-9]+)%").find(line)?.let {
                        torProgress = it.groupValues[1].toInt()
                        onStatus("Tor подключается: $torProgress%")
                    }
                } }
            }
            // Ready = the SOCKS port answers. Tor also has to build its first circuit before traffic works.
            val deadline = System.currentTimeMillis() + if (engine == ExtraCores.TOR) 180_000 else 15_000
            while (true) {
                if (!p.isAlive) error("$tag завершился: " + lastLines(tag))
                val listening = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess
                if (listening && (engine != ExtraCores.TOR || torProgress >= 100)) break
                if (System.currentTimeMillis() > deadline) error(
                    if (engine == ExtraCores.TOR) "Tor не подключился за 3 минуты (дошёл до $torProgress%): мосты не отвечают. " +
                        "Попробуйте другие мосты — Snowflake, свои из @GetBridgesBot — или подключитесь позже."
                    else "$tag не ответил вовремя. " + lastLines(tag).lines().lastOrNull().orEmpty()
                )
                delay(250)
            }
            ports[s.id] = port
        }
        ports
    }

    private fun lastLines(tag: String?): String = synchronized(log) {
        log.filter { it.startsWith("[$tag]") }.takeLast(3).joinToString("\n") { it.substringAfter("] ") }
    }

    fun alive(): Boolean = procs.all { it.isAlive }

    fun stop() {
        procs.forEach { p -> runCatching { p.destroy() } }
        procs.forEach { p -> runCatching { if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() } }
        procs.clear()
    }
}
