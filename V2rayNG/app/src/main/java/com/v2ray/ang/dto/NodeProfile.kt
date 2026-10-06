package com.v2ray.ang.dto

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.google.gson.JsonObject
import com.v2ray.ang.util.JsonUtil

enum class PreferredCore {
    AUTO,
    XRAY,
    SING_BOX;

    companion object {
        fun parse(value: String?): PreferredCore =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}

/** Core-neutral projection of the persisted v2rayNG profile. */
data class NodeProfile(
    val id: String? = null,
    val remarks: String,
    val protocol: EConfigType,
    val server: String,
    val port: Int,
    val password: String? = null,
    val username: String? = null,
    val method: String? = null,
    val flow: String? = null,
    val network: String = "tcp",
    val headerType: String? = null,
    val host: String? = null,
    val path: String? = null,
    val grpcMode: String? = null,
    val serviceName: String? = null,
    val authority: String? = null,
    val security: String? = null,
    val tlsEnabled: Boolean,
    val sni: String? = null,
    val insecure: Boolean,
    val alpn: List<String> = emptyList(),
    val obfsType: String? = null,
    val obfsPassword: String? = null,
    val upMbps: Int? = null,
    val downMbps: Int? = null,
    val hopPorts: String? = null,
    val hopInterval: String? = null,
    val fingerprint: String? = null,
    val pinnedCertificateSha256: String? = null,
    val verifyPeerCertByName: String? = null,
    val echConfigList: List<String> = emptyList(),
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    val realitySpiderX: String? = null,
    val realityMldsa65Verify: String? = null,
    val browserDialerMode: String? = null,
    val wireGuardPrivateKey: String? = null,
    val wireGuardPublicKey: String? = null,
    val wireGuardPreSharedKey: String? = null,
    val wireGuardLocalAddresses: List<String> = emptyList(),
    val wireGuardReserved: List<Int> = emptyList(),
    val wireGuardMtu: Int? = null,
    val preferredCore: PreferredCore = PreferredCore.AUTO,
    val xrayFinalMask: String? = null,
    val hopMode: String? = null,
    val hopRemoteIPs: List<String> = emptyList(),
    val xrayCongestion: String? = null,
) {
    companion object {
        fun from(item: ProfileItem, id: String? = null): NodeProfile {
            val mask = FinalMaskFields.parse(item.finalMask)
            return NodeProfile(
            id = id,
            remarks = item.remarks,
            protocol = item.configType,
            server = item.server.orEmpty(),
            port = item.serverPort?.toIntOrNull() ?: 0,
            password = item.password,
            username = item.username,
            method = item.method,
            flow = item.flow,
            network = item.network?.ifBlank { "tcp" } ?: "tcp",
            headerType = item.headerType,
            host = item.host,
            path = item.path,
            grpcMode = item.mode,
            serviceName = item.serviceName,
            authority = item.authority,
            security = item.security,
            tlsEnabled = item.security.equals("tls", true) || item.security.equals("reality", true),
            sni = item.sni,
            insecure = item.insecure == true,
            alpn = item.alpn.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
            obfsType = (item.obfsPassword ?: mask.obfsPassword)?.takeIf { it.isNotBlank() }?.let { "salamander" },
            obfsPassword = item.obfsPassword ?: mask.obfsPassword,
            upMbps = parseMbps(item.bandwidthUp) ?: parseMbps(mask.brutalUp),
            downMbps = parseMbps(item.bandwidthDown) ?: parseMbps(mask.brutalDown),
            hopPorts = item.portHopping ?: mask.hopPorts,
            hopInterval = item.portHoppingInterval ?: mask.hopInterval,
            fingerprint = item.fingerPrint,
            pinnedCertificateSha256 = item.pinnedCA256,
            verifyPeerCertByName = item.verifyPeerCertByName,
            echConfigList = item.echConfigList.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
            realityPublicKey = item.publicKey,
            realityShortId = item.shortId,
            realitySpiderX = item.spiderX,
            realityMldsa65Verify = item.mldsa65Verify,
            browserDialerMode = item.browserDialerMode,
            wireGuardPrivateKey = item.secretKey,
            wireGuardPublicKey = item.publicKey,
            wireGuardPreSharedKey = item.preSharedKey,
            wireGuardLocalAddresses = item.localAddress.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
            wireGuardReserved = item.reserved.orEmpty().split(',').mapNotNull {
                it.trim().toIntOrNull()?.takeIf { value -> value in 0..255 }
            },
            wireGuardMtu = item.mtu,
            preferredCore = PreferredCore.parse(item.preferredCore),
            xrayFinalMask = item.finalMask,
            hopMode = mask.hopMode,
            hopRemoteIPs = mask.hopRemoteIPs,
            xrayCongestion = mask.congestion,
            )
        }

        private fun parseMbps(value: String?): Int? =
            value?.trim()?.removeSuffix("Mbps")?.removeSuffix("mbps")?.toDoubleOrNull()?.toInt()

        private data class FinalMaskFields(
            val brutalUp: String? = null,
            val brutalDown: String? = null,
            val hopPorts: String? = null,
            val hopInterval: String? = null,
            val obfsPassword: String? = null,
            val hopMode: String? = null,
            val hopRemoteIPs: List<String> = emptyList(),
            val congestion: String? = null,
        ) {
            companion object {
                fun parse(raw: String?): FinalMaskFields {
                    val root = JsonUtil.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject ?: return FinalMaskFields()
                    val quic = root.objectValue("quicParams")
                    val oldHop = quic?.objectValue("udpHop")
                    val udp = root.arrayValue("udp")
                    val hop = udp?.firstOrNull { it.isJsonObject && it.asJsonObject.stringValue("type")?.equals("udphop", true) == true }
                        ?.asJsonObject?.objectValue("settings")
                    val salamander = udp?.firstOrNull { it.isJsonObject && it.asJsonObject.stringValue("type")?.equals("salamander", true) == true }
                        ?.asJsonObject?.objectValue("settings")
                    return FinalMaskFields(
                        brutalUp = quic?.stringValue("brutalUp"),
                        brutalDown = quic?.stringValue("brutalDown"),
                        hopPorts = hop?.stringValue("remotePorts") ?: oldHop?.stringValue("ports"),
                        hopInterval = hop?.stringValue("interval") ?: oldHop?.stringValue("interval"),
                        obfsPassword = salamander?.stringValue("password"),
                        hopMode = hop?.stringValue("mode"),
                        hopRemoteIPs = hop?.arrayValue("remoteIPs")?.mapNotNull {
                            it.takeIf { value -> value.isJsonPrimitive }?.asString
                        }.orEmpty(),
                        congestion = quic?.stringValue("congestion"),
                    )
                }

                private fun JsonObject.objectValue(key: String): JsonObject? =
                    entrySet().firstOrNull { it.key.equals(key, true) }?.value?.takeIf { it.isJsonObject }?.asJsonObject

                private fun JsonObject.arrayValue(key: String) =
                    entrySet().firstOrNull { it.key.equals(key, true) }?.value?.takeIf { it.isJsonArray }?.asJsonArray

                private fun JsonObject.stringValue(key: String): String? =
                    entrySet().firstOrNull { it.key.equals(key, true) }?.value?.takeIf { it.isJsonPrimitive }?.asString
            }
        }
    }
}
