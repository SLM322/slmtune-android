package com.slmtune.app.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI

/**
 * The sing-box config for one connection (Android counterpart of SingBoxConfig.Build in the Windows version).
 * Order of the route rules matters: blocking first, then the user's own rules, AI and games, then the preset.
 */
object ConfigBuilder {
    private fun arr(items: Collection<String>) = JsonArray(items.distinct().map { JsonPrimitive(it) })

    /** "https://1.1.1.1/dns-query" → a sing-box 1.12+ DNS server object. */
    private fun dnsServer(tag: String, url: String, detour: String?): JsonObject {
        val u = runCatching { URI(url) }.getOrNull()
        return buildJsonObject {
            when (u?.scheme) {
                "https" -> { put("type", "https"); put("server", u.host); if (!u.path.isNullOrEmpty() && u.path != "/dns-query") put("path", u.path) }
                "tls" -> { put("type", "tls"); put("server", u.host) }
                else -> { put("type", "udp"); put("server", url.substringAfter("://").ifBlank { "1.1.1.1" }) }
            }
            put("tag", tag)
            if (detour != null) put("detour", detour)
        }
    }

    /**
     * [backends]: server id → SOCKS port of its separate core. [mixedPort] > 0 adds a local SOCKS/HTTP inbound
     * for the app's own requests (the app itself stays outside the tunnel while separate cores run).
     */
    fun build(s: Settings, server: Server, rulesDir: java.io.File, backends: Map<String, Int> = emptyMap(), mixedPort: Int = 0): String {
        val chain = Outbounds.chain(s, server, backends)
        val udp = ShareLinks.hasUdp(server) && Outbounds.hops(s, server).all(ShareLinks::hasUdp)
        val selective = s.preset == "blocked"

        // ---- rule sets (local files the app keeps up to date) ----
        val ruleSets = mutableListOf<JsonObject>()
        fun local(tag: String, path: String, binary: Boolean) = ruleSets.add(buildJsonObject {
            put("type", "local"); put("tag", tag); put("format", if (binary) "binary" else "source"); put("path", path)
        })
        val blockTags = Lists.blocks.filter { it.id in s.blockLists && Lists.blockFile(rulesDir, it).exists() }.map { b ->
            "block-${b.id}".also { local(it, Lists.blockFile(rulesDir, b).path, true) }
        }
        val presetTags = when (s.preset) {
            "blocked" -> listOf(Lists.BLOCKED)
            "except_ru" -> listOf(Lists.RU_DOMAINS, Lists.RU_IPS)
            else -> emptyList()
        }.filter { Lists.has(rulesDir, it) }.onEach { local(it, Lists.ruleFile(rulesDir, it).path, false) }

        // ---- services (AI, games, calls) ----
        val on = { d: ServiceDef -> d.id !in s.disabledServices }
        val aiDomains = Catalog.inGroup(Catalog.AI).filter(on).flatMap { it.domains } + Catalog.aiAlways
        val aiApps = Catalog.inGroup(Catalog.AI).filter(on).flatMap { it.packages }
        val gameApps = Catalog.inGroup(Catalog.GAME).filter(on).flatMap { it.packages }
        val callApps = Catalog.inGroup(Catalog.CALL).filter(on).flatMap { it.packages }

        val rules = buildJsonArray {
            add(buildJsonObject { put("action", "sniff") })
            add(buildJsonObject { put("protocol", "dns"); put("action", "hijack-dns") })
            add(buildJsonObject { put("ip_is_private", true); put("action", "route"); put("outbound", "direct") })
            if (blockTags.isNotEmpty()) add(buildJsonObject { put("rule_set", arr(blockTags)); put("action", "reject") })
            // The user's own rules win over everything below.
            for (r in s.customRules.filter { it.enabled }) {
                fun target(o: kotlinx.serialization.json.JsonObjectBuilder) {
                    if (r.action == "block") o.put("action", "reject")
                    else { o.put("action", "route"); o.put("outbound", if (r.action == "direct") "direct" else "proxy") }
                }
                if (r.domains.isNotEmpty()) add(buildJsonObject { put("domain_suffix", arr(r.domains)); target(this) })
                if (r.ips.isNotEmpty()) add(buildJsonObject { put("ip_cidr", arr(r.ips.map { if (it.contains('/')) it else if (it.contains(':')) "$it/128" else "$it/32" })); target(this) })
                if (r.packages.isNotEmpty()) add(buildJsonObject { put("package_name", arr(r.packages)); target(this) })
            }
            // A server without UDP: QUIC is refused at once so browsers fall back to TCP instead of leaking.
            if (!udp) add(buildJsonObject { putJsonArray("network") { add(JsonPrimitive("udp")) }; putJsonArray("port") { add(JsonPrimitive(443)) }; put("action", "reject") })
            if (s.aiViaVpn) {
                add(buildJsonObject { put("domain_suffix", arr(aiDomains)); put("action", "route"); put("outbound", "proxy") })
                if (aiApps.isNotEmpty()) add(buildJsonObject { put("package_name", arr(aiApps)); put("action", "route"); put("outbound", "proxy") })
            }
            if (callApps.isNotEmpty() && udp) add(buildJsonObject {
                put("package_name", arr(callApps)); putJsonArray("network") { add(JsonPrimitive("udp")) }; put("action", "route"); put("outbound", "proxy")
            })
            if (gameApps.isNotEmpty() && s.gamesRoute.isNotEmpty()) {
                if (s.gamesRoute == "vpn" && udp) add(buildJsonObject {
                    put("package_name", arr(gameApps)); putJsonArray("network") { add(JsonPrimitive("udp")) }; put("action", "route"); put("outbound", "proxy")
                })
                add(buildJsonObject { put("package_name", arr(gameApps)); put("action", "route"); put("outbound", "direct") })
            }
            if (!udp) add(buildJsonObject { putJsonArray("network") { add(JsonPrimitive("udp")) }; put("action", "route"); put("outbound", "direct") })
            if (presetTags.isNotEmpty())
                add(buildJsonObject { put("rule_set", arr(presetTags)); put("action", "route"); put("outbound", if (selective) "proxy" else "direct") })
        }

        val dnsRules = buildJsonArray {
            if (blockTags.isNotEmpty()) add(buildJsonObject { put("rule_set", arr(blockTags)); put("action", "predefined"); put("rcode", "NXDOMAIN") })
            if (s.aiViaVpn) add(buildJsonObject { put("domain_suffix", arr(aiDomains)); put("action", "route"); put("server", "remote-dns") })
            val domainSets = presetTags.filter { it != Lists.RU_IPS }
            if (domainSets.isNotEmpty())
                add(buildJsonObject { put("rule_set", arr(domainSets)); put("action", "route"); put("server", if (selective) "remote-dns" else "direct-dns") })
        }

        val config = buildJsonObject {
            putJsonObject("log") { put("level", "warn"); put("timestamp", true) }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(dnsServer("remote-dns", s.remoteDns, "proxy"))
                    add(dnsServer("direct-dns", s.directDns, null))
                }
                put("rules", dnsRules)
                put("final", if (selective) "direct-dns" else "remote-dns")
                if (!s.ipv6) put("strategy", "ipv4_only")
            }
            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("type", "tun"); put("tag", "tun-in")
                    put("address", arr(if (s.ipv6) listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126") else listOf("172.19.0.1/30")))
                    put("mtu", 9000)
                    put("auto_route", true)
                    put("strict_route", true)
                    put("stack", "mixed")
                    // Local network (router, printers, casting) stays outside the tunnel.
                    if (s.lanBypass) put("route_exclude_address", arr(listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4")))
                })
                if (mixedPort > 0) add(buildJsonObject { put("type", "mixed"); put("tag", "mixed-in"); put("listen", "127.0.0.1"); put("listen_port", mixedPort) })
            }
            putJsonArray("outbounds") {
                chain.outbounds.forEach { add(it) }
                add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
            }
            if (chain.endpoints.isNotEmpty()) put("endpoints", JsonArray(chain.endpoints))
            putJsonObject("route") {
                put("rules", rules)
                if (ruleSets.isNotEmpty()) put("rule_set", JsonArray(ruleSets))
                put("final", if (selective) "direct" else "proxy")
                put("auto_detect_interface", true)
                put("default_domain_resolver", "direct-dns")
            }
            putJsonObject("experimental") {
                putJsonObject("cache_file") { put("enabled", true) }
            }
        }
        return config.toString()
    }
}
