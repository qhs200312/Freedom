package com.v2ray.ang.core.nowhere

import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Nowhere v3 share-link fields; service-only options never control the local listener. */
data class NowhereEndpoint(
    val host: String,
    val port: Int,
    val key: String,
    val carrier: String = "tcp",
    val morph: Boolean = true,
    val sni: String? = null,
    val pin: String? = null,
    val name: String = "Nowhere",
) {
    init {
        require(host.isNotBlank() && host != "*" && !host.contains('%')) { "Invalid Nowhere host" }
        require(port in 1..65535) { "Invalid Nowhere port" }
        require(key.matches(Regex("[0-9a-f]{32}"))) { "Nowhere key must be 32 lowercase hexadecimal characters" }
        require(carrier == "tcp" || carrier == "udp") { "Nowhere transport must be tcp or udp" }
        require(pin == null || pin.matches(Regex("[0-9a-f]{64}"))) { "Invalid Nowhere certificate fingerprint" }
        require(sni == null || (sni.length <= 253 && sni.matches(Regex("[A-Za-z0-9.-]+")) &&
            !sni.contains(':'))) { "Invalid Nowhere server name" }
    }

    private val address: String get() = if (host.contains(':')) "[$host]" else IDN.toASCII(host)

    fun shareLink(): String = buildString {
        append("nowhere://$key@$address:$port?net=$carrier&morph=${if (morph) 1 else 0}")
        sni?.let { append("&sni=${encode(it)}") }
        pin?.let { append("&pin=$it") }
        append("#${encode(name)}")
    }

    fun vectorUrl(localPort: Int, username: String, password: String): String {
        require(localPort in 1..65535)
        require(username.isNotEmpty() && password.isNotEmpty())
        return buildString {
            append("vector://$key@$address/$carrier:$port")
            append("?in=${encode(username)}:${encode(password)}@127.0.0.1:$localPort")
            append("&morph=${if (morph) 1 else 0}")
            sni?.let { append("&sni=${encode(it)}") }
            pin?.let { append("&pin=$it") }
        }
    }

    override fun toString(): String = "NowhereEndpoint(host=$host, port=$port, carrier=$carrier)"

    companion object {
        fun parse(value: String): NowhereEndpoint {
            val uri = URI(value.trim())
            require(uri.scheme == "nowhere" && uri.rawPath.isNullOrEmpty()) { "Expected a nowhere:// share link" }
            val user = uri.rawUserInfo ?: error("Nowhere key is missing")
            require(!user.contains(':')) { "Nowhere links do not accept a password component" }
            val options = linkedMapOf<String, String>()
            uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEach {
                val parts = it.split('=', limit = 2)
                options.putIfAbsent(decode(parts[0]), decode(parts.getOrElse(1) { "" }))
            }
            val morph = when (options["morph"]) {
                null, "1" -> true
                "0" -> false
                else -> error("Nowhere morph must be 0 or 1")
            }
            return NowhereEndpoint(
                host = uri.host?.removeSurrounding("[", "]") ?: error("Invalid Nowhere host"),
                port = uri.port,
                key = decode(user),
                carrier = options["net"] ?: "tcp",
                morph = morph,
                sni = options["sni"]?.takeUnless { it.isEmpty() || it == "none" },
                pin = options["pin"]?.takeUnless { it.isEmpty() || it == "none" },
                name = uri.rawFragment?.let(::decode)?.ifBlank { "Nowhere" } ?: "Nowhere",
            )
        }

        private fun decode(value: String): String =
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}
