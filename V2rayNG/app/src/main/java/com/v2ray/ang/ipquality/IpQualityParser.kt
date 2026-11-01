// Logic reference: FlClash (GPL-3.0), independently implemented from the supplied behavior specification.
package com.v2ray.ang.ipquality

import com.google.gson.JsonObject
import com.google.gson.JsonParser

object IpQualityParser {
    fun parse(source: IpQualitySource, ip: String, status: Int, body: String): IpQualityOutcome {
        if (status == 429) return IpQualityOutcome.Failure(IpQualityFailure.rateLimited)
        if (status != 200) return IpQualityOutcome.Failure(IpQualityFailure.failed)
        return try {
            val json = JsonParser.parseString(body)
            if (!json.isJsonObject) return IpQualityOutcome.Failure(IpQualityFailure.noType)
            parseObject(source, ip, json.asJsonObject)
        } catch (_: Exception) {
            IpQualityOutcome.Failure(IpQualityFailure.failed)
        }
    }

    private fun parseObject(source: IpQualitySource, ip: String, json: JsonObject): IpQualityOutcome {
        if (source == IpQualitySource.identMe &&
            IpQualityAddress.normalize(json.text("ip").orEmpty()) != IpQualityAddress.normalize(ip)) {
            return IpQualityOutcome.Failure(IpQualityFailure.ipMismatch)
        }
        if (source == IpQualitySource.ipApiCom && json.text("status") != "success") {
            return IpQualityOutcome.Failure(IpQualityFailure.failed)
        }
        if (json.flag("error") || json.text("status")?.lowercase() in setOf("error", "denied", "warning")) {
            return IpQualityOutcome.Failure(IpQualityFailure.failed)
        }
        var hosting = false
        var mobile = false
        var declared: String? = null
        var organization: String? = null
        var asn: String? = null
        var flags = json
        var proxyKey = "is_proxy"
        var vpnKey = "is_vpn"
        var torKey = "is_tor"
        var abuserKey = "is_abuser"
        val evidence: Boolean
        when (source) {
            IpQualitySource.identMe -> {
                declared = json.text("type")
                organization = json.text("aso")
                asn = json.text("asn")
                evidence = json.text("ip") != null
            }
            IpQualitySource.ipApiCom -> {
                hosting = json.flag("hosting")
                mobile = json.flag("mobile")
                organization = json.text("org") ?: json.text("isp")
                asn = json.text("as")
                proxyKey = "proxy"
                evidence = json.hasAny("hosting", "mobile", "proxy", "org", "isp", "as")
            }
            IpQualitySource.ipQuery -> {
                flags = json.obj("risk")
                val isp = json.obj("isp")
                hosting = flags.flag("is_datacenter")
                mobile = flags.flag("is_mobile")
                organization = isp.text("org") ?: isp.text("isp")
                asn = isp.text("asn")
                evidence = flags.hasAny("is_datacenter", "is_mobile", "is_proxy", "is_vpn", "is_tor") ||
                    organization != null || asn != null || json.text("ip") != null
            }
            IpQualitySource.ipLocate, IpQualitySource.ipApiIs -> {
                val company = json.obj("company")
                val network = json.obj("asn")
                flags = if (source == IpQualitySource.ipLocate) json.obj("privacy") else json
                hosting = flags.flag(if (source == IpQualitySource.ipLocate) "is_hosting" else "is_datacenter")
                mobile = source == IpQualitySource.ipApiIs && json.flag("is_mobile")
                declared = company.text("type")?.takeUnless { normalizeType(false, false, it).second }
                    ?: network.text("type")
                // Hosting declarations in either record dominate other declarations.
                if (network.text("type")?.lowercase() == "hosting") declared = "hosting"
                organization = company.text("name") ?: network.text("name") ?: network.text("org")
                asn = network.text("asn")
                evidence = declared != null || organization != null || asn != null || json.text("ip") != null ||
                    flags.hasAny("is_hosting", "is_datacenter", "is_mobile", "is_proxy", "is_vpn", "is_tor", "is_abuser")
            }
            IpQualitySource.proxyCheck -> {
                val record = json.get(ip)?.takeIf { it.isJsonObject }?.asJsonObject ?: json.entrySet().firstOrNull {
                    IpQualityAddress.normalize(it.key) == IpQualityAddress.normalize(ip) && it.value.isJsonObject
                }?.value?.asJsonObject ?: return IpQualityOutcome.Failure(IpQualityFailure.noType)
                val network = record.obj("network")
                flags = record.obj("detections")
                declared = network.text("type")
                hosting = flags.flag("hosting") || declared.equals("Hosting", true)
                mobile = declared.equals("Wireless", true)
                organization = network.text("organisation")
                asn = network.text("asn")
                proxyKey = "proxy"
                vpnKey = "vpn"
                torKey = "tor"
                abuserKey = "abuser"
                evidence = declared != null || organization != null || asn != null ||
                    flags.hasAny("hosting", "proxy", "vpn", "tor", "abuser")
            }
        }
        if (!evidence) return IpQualityOutcome.Failure(IpQualityFailure.noType)
        val (type, inferred) = normalizeType(hosting, mobile, declared)
        return IpQualityOutcome.Success(IpQuality(
            ip = ip, source = source, type = type, organization = organization,
            asn = asn?.let(::normalizeAsn), inferred = inferred,
            isProxy = flags.flag(proxyKey), isVpn = flags.flag(vpnKey),
            isTor = flags.flag(torKey), isAbuser = flags.flag(abuserKey),
        ))
    }

    fun normalizeType(hosting: Boolean, mobile: Boolean, declaration: String?): Pair<IpType, Boolean> {
        val declared = when (declaration?.trim()?.lowercase()) {
            "isp", "residential" -> IpType.residential
            "mobile", "wireless" -> IpType.mobile
            "business", "education", "government", "banking" -> IpType.business
            "hosting" -> IpType.hosting
            else -> null
        }
        return when {
            hosting || declared == IpType.hosting -> IpType.hosting to false
            mobile -> IpType.mobile to false
            declared != null -> declared to false
            else -> IpType.residential to true
        }
    }

    private fun normalizeAsn(value: String): String =
        Regex("^(?:AS)?(\\d+)(?:\\s.*)?$", RegexOption.IGNORE_CASE).matchEntire(value.trim())
            ?.groupValues?.get(1)?.let { "AS$it" } ?: value.trim()

    private fun JsonObject.obj(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf {
        it.isJsonPrimitive && (it.asJsonPrimitive.isString || it.asJsonPrimitive.isNumber)
    }?.asString?.trim()?.takeIf(String::isNotEmpty)
    private fun JsonObject.flag(key: String): Boolean =
        get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true
    private fun JsonObject.hasAny(vararg keys: String) = keys.any { key ->
        get(key)?.let { !it.isJsonNull && (it.isJsonPrimitive || it.isJsonObject && it.asJsonObject.size() > 0) } == true
    }
}
