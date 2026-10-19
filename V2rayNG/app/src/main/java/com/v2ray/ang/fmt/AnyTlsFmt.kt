package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.util.Utils
import java.net.URI

object AnyTlsFmt : FmtBase() {
    fun parse(value: String): ProfileItem? = runCatching {
        val uri = URI(Utils.fixIllegalUrl(value))
        require(uri.scheme.equals("anytls", true))
        require(uri.rawPath.orEmpty() in setOf("", "/"))
        val query = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEach { part ->
            val key = Utils.decodeURIComponent(part.substringBefore('='))
            query.putIfAbsent(key, Utils.decodeURIComponent(part.substringAfter('=', "")))
        }
        fun flag(vararg keys: String): Boolean {
            val flag = keys.firstNotNullOfOrNull { query[it] } ?: return false
            return when (flag.lowercase()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> error("Invalid AnyTLS TLS verification flag")
            }
        }
        ProfileItem(
            configType = EConfigType.ANYTLS,
            remarks = Utils.decodeURIComponent(uri.rawFragment.orEmpty()).ifBlank { "AnyTLS" },
            server = uri.idnHost,
            serverPort = (if (uri.port == -1) 443 else uri.port).toString(),
            password = Utils.decodeURIComponent(uri.rawUserInfo.orEmpty()),
            network = "tcp",
            security = AppConfig.TLS,
            sni = query["sni"],
            insecure = flag("insecure", "allowInsecure", "allow_insecure"),
            alpn = query["alpn"],
            fingerPrint = query["fp"],
            anytlsIdleSessionCheckInterval = query["idle_session_check_interval"],
            anytlsIdleSessionTimeout = query["idle_session_timeout"],
            anytlsMinIdleSession = query["min_idle_session"]?.let {
                requireNotNull(it.toIntOrNull()) { "Invalid AnyTLS minimum idle sessions" }
            },
            anytlsClientMetadata = query["client_metadata"],
        ).also(::validate)
    }.getOrNull()

    fun validate(profile: ProfileItem) {
        require(!profile.server.isNullOrBlank()) { "AnyTLS server is required" }
        require(profile.serverPort?.toIntOrNull() in 1..65535) { "Invalid AnyTLS port" }
        require(!profile.password.isNullOrBlank()) { "AnyTLS password is required" }
        require(profile.security.isNullOrBlank() || profile.security.equals(AppConfig.TLS, true)) {
            "AnyTLS requires TLS"
        }
        require(validDuration(profile.anytlsIdleSessionCheckInterval)) { "Invalid AnyTLS idle check interval" }
        require(validDuration(profile.anytlsIdleSessionTimeout)) { "Invalid AnyTLS idle timeout" }
        require(profile.anytlsMinIdleSession == null || profile.anytlsMinIdleSession!! >= 0) {
            "AnyTLS minimum idle sessions cannot be negative"
        }
    }

    internal fun validDuration(value: String?): Boolean = value.isNullOrBlank() ||
        (Regex("(?:\\d+(?:\\.\\d+)?(?:ms|s|m|h))+").matches(value) &&
            Regex("\\d+(?:\\.\\d+)?").findAll(value).any { it.value.toDouble() > 0 })

    fun toUri(profile: ProfileItem): String {
        validate(profile)
        val query = hashMapOf("insecure" to if (profile.insecure == true) "1" else "0")
        mapOf(
            "sni" to profile.sni,
            "alpn" to profile.alpn,
            "fp" to profile.fingerPrint,
            "idle_session_check_interval" to profile.anytlsIdleSessionCheckInterval,
            "idle_session_timeout" to profile.anytlsIdleSessionTimeout,
            "min_idle_session" to profile.anytlsMinIdleSession?.toString(),
            "client_metadata" to profile.anytlsClientMetadata,
        ).forEach { (key, value) -> value?.takeIf(String::isNotBlank)?.let { query[key] = it } }
        return toUri(profile, profile.password, query)
    }
}
