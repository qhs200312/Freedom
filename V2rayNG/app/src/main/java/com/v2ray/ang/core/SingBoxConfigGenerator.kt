package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import java.io.File
import java.net.URI

object SingBoxConfigGenerator {
    private val wellKnownDnsIps = mapOf(
        "cloudflare-dns.com" to "1.1.1.1",
        "dns.google" to "8.8.8.8",
        "dns.alidns.com" to "223.5.5.5",
        "doh.pub" to "1.12.12.12",
    )
    private val googleDomains = listOf(
        "google.com", "googleapis.com", "googleapis.cn", "gstatic.com",
        "googleusercontent.com", "ggpht.com", "googlevideo.com", "youtube.com",
        "youtu.be", "ytimg.com"
    )
    private val stunDomains = listOf(
        "full:stun.l.google.com",
        "full:stun1.l.google.com",
        "full:stun2.l.google.com",
        "full:stun3.l.google.com",
        "full:stun4.l.google.com",
        "full:stun.cloudflare.com",
    )
    fun generate(
        profile: NodeProfile,
        ruleSetDir: String? = null,
        enableTun: Boolean = false,
    ): String {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val routingRules = MmkvManager.decodeRoutingRulesets().orEmpty()
        return generate(
        profile = profile,
        socksPort = SettingsManager.getSocksPort(),
        httpPort = SettingsManager.getHttpPort(),
        logLevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warn",
        socksUsername = SettingsManager.getSocksUsername(),
        socksPassword = SettingsManager.getSocksPassword(),
        routingRules = routingRules,
        blockGoogleLocation = MmkvManager.decodeSettingsBool(AppConfig.PREF_BLOCK_GOOGLE_LOCATION_ENDPOINTS, true),
        blockGoogleMaps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BLOCK_GOOGLE_MAPS_SERVICES, true),
        strictBlockGoogleMapsSdk = MmkvManager.decodeSettingsBool(
            AppConfig.PREF_STRICT_BLOCK_GOOGLE_MAPS_SDK_ENDPOINTS,
            AppConfig.DEFAULT_STRICT_BLOCK_GOOGLE_MAPS_SDK_ENDPOINTS,
        ),
        localDnsEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED, true),
        remoteDnsServers = SettingsManager.getRemoteDnsServers(),
        domesticDnsServers = SettingsManager.getDomesticDnsServers(),
        ruleSetDir = ruleSetDir,
        enableTun = enableTun,
        tunMtu = SettingsManager.getVpnMtu(),
        tunDnsServers = SettingsManager.getVpnDnsServers().filter(Utils::isPureIpAddress),
        tunRoutes = buildList {
            if (SettingsManager.routingRulesetsBypassLan()) addAll(AppConfig.ROUTED_IP_LIST)
            else add("0.0.0.0/0")
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED)) {
                if (SettingsManager.routingRulesetsBypassLan()) {
                    add("2000::/3")
                    add("fc00::/18")
                } else add("::/0")
            }
        },
        tunAddresses = buildList {
            add("${vpnConfig.ipv4Client}/30")
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED)) {
                add("${vpnConfig.ipv6Client}/126")
            }
        },
        )
    }

    fun unsupportedRoutingReason(): String? =
        unsupportedRoutingReason(MmkvManager.decodeRoutingRulesets().orEmpty())

    internal fun generate(
        profile: NodeProfile,
        socksPort: Int,
        httpPort: Int,
        logLevel: String,
        socksUsername: String? = null,
        socksPassword: String? = null,
        routingRules: List<RulesetItem> = emptyList(),
        blockGoogleLocation: Boolean = false,
        blockGoogleMaps: Boolean = false,
        strictBlockGoogleMapsSdk: Boolean = AppConfig.DEFAULT_STRICT_BLOCK_GOOGLE_MAPS_SDK_ENDPOINTS,
        localDnsEnabled: Boolean = false,
        @Suppress("UNUSED_PARAMETER") fakeDnsEnabled: Boolean = false,
        remoteDnsServers: List<String> = emptyList(),
        domesticDnsServers: List<String> = emptyList(),
        ruleSetDir: String? = null,
        enableTun: Boolean = false,
        tunMtu: Int = AppConfig.VPN_MTU,
        tunDnsServers: List<String> = listOf("1.1.1.1"),
        tunRoutes: List<String> = listOf("0.0.0.0/0"),
        tunAddresses: List<String> = listOf("10.10.14.1/30"),
    ): String {
        require(CoreSelector.canGenerateSingBox(profile)) {
            "Profile contains Xray-only settings and cannot be represented by sing-box"
        }
        val routingError = unsupportedRoutingReason(routingRules)
        require(routingError == null) { routingError.orEmpty() }

        val inboundUser = socksUsername?.let { username ->
            JsonObject().apply {
                addProperty("username", username)
                addProperty("password", socksPassword.orEmpty())
            }
        }
        val root = JsonObject().apply {
            add("log", JsonObject().apply {
                addProperty("level", logLevel)
            })
            val needsServerResolver = !Utils.isPureIpAddress(profile.server)
            if (localDnsEnabled || needsServerResolver) {
                val dns = if (localDnsEnabled) buildDns(routingRules, remoteDnsServers, domesticDnsServers, false)
                    else JsonObject().apply {
                        add("servers", JsonArray())
                        addProperty("final", "bootstrap-local")
                    }
                if (needsServerResolver) {
                    dns.getAsJsonArray("servers").add(JsonObject().apply {
                        addProperty("type", "local")
                        addProperty("tag", "bootstrap-local")
                    })
                }
                add("dns", dns)
            }
            if (profile.protocol == com.v2ray.ang.enums.EConfigType.WIREGUARD) {
                add("endpoints", JsonArray().apply { add(wireGuardEndpoint(profile)) })
            }
            add("inbounds", JsonArray().apply {
                if (enableTun) {
                    add(JsonObject().apply {
                        addProperty("type", "tun")
                        addProperty("tag", "tun-in")
                        add("address", JsonArray().apply { tunAddresses.forEach(::add) })
                        addProperty("mtu", tunMtu)
                        addProperty("auto_route", true)
                        add("route_address", JsonArray().apply { tunRoutes.forEach(::add) })
                        addProperty("dns_mode", "native")
                        add("dns_address", JsonArray().apply { tunDnsServers.forEach(::add) })
                        addProperty("strict_route", false)
                        addProperty("stack", "gvisor")
                    })
                }
                add(JsonObject().apply {
                    addProperty("type", "mixed")
                    addProperty("tag", "socks-in")
                    addProperty("listen", "127.0.0.1")
                    addProperty("listen_port", socksPort)
                    inboundUser?.let { add("users", JsonArray().apply { add(it.deepCopy()) }) }
                })
                if (httpPort != socksPort) {
                    add(JsonObject().apply {
                        addProperty("type", "http")
                        addProperty("tag", "http-in")
                        addProperty("listen", "127.0.0.1")
                        addProperty("listen_port", httpPort)
                        inboundUser?.let { add("users", JsonArray().apply { add(it.deepCopy()) }) }
                    })
                }
            })
            add("outbounds", JsonArray().apply {
                if (profile.protocol != com.v2ray.ang.enums.EConfigType.WIREGUARD) {
                    add(proxyOutbound(profile).apply {
                        if (needsServerResolver) addProperty("domain_resolver", "bootstrap-local")
                    })
                }
                add(JsonObject().apply {
                    addProperty("type", "direct")
                    addProperty("tag", "direct")
                })
            })
            add("route", buildRoute(
                routingRules,
                blockGoogleLocation,
                blockGoogleMaps,
                strictBlockGoogleMapsSdk,
                localDnsEnabled,
                ruleSetDir,
            ))
        }
        return JsonUtil.toJsonPretty(root) ?: root.toString()
    }

    private fun proxyOutbound(profile: NodeProfile): JsonObject = when (profile.protocol) {
        com.v2ray.ang.enums.EConfigType.HYSTERIA2 -> hysteria2Outbound(profile)
        com.v2ray.ang.enums.EConfigType.VMESS -> standardOutbound(profile, "vmess").apply {
            addProperty("uuid", profile.password)
            addProperty("security", profile.method?.ifBlank { "auto" } ?: "auto")
            applyTlsAndTransport(profile)
        }
        com.v2ray.ang.enums.EConfigType.VLESS -> standardOutbound(profile, "vless").apply {
            addProperty("uuid", profile.password)
            profile.flow?.takeIf { it.isNotBlank() }?.let { addProperty("flow", it) }
            applyTlsAndTransport(profile)
        }
        com.v2ray.ang.enums.EConfigType.TROJAN -> standardOutbound(profile, "trojan").apply {
            addProperty("password", profile.password)
            applyTlsAndTransport(profile)
        }
        com.v2ray.ang.enums.EConfigType.SHADOWSOCKS -> standardOutbound(profile, "shadowsocks").apply {
            addProperty("method", normalizeShadowsocksMethod(profile.method.orEmpty()))
            addProperty("password", profile.password)
        }
        com.v2ray.ang.enums.EConfigType.SOCKS -> standardOutbound(profile, "socks").apply {
            addProperty("version", "5")
            profile.username?.takeIf { it.isNotBlank() }?.let { addProperty("username", it) }
            profile.password?.takeIf { it.isNotBlank() }?.let { addProperty("password", it) }
        }
        com.v2ray.ang.enums.EConfigType.HTTP -> standardOutbound(profile, "http").apply {
            profile.username?.takeIf { it.isNotBlank() }?.let { addProperty("username", it) }
            profile.password?.takeIf { it.isNotBlank() }?.let { addProperty("password", it) }
        }
        else -> error("Unsupported sing-box protocol: ${profile.protocol}")
    }

    private fun standardOutbound(profile: NodeProfile, type: String) = JsonObject().apply {
        addProperty("type", type)
        addProperty("tag", AppConfig.TAG_PROXY)
        addProperty("server", profile.server)
        addProperty("server_port", profile.port)
    }

    private fun JsonObject.applyTlsAndTransport(profile: NodeProfile) {
        if (profile.tlsEnabled) add("tls", tlsOptions(profile))
        buildTransport(profile)?.let { add("transport", it) }
    }

    private fun tlsOptions(profile: NodeProfile) = JsonObject().apply {
        addProperty("enabled", true)
        profile.sni?.takeIf { it.isNotBlank() }?.let { addProperty("server_name", it) }
        addProperty("insecure", profile.insecure)
        if (profile.alpn.isNotEmpty()) {
            add("alpn", JsonArray().apply { profile.alpn.forEach(::add) })
        }
        profile.fingerprint?.takeIf { it.isNotBlank() }?.let {
            add("utls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("fingerprint", it)
            })
        }
        if (profile.security.equals(AppConfig.REALITY, true)) {
            add("reality", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("public_key", profile.realityPublicKey)
                profile.realityShortId?.takeIf { it.isNotBlank() }?.let { addProperty("short_id", it) }
            })
        }
        if (profile.echConfigList.isNotEmpty()) {
            add("ech", JsonObject().apply {
                addProperty("enabled", true)
                add("config", JsonArray().apply { profile.echConfigList.forEach(::add) })
            })
        }
    }

    private fun buildTransport(profile: NodeProfile): JsonObject? = when (profile.network.lowercase()) {
        "", "tcp" -> null
        "ws" -> JsonObject().apply {
            addProperty("type", "ws")
            profile.path?.takeIf { it.isNotBlank() }?.let { addProperty("path", it) }
            addHostHeader(profile.host)
        }
        "httpupgrade" -> JsonObject().apply {
            addProperty("type", "httpupgrade")
            profile.host?.takeIf { it.isNotBlank() }?.let { addProperty("host", it) }
            profile.path?.takeIf { it.isNotBlank() }?.let { addProperty("path", it) }
        }
        "http", "h2" -> JsonObject().apply {
            addProperty("type", "http")
            profile.host?.takeIf { it.isNotBlank() }?.let {
                add("host", JsonArray().apply { splitValues(it).forEach(::add) })
            }
            profile.path?.takeIf { it.isNotBlank() }?.let { addProperty("path", it) }
        }
        "grpc" -> JsonObject().apply {
            addProperty("type", "grpc")
            profile.serviceName?.takeIf { it.isNotBlank() }?.let { addProperty("service_name", it) }
        }
        else -> error("Unsupported sing-box transport: ${profile.network}")
    }

    private fun JsonObject.addHostHeader(host: String?) {
        host?.takeIf { it.isNotBlank() }?.let {
            add("headers", JsonObject().apply { addProperty("Host", it) })
        }
    }

    private fun wireGuardEndpoint(profile: NodeProfile) = JsonObject().apply {
        addProperty("type", "wireguard")
        addProperty("tag", AppConfig.TAG_PROXY)
        profile.wireGuardMtu?.takeIf { it > 0 }?.let { addProperty("mtu", it) }
        add("address", JsonArray().apply { profile.wireGuardLocalAddresses.forEach(::add) })
        addProperty("private_key", profile.wireGuardPrivateKey)
        add("peers", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("address", profile.server)
                addProperty("port", profile.port)
                addProperty("public_key", profile.wireGuardPublicKey)
                profile.wireGuardPreSharedKey?.takeIf { it.isNotBlank() }?.let {
                    addProperty("pre_shared_key", it)
                }
                add("allowed_ips", JsonArray().apply {
                    add("0.0.0.0/0")
                    add("::/0")
                })
                if (profile.wireGuardReserved.isNotEmpty()) {
                    add("reserved", JsonArray().apply { profile.wireGuardReserved.forEach(::add) })
                }
            })
        })
    }

    private fun normalizeShadowsocksMethod(method: String): String = when (method.lowercase()) {
        "plain" -> "none"
        "chacha20-poly1305" -> "chacha20-ietf-poly1305"
        "xchacha20-poly1305" -> "xchacha20-ietf-poly1305"
        else -> method.lowercase()
    }

    private fun splitValues(value: String): List<String> =
        value.split(',').map(String::trim).filter(String::isNotEmpty)

    private fun hysteria2Outbound(profile: NodeProfile) = JsonObject().apply {
        addProperty("type", "hysteria2")
        addProperty("tag", "proxy")
        // Keep QUIC datagrams below mobile/tethered path MTUs, including obfuscation overhead.
        addProperty("initial_packet_size", 1200)
        addProperty("disable_path_mtu_discovery", true)
        addProperty("server", profile.server)
        if (profile.hopPorts.isNullOrBlank()) {
            addProperty("server_port", profile.port)
        } else {
            add("server_ports", JsonArray().apply { add(toSingBoxPortRange(profile.hopPorts.orEmpty())) })
        }
        addProperty("password", profile.password)
        profile.upMbps?.takeIf { it > 0 }?.let { addProperty("up_mbps", it) }
        profile.downMbps?.takeIf { it > 0 }?.let { addProperty("down_mbps", it) }
        profile.hopInterval?.takeIf { it.isNotBlank() }?.let { addProperty("hop_interval", toDuration(it)) }
        profile.obfsPassword?.takeIf { it.isNotBlank() }?.let {
            add("obfs", JsonObject().apply {
                addProperty("type", "salamander")
                addProperty("password", it)
            })
        }
        add("tls", JsonObject().apply {
            addProperty("enabled", profile.tlsEnabled)
            profile.sni?.takeIf { it.isNotBlank() }?.let { addProperty("server_name", it) }
            addProperty("insecure", profile.insecure)
            if (profile.alpn.isNotEmpty()) {
                add("alpn", JsonArray().apply { profile.alpn.forEach(::add) })
            } else {
                add("alpn", JsonArray().apply { add("h3") })
            }
        })
    }

    private fun toSingBoxPortRange(value: String): String =
        value.trim().replace('-', ':').replace(" ", "")

    private fun toDuration(value: String): String {
        val trimmed = value.trim()
        if (trimmed.endsWith('s', true)) return trimmed
        return "${trimmed.toIntOrNull()?.coerceAtLeast(5) ?: 30}s"
    }

    private fun unsupportedRoutingReason(rules: List<RulesetItem>): String? {
        rules.filter { it.enabled }.forEach { rule ->
            if (rule.outboundTag !in AppConfig.BUILTIN_OUTBOUND_TAGS) {
                return "sing-box cannot map custom routing outbound '${rule.outboundTag}'"
            }
            if (!rule.process.isNullOrEmpty()) {
                return "sing-box SOCKS data plane cannot preserve process routing rule '${rule.remarks}'"
            }
            rule.domain.orEmpty().forEach { value ->
                if (value.startsWith("geosite:", true) &&
                    !value.equals(AppConfig.GEOSITE_PRIVATE, true) &&
                    !value.equals("geosite:google", true) &&
                    !value.equals(AppConfig.GEOSITE_CN, true)
                ) {
                    return "sing-box bundle has no compatible rule-set for '$value'"
                }
            }
            rule.ip.orEmpty().forEach { value ->
                if (value.startsWith("geoip:", true) &&
                    !value.equals(AppConfig.GEOIP_PRIVATE, true) &&
                    !value.equals("geoip:google", true) &&
                    !value.equals(AppConfig.GEOIP_CN, true)
                ) {
                    return "sing-box bundle has no compatible rule-set for '$value'"
                }
            }
        }
        return null
    }

    private fun buildDns(
        rules: List<RulesetItem>,
        remoteDnsServers: List<String>,
        domesticDnsServers: List<String>,
        @Suppress("UNUSED_PARAMETER") fakeDnsEnabled: Boolean,
    ): JsonObject {
        val remote = remoteDnsServers.ifEmpty { listOf(AppConfig.DNS_PROXY) }
        val domestic = domesticDnsServers.ifEmpty { listOf(AppConfig.DNS_DIRECT) }
        val remoteTags = remote.indices.map { "remote-dns-$it" }
        val domesticTags = domestic.indices.map { "domestic-dns-$it" }
        val needsBootstrap = (remote + domestic).any(::dnsServerNeedsBootstrap)
        val bootstrapTag = "bootstrap-dns"
        val bootstrapAddress = domestic.firstOrNull { Utils.isPureIpAddress(it.trim()) }
            ?: AppConfig.DNS_DIRECT

        return JsonObject().apply {
            add("servers", JsonArray().apply {
                if (needsBootstrap) {
                    add(JsonObject().apply {
                        addProperty("type", "udp")
                        addProperty("tag", bootstrapTag)
                        addProperty("server", bootstrapAddress)
                    })
                }
                remote.forEachIndexed { index, address ->
                    add(buildDnsServer(address, remoteTags[index], AppConfig.TAG_PROXY, bootstrapTag))
                }
                domestic.forEachIndexed { index, address ->
                    add(buildDnsServer(address, domesticTags[index], null, bootstrapTag))
                }
            })
            add("rules", JsonArray().apply {
                val domesticTag = domesticTags.first()
                rules.asSequence()
                    .filter { it.enabled && it.outboundTag == AppConfig.TAG_DIRECT }
                    .mapNotNull { buildDnsDomainRule(it.domain.orEmpty(), domesticTag) }
                    .forEach(::add)
            })
            addProperty("final", remoteTags.first())
        }
    }

    private fun buildDnsServer(
        address: String,
        tag: String,
        detour: String?,
        bootstrapTag: String,
    ): JsonObject {
        val value = address.trim()
        if (value.equals("localhost", ignoreCase = true)) {
            return JsonObject().apply {
                addProperty("type", "local")
                addProperty("tag", tag)
                detour?.let { addProperty("detour", it) }
            }
        }

        val uri = value.takeIf { it.contains("://") }?.let { runCatching { URI(it) }.getOrNull() }
        val type = when (uri?.scheme?.lowercase()) {
            "https" -> "https"
            "h3" -> "h3"
            "tcp" -> "tcp"
            "tls" -> "tls"
            "quic" -> "quic"
            else -> "udp"
        }
        val originalHost = uri?.host?.takeIf { it.isNotBlank() } ?: value
        val server = wellKnownDnsIps[originalHost.lowercase()] ?: originalHost

        return JsonObject().apply {
            addProperty("type", type)
            addProperty("tag", tag)
            addProperty("server", server)
            uri?.port?.takeIf { it > 0 }?.let { addProperty("server_port", it) }
            if (type == "https" || type == "h3") {
                uri?.rawPath?.takeIf { it.isNotBlank() && it != "/" }?.let { addProperty("path", it) }
            }
            if (server != originalHost && type in setOf("https", "h3", "tls", "quic")) {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("server_name", originalHost)
                })
            }
            detour?.let { addProperty("detour", it) }
            if (dnsServerNeedsBootstrap(value)) {
                add("domain_resolver", JsonObject().apply {
                    addProperty("server", bootstrapTag)
                })
            }
        }
    }

    private fun dnsServerNeedsBootstrap(address: String): Boolean {
        val value = address.trim()
        if (value.equals("localhost", ignoreCase = true)) return false
        if (Utils.isPureIpAddress(value)) return false
        var host = value.takeIf { it.contains("://") }
            ?.let { runCatching { URI(it).host }.getOrNull() }
            ?: value
        if (host.startsWith('[') && host.contains(']')) {
            host = host.substringAfter('[').substringBefore(']')
        } else if (host.count { it == ':' } == 1 && host.substringAfterLast(':').toIntOrNull() != null) {
            host = host.substringBeforeLast(':')
        }
        if (host.lowercase() in wellKnownDnsIps) return false
        return host.isNotBlank() && !Utils.isPureIpAddress(host)
    }

    private fun buildDnsDomainRule(domains: List<String>, server: String): JsonObject? {
        val rule = JsonObject()
        addDomainConditions(rule, domains)
        if (rule.entrySet().isEmpty()) return null
        rule.addProperty("action", "route")
        rule.addProperty("server", server)
        return rule
    }

    private fun buildRoute(
        rules: List<RulesetItem>,
        blockGoogleLocation: Boolean,
        blockGoogleMaps: Boolean,
        strictBlockGoogleMapsSdk: Boolean,
        localDnsEnabled: Boolean,
        ruleSetDir: String?,
    ): JsonObject {
        val outputRules = JsonArray()
        if (localDnsEnabled) {
            outputRules.add(JsonObject().apply {
                addProperty("port", 53)
                addProperty("action", "hijack-dns")
            })
        }
        outputRules.add(JsonObject().apply {
            addProperty("action", "sniff")
            add("sniffer", JsonArray().apply {
                add("tls")
                add("http")
                add("quic")
            })
            addProperty("timeout", "300ms")
        })
        outputRules.add(JsonObject().apply {
            addProperty("network", "udp")
            addProperty("action", "sniff")
            add("sniffer", JsonArray().apply { add("stun") })
        })
        outputRules.add(JsonObject().apply {
            addProperty("protocol", "stun")
            addProperty("action", "route")
            addProperty("outbound", AppConfig.TAG_PROXY)
        })
        convertRule(RulesetItem(
            domain = stunDomains,
            network = "udp",
            outboundTag = AppConfig.TAG_PROXY,
        ))?.let(outputRules::add)
        if (blockGoogleLocation) {
            outputRules.add(domainRule(AppConfig.GEMINI_LIVE_DOMAINS, AppConfig.TAG_PROXY, "443"))
            outputRules.add(domainRule(AppConfig.GOOGLE_LOCATION_ENDPOINT_DOMAINS, "block", "443"))
        }
        if (blockGoogleMaps) {
            outputRules.add(domainRule(
                AppConfig.GOOGLE_MAPS_SHARED_SDK_DOMAINS,
                if (strictBlockGoogleMapsSdk) "block" else AppConfig.TAG_PROXY,
                "443",
            ))
            outputRules.add(domainRule(AppConfig.GOOGLE_MAPS_SERVICE_DOMAINS, "block", "443"))
        }

        var finalOutbound = "proxy"
        rules.filter { it.enabled }.forEach { rule ->
            val isCatchAll = rule.domain.isNullOrEmpty() && rule.ip.isNullOrEmpty() &&
                rule.process.isNullOrEmpty() && rule.protocol.isNullOrEmpty() &&
                rule.network.isNullOrBlank() && (rule.port == null || rule.port == "0-65535")
            if (isCatchAll) {
                finalOutbound = rule.outboundTag.ifBlank { "proxy" }
            } else {
                convertRule(rule)?.let(outputRules::add)
            }
        }
        return JsonObject().apply {
            add("rules", outputRules)
            addProperty("final", if (finalOutbound == AppConfig.TAG_BLOCKED) "direct" else finalOutbound)
            addProperty("auto_detect_interface", true)
            val requiredRuleSets = requiredRuleSets(rules)
            if (requiredRuleSets.isNotEmpty()) {
                require(!ruleSetDir.isNullOrBlank()) { "sing-box rule-set directory is unavailable" }
                add("rule_set", JsonArray().apply {
                    requiredRuleSets.forEach { (tag, fileName) ->
                        val path = File(ruleSetDir, fileName)
                        require(path.isFile) { "Missing bundled sing-box rule-set: $fileName" }
                        add(JsonObject().apply {
                            addProperty("type", "local")
                            addProperty("tag", tag)
                            addProperty("format", "binary")
                            addProperty("path", path.absolutePath)
                        })
                    }
                })
            }
        }
    }

    private fun requiredRuleSets(rules: List<RulesetItem>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        rules.filter { it.enabled }.forEach { rule ->
            if (rule.ip.orEmpty().any { it.equals(AppConfig.GEOIP_CN, true) }) {
                result["geoip-cn"] = AppConfig.SING_GEOIP_CN_SRS
            }
            if (rule.domain.orEmpty().any { it.equals(AppConfig.GEOSITE_CN, true) }) {
                result["geosite-cn"] = AppConfig.SING_GEOSITE_CN_SRS
            }
        }
        return result
    }

    private fun domainRule(domains: List<String>, outbound: String, port: String? = null): JsonObject =
        requireNotNull(convertRule(RulesetItem(domain = domains, outboundTag = outbound, port = port)))

    private fun convertRule(rule: RulesetItem): JsonObject? {
        val result = JsonObject().apply {
            addDomainConditions(this, rule.domain.orEmpty())

            val ipCidr = JsonArray()
            rule.ip.orEmpty().forEach { value ->
                if (value.equals(AppConfig.GEOIP_PRIVATE, true)) addProperty("ip_is_private", true)
                else if (value.equals("geoip:google", true)) Unit
                else if (value.equals(AppConfig.GEOIP_CN, true)) addRuleSet("geoip-cn")
                else if (!value.startsWith("geoip:", true)) ipCidr.add(value)
            }
            if (ipCidr.size() > 0) add("ip_cidr", ipCidr)
            rule.network?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.let {
                if (it.isNotEmpty()) add("network", JsonArray().apply { it.forEach(::add) })
            }
            rule.protocol?.takeIf { it.isNotEmpty() }?.let {
                add("protocol", JsonArray().apply { it.forEach(::add) })
            }
            rule.port?.takeIf { it.isNotBlank() && it != "0-65535" }?.let { value ->
                val single = value.toIntOrNull()
                if (single != null) add("port", JsonArray().apply { add(single) })
                else add("port_range", JsonArray().apply { add(value.replace('-', ':')) })
            }
        }
        if (result.entrySet().isEmpty()) return null
        if (rule.outboundTag == AppConfig.TAG_BLOCKED) {
            result.addProperty("action", "reject")
        } else {
            result.addProperty("action", "route")
            result.addProperty("outbound", rule.outboundTag.ifBlank { AppConfig.TAG_PROXY })
        }
        return result
    }

    private fun addDomainConditions(target: JsonObject, domains: List<String>) = with(target) {
        val exact = JsonArray()
        val suffix = JsonArray()
        val keyword = JsonArray()
        val regex = JsonArray()
        domains.forEach { value ->
            when {
                value.equals(AppConfig.GEOSITE_PRIVATE, true) ->
                    listOf("lan", "local", "localdomain", "home.arpa").forEach(suffix::add)
                value.startsWith("full:", true) -> exact.add(value.substringAfter(':'))
                value.startsWith("keyword:", true) -> keyword.add(value.substringAfter(':'))
                value.startsWith("regexp:", true) -> regex.add(value.substringAfter(':'))
                value.startsWith("domain:", true) -> suffix.add(value.substringAfter(':'))
                value.equals("geosite:google", true) -> googleDomains.forEach(suffix::add)
                value.equals(AppConfig.GEOSITE_CN, true) -> addRuleSet("geosite-cn")
                !value.startsWith("geosite:", true) -> suffix.add(value)
            }
        }
        if (exact.size() > 0) add("domain", exact)
        if (suffix.size() > 0) add("domain_suffix", suffix)
        if (keyword.size() > 0) add("domain_keyword", keyword)
        if (regex.size() > 0) add("domain_regex", regex)
    }

    private fun JsonObject.addRuleSet(tag: String) {
        val values = getAsJsonArray("rule_set") ?: JsonArray().also { add("rule_set", it) }
        if (values.none { it.asString == tag }) values.add(tag)
    }
}
