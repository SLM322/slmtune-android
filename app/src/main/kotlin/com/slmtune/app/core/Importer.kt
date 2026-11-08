package com.slmtune.app.core

import android.net.Uri

/** One entry point for the clipboard, QR codes, slmtune:// links and text shared from other apps. */
object Importer {
    sealed class Result {
        data class Servers(val added: Int, val failed: List<String>) : Result()
        data class AskSubscription(val url: String) : Result()
        data class Nothing(val why: String) : Result()
    }

    fun importText(raw: String): Result {
        var text = raw.trim()
        if (text.startsWith("slmtune://", true)) text = fromDeepLink(text) ?: return Result.Nothing("Ссылка slmtune:// не распознана.")
        if ((text.startsWith("https://") || text.startsWith("http://")) && !text.contains('\n')) return Result.AskSubscription(text)
        val links = ShareLinks.findLinks(text)
        if (links.isEmpty()) return Result.Nothing("Нет поддерживаемых ссылок: vless://, vmess://, trojan://, ss://, hysteria2://, tuic://, anytls://, naive+https://, wireguard:// или ссылки на подписку.")
        val ok = mutableListOf<Server>()
        val failed = mutableListOf<String>()
        for (l in links) runCatching { ok += ShareLinks.parse(l) }.onFailure { failed += (it.message ?: l.take(40)) }
        if (ok.isNotEmpty()) Store.addServers(ok)
        return Result.Servers(ok.size, failed)
    }

    /** slmtune://import?link=…, slmtune://subscribe?url=…, or a share link right after the scheme. */
    fun fromDeepLink(link: String): String? {
        val rest = link.substring("slmtune://".length)
        if (ShareLinks.findLinks(rest).isNotEmpty()) return rest
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return null
        return when (uri.host?.lowercase()) {
            "import", "add" -> (uri.getQueryParameter("link") ?: uri.getQueryParameter("url"))?.let { l ->
                if (ShareLinks.findLinks(l).isEmpty()) ShareLinks.decodeBase64(l) ?: l else l
            }
            "subscribe", "sub", "install-config" -> uri.getQueryParameter("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            else -> null
        }
    }
}
