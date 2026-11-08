package com.slmtune.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** One server: a share link split into fields, the same shape as ServerProfile in the Windows version. */
@Serializable
data class Server(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val name: String = "",
    val kind: String = "",
    val server: String = "",
    val port: Int = 0,
    val userId: String = "",
    val password: String = "",
    val params: Map<String, String> = emptyMap(),
    val subscriptionId: String = "",
    /** Cascade: reach this server through another one. */
    val viaId: String = "",
    /** Group: member ids, in order. */
    val members: List<String> = emptyList(),
    val link: String = "",
    val pingMs: Int = -1,
) {
    fun param(key: String, def: String = ""): String = params.entries.firstOrNull { it.key.equals(key, true) }?.value ?: def
    val title: String get() = name.ifBlank { "$kind $server" }
}

@Serializable
data class Subscription(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val name: String = "",
    val url: String = "",
    val autoUpdate: Boolean = true,
    val lastUpdate: Long = 0,
    val count: Int = 0,
    val lastError: String? = null,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    /** Unix seconds; 0 = not sent. */
    val expire: Long = 0,
)

@Serializable
data class CustomRule(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val name: String = "",
    /** "vpn" | "direct" | "block" */
    val action: String = "vpn",
    val domains: List<String> = emptyList(),
    val ips: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val enabled: Boolean = true,
)

@Serializable
data class Settings(
    val servers: List<Server> = emptyList(),
    val activeId: String = "",
    val subscriptions: List<Subscription> = emptyList(),
    /** Routing preset: "all" (everything through the VPN), "except_ru" (Russian sites direct), "blocked" (only blocked sites through the VPN). */
    val preset: String = "except_ru",
    val aiViaVpn: Boolean = true,
    val disabledServices: List<String> = emptyList(),
    /** Games: "vpn" (UDP through the VPN), "direct", "" (general rules). */
    val gamesRoute: String = "direct",
    val customRules: List<CustomRule> = emptyList(),
    /** Apps: "off" | "only" (only chosen apps through the VPN) | "bypass" (chosen apps bypass the VPN). */
    val perAppMode: String = "off",
    val perAppPackages: List<String> = emptyList(),
    val blockLists: List<String> = emptyList(),
    val lanBypass: Boolean = true,
    val ipv6: Boolean = false,
    /** Cascade entry for every server ("" = off). */
    val cascadeEntryId: String = "",
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "https://77.88.8.8/dns-query",
    val wifiRulesOn: Boolean = false,
    val wifiHome: List<String> = emptyList(),
    /** Unknown Wi-Fi networks: connect automatically. */
    val wifiOtherConnect: Boolean = false,
    val language: String = "",
    /** Hysteria 2 through the official client (full build) instead of sing-box's own. */
    val hysteriaOfficial: Boolean = false,
    /** "Мои серверы": own VPSes set up over SSH. */
    val myServers: List<MyServer> = emptyList(),
)

/** Settings in one JSON file in the app's private folder; every change is saved at once. */
object Store {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    val settings = MutableStateFlow(Settings())
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, "settings.json")
        settings.value = runCatching { json.decodeFromString<Settings>(file.readText()) }.getOrDefault(Settings())
    }

    @Synchronized
    fun update(change: (Settings) -> Settings) {
        val next = change(settings.value)
        settings.value = next
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(Settings.serializer(), next))
            tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
        }
    }

    fun activeServer(): Server? = settings.value.let { s -> s.servers.firstOrNull { it.id == s.activeId } ?: s.servers.firstOrNull() }

    fun server(id: String): Server? = settings.value.servers.firstOrNull { it.id == id }

    fun addServers(list: List<Server>) = update { s ->
        val all = s.servers + list
        s.copy(servers = all, activeId = s.activeId.ifBlank { all.firstOrNull()?.id ?: "" })
    }
}
