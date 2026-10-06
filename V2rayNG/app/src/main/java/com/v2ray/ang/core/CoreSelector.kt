package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.PreferredCore
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager

enum class RuntimeCore {
    XRAY,
    SING_BOX,
}

object CoreSelector {
    val configurableProtocols = listOf(
        EConfigType.VMESS,
        EConfigType.VLESS,
        EConfigType.TROJAN,
        EConfigType.SHADOWSOCKS,
        EConfigType.SOCKS,
        EConfigType.HTTP,
        EConfigType.WIREGUARD,
        EConfigType.HYSTERIA2,
    )

    fun preferenceKey(protocol: EConfigType): String? = when (protocol) {
        EConfigType.HYSTERIA2 -> AppConfig.PREF_HY2_DEFAULT_CORE
        in configurableProtocols -> AppConfig.PREF_PROTOCOL_DEFAULT_CORE_PREFIX + protocol.name.lowercase()
        else -> null
    }

    fun builtInDefault(protocol: EConfigType): PreferredCore =
        if (protocol == EConfigType.HYSTERIA2) PreferredCore.SING_BOX else PreferredCore.XRAY

    fun selectConfigured(profile: NodeProfile): RuntimeCore {
        val configuredDefault = PreferredCore.parse(
            preferenceKey(profile.protocol)?.let(MmkvManager::decodeSettingsString)
                ?: builtInDefault(profile.protocol).name
        )
        return select(profile, configuredDefault)
    }

    fun select(
        profile: NodeProfile,
        protocolDefaultCore: PreferredCore = builtInDefault(profile.protocol),
    ): RuntimeCore {
        return when (profile.preferredCore) {
            PreferredCore.XRAY -> RuntimeCore.XRAY
            PreferredCore.SING_BOX ->
                if (canGenerateSingBox(profile)) RuntimeCore.SING_BOX else RuntimeCore.XRAY
            PreferredCore.AUTO -> selectAutomatic(profile, protocolDefaultCore)
        }
    }

    private fun selectAutomatic(profile: NodeProfile, protocolDefaultCore: PreferredCore): RuntimeCore {
        return when (protocolDefaultCore) {
            PreferredCore.XRAY -> RuntimeCore.XRAY
            PreferredCore.SING_BOX ->
                if (canGenerateSingBox(profile)) RuntimeCore.SING_BOX else RuntimeCore.XRAY
            PreferredCore.AUTO -> if (canGenerateSingBox(profile)) RuntimeCore.SING_BOX else RuntimeCore.XRAY
        }
    }

    fun canGenerateSingBox(profile: NodeProfile): Boolean {
        if (profile.protocol !in configurableProtocols) return false
        if (profile.server.isBlank() || profile.port !in 1..65535) return false
        if (!profile.pinnedCertificateSha256.isNullOrBlank()) return false
        if (!profile.verifyPeerCertByName.isNullOrBlank()) return false
        if (!profile.browserDialerMode.isNullOrBlank()) return false
        if (!profile.xrayFinalMask.isNullOrBlank() && profile.protocol != EConfigType.HYSTERIA2) return false

        if (profile.protocol == EConfigType.WIREGUARD) {
            return !profile.wireGuardPrivateKey.isNullOrBlank() &&
                !profile.wireGuardPublicKey.isNullOrBlank() &&
                profile.wireGuardLocalAddresses.isNotEmpty() &&
                profile.wireGuardReserved.size in setOf(0, 3)
        }

        if (profile.protocol in setOf(EConfigType.VMESS, EConfigType.VLESS, EConfigType.TROJAN)) {
            if (!supportsV2RayTransport(profile)) return false
            if (profile.security.orEmpty().lowercase() !in setOf("", "tls", "reality")) return false
            if (!profile.realitySpiderX.isNullOrBlank() || !profile.realityMldsa65Verify.isNullOrBlank()) return false
        }

        if (profile.protocol == EConfigType.VLESS && profile.method.orEmpty().lowercase() !in setOf("", "none")) return false
        if (profile.protocol == EConfigType.TROJAN && profile.password.isNullOrBlank()) return false
        if (profile.protocol == EConfigType.VMESS && profile.password.isNullOrBlank()) return false
        if (profile.protocol == EConfigType.VLESS && profile.password.isNullOrBlank()) return false

        if (profile.protocol == EConfigType.SHADOWSOCKS) {
            if (profile.password.isNullOrBlank() || profile.method.isNullOrBlank()) return false
            if (profile.network !in setOf("", "tcp")) return false
            if (profile.headerType.orEmpty().lowercase() !in setOf("", "none")) return false
            if (profile.tlsEnabled) return false
        }

        if (profile.xrayCongestion.equals("force-brutal", true)) return false
        if (profile.hopRemoteIPs.isNotEmpty()) return false
        if (!profile.hopMode.isNullOrBlank() && !profile.hopMode.equals("intervalRemote", true)) return false
        val finalMask = profile.xrayFinalMask.orEmpty()
        if (finalMask.isBlank()) return true
        val root = JsonUtil.parseString(finalMask)?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        if (root.getAsJsonArray("tcp")?.size()?.let { it > 0 } == true) return false
        val allowedMasks = setOf("salamander", "udphop")
        if (root.getAsJsonArray("udp")?.any { element ->
                !element.isJsonObject || element.asJsonObject.get("type")?.asString?.lowercase() !in allowedMasks
            } == true
        ) return false
        val allowedQuicKeys = setOf("congestion", "brutalUp", "brutalDown", "udpHop")
        return root.getAsJsonObject("quicParams")?.entrySet()?.all { it.key in allowedQuicKeys } != false
    }

    private fun supportsV2RayTransport(profile: NodeProfile): Boolean = when (profile.network.lowercase()) {
        "", "tcp" -> profile.headerType.orEmpty().lowercase() in setOf("", "none")
        "ws", "httpupgrade", "http", "h2" -> true
        "grpc" -> profile.authority.isNullOrBlank() && profile.grpcMode.orEmpty().lowercase() in setOf("", "gun")
        else -> false
    }
}
