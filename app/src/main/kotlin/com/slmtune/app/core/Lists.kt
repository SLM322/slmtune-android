package com.slmtune.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Lists behind the presets and blocking, the same sources as the Windows version:
 * - blocked in Russia (itdoginfo/allow-domains) — "only blocked sites through the VPN";
 * - Russian sites (v2fly category-ru) and Russian IPs (ipverse) — "everything except Russia";
 * - MetaCubeX rule sets for ads, trackers, 18+.
 * Downloaded by the app and handed to sing-box as local files, so a failed download never stops a connection.
 */
object Lists {
    const val BLOCKED = "ru-blocked"
    const val RU_DOMAINS = "ru-domains"
    const val RU_IPS = "ru-ips"

    private val sources = mapOf(
        BLOCKED to "https://raw.githubusercontent.com/itdoginfo/allow-domains/main/Russia/inside-raw.lst",
        RU_DOMAINS to "https://raw.githubusercontent.com/v2fly/domain-list-community/master/data/category-ru",
        RU_IPS to "https://raw.githubusercontent.com/ipverse/rir-ip/master/country/ru/ipv4-aggregated.txt",
    )

    data class Block(val id: String, val title: String, val note: String, val file: String)

    val blocks = listOf(
        Block("ads", "Реклама", "баннеры, всплывающие окна, рекламные сети", "category-ads-all.srs"),
        Block("trackers", "Трекеры и аналитика", "счётчики и слежка за посещениями", "tracker.srs"),
        Block("adult", "Сайты 18+", "семейный фильтр", "category-porn.srs"),
    )
    private const val BLOCK_BASE = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/geosite/"

    fun dir(context: Context) = File(context.filesDir, "rules").also { it.mkdirs() }
    fun ruleFile(dir: File, id: String) = File(dir, "$id.json")
    fun ruleFile(context: Context, id: String) = ruleFile(dir(context), id)
    fun blockFile(dir: File, b: Block) = File(dir, b.file)
    fun blockFile(context: Context, b: Block) = blockFile(dir(context), b)

    fun has(dir: File, id: String) = ruleFile(dir, id).let { it.exists() && it.length() > 0 }

    /** Downloads what the settings need and is missing or older than a day. Returns how many were updated. */
    suspend fun update(context: Context, settings: Settings, force: Boolean = false, proxyPort: Int? = null): Int = withContext(Dispatchers.IO) {
        var n = 0
        val wanted = when (settings.preset) {
            "blocked" -> listOf(BLOCKED)
            "except_ru" -> listOf(RU_DOMAINS, RU_IPS)
            else -> emptyList()
        }
        for (id in wanted) {
            val f = ruleFile(context, id)
            if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < 86_400_000) continue
            runCatching {
                val text = fetch(sources.getValue(id), proxyPort)
                val json = when (id) {
                    RU_IPS -> ruleSet(ips = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") })
                    RU_DOMAINS -> v2flyRuleSet(text)
                    else -> ruleSet(suffixes = text.lines().map { it.trim().trimStart('.') }.filter { it.isNotEmpty() && !it.startsWith("#") })
                }
                writeAtomic(f, json.toByteArray())
                n++
            }
        }
        for (b in blocks.filter { it.id in settings.blockLists }) {
            val f = blockFile(context, b)
            if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < 86_400_000) continue
            runCatching {
                val bytes = fetchBytes(BLOCK_BASE + b.file, proxyPort)
                require(bytes.size > 3 && bytes[0] == 'S'.code.toByte() && bytes[1] == 'R'.code.toByte() && bytes[2] == 'S'.code.toByte()) { "not a rule set" }
                writeAtomic(f, bytes)
                n++
            }
        }
        n
    }

    private fun ruleSet(suffixes: List<String> = emptyList(), domains: List<String> = emptyList(), ips: List<String> = emptyList()): String =
        buildJsonObject {
            put("version", 3)
            putJsonArray("rules") {
                add(buildJsonObject {
                    if (suffixes.isNotEmpty()) put("domain_suffix", JsonArray(suffixes.distinct().map { JsonPrimitive(it) }))
                    if (domains.isNotEmpty()) put("domain", JsonArray(domains.distinct().map { JsonPrimitive(it) }))
                    if (ips.isNotEmpty()) put("ip_cidr", JsonArray(ips.distinct().map { JsonPrimitive(it) }))
                })
            }
        }.toString()

    /** v2fly format: "domain", "full:x", "domain:x", "regexp:/keyword:/include:" (skipped), "@attr" suffixes stripped. */
    private fun v2flyRuleSet(text: String): String {
        val suffixes = mutableListOf<String>()
        val full = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim().substringBefore(' ').substringBefore('@').trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("full:") -> full += line.removePrefix("full:")
                line.startsWith("domain:") -> suffixes += line.removePrefix("domain:")
                line.contains(':') -> {}
                else -> suffixes += line
            }
        }
        // The list names the main Russian zones too (.ru, .рф, .su) — keep them as suffixes.
        suffixes += listOf("ru", "xn--p1ai", "su")
        return ruleSet(suffixes = suffixes, domains = full)
    }

    private fun writeAtomic(f: File, bytes: ByteArray) {
        val tmp = File(f.path + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun fetch(url: String, proxyPort: Int? = null): String = String(fetchBytes(url, proxyPort), Charsets.UTF_8)

    fun fetchBytes(url: String, proxyPort: Int? = null, headers: Map<String, String> = emptyMap(), onHeaders: ((Map<String, List<String>>) -> Unit)? = null): ByteArray {
        val proxy = proxyPort?.let { Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", it)) } ?: Proxy.NO_PROXY
        val c = URL(url).openConnection(proxy) as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "SLMtune-Android/4.1")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            onHeaders?.invoke(c.headerFields.filterKeys { it != null })
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
