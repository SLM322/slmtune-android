package com.slmtune.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Provider subscriptions: the server list plus traffic left and the end date (subscription-userinfo). */
object Subscriptions {
    class Result(val servers: List<Server>, val skipped: Int, val sub: Subscription)

    suspend fun fetch(sub: Subscription, proxyPort: Int? = null): Result = withContext(Dispatchers.IO) {
        require(sub.url.startsWith("https://") || sub.url.startsWith("http://")) { "Адрес подписки должен начинаться с https://" }
        var headers: Map<String, List<String>> = emptyMap()
        // Panels (Marzban, 3x-ui, Remnawave) answer with the plain link list to v2rayN-like clients.
        val body = String(Lists.fetchBytes(sub.url, proxyPort, mapOf("User-Agent" to "v2rayN/7.0 SLMtune-Android/4.1")) { headers = it }, Charsets.UTF_8)
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        var updated = sub.copy(lastUpdate = System.currentTimeMillis(), lastError = null)
        header("subscription-userinfo")?.let { updated = applyUserInfo(updated, it) }
        if (updated.name.isBlank()) header("profile-title")?.let { t ->
            val title = if (t.startsWith("base64:", true)) ShareLinks.decodeBase64(t.substring(7)) ?: t else t
            updated = updated.copy(name = title.trim())
        }
        val links = ShareLinks.findLinks(body)
        require(links.isNotEmpty()) { "В ответе подписки нет поддерживаемых ссылок." }
        var skipped = 0
        val servers = links.mapNotNull { l ->
            runCatching { ShareLinks.parse(l).copy(subscriptionId = sub.id) }.getOrElse { skipped++; null }
        }
        Result(servers, skipped, updated.copy(count = servers.size, name = updated.name.ifBlank { java.net.URI(sub.url).host ?: "Подписка" }))
    }

    /** "upload=1; download=2; total=3; expire=1767139200" */
    fun applyUserInfo(s: Subscription, header: String): Subscription {
        var r = s
        for (part in header.split(';')) {
            val kv = part.split('=', limit = 2).map { it.trim() }
            if (kv.size != 2) continue
            val v = kv[1].toDoubleOrNull()?.toLong() ?: continue
            r = when (kv[0].lowercase()) {
                "upload" -> r.copy(upload = v)
                "download" -> r.copy(download = v)
                "total" -> r.copy(total = v)
                "expire" -> r.copy(expire = v)
                else -> r
            }
        }
        return r
    }

    /** "38,2 из 100 ГБ · до 30.11.2026" or "". */
    fun describe(s: Subscription): String {
        if (s.total <= 0 && s.expire <= 0) return ""
        val gb = { b: Long -> String.format(java.util.Locale("ru"), if (b >= 100L shl 30) "%.0f" else "%.1f", b / 1073741824.0) }
        val parts = mutableListOf<String>()
        parts += if (s.total > 0) "${gb(s.upload + s.download)} из ${gb(s.total)} ГБ" else "${gb(s.upload + s.download)} ГБ, без лимита"
        if (s.expire > 0) {
            val date = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru")).format(java.util.Date(s.expire * 1000))
            parts += if (s.expire * 1000 < System.currentTimeMillis()) "истекла $date" else "до $date"
        }
        return parts.joinToString(" · ")
    }
}
