package com.v2ray.ang.handler

import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.util.JsonUtil
import java.net.URI
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object ExitIpLookup {
    private val ipv4Endpoints = listOf("https://ipv4.icanhazip.com", "https://api4.ipify.org")
    private val ipv6Endpoints = listOf("https://ipv6.icanhazip.com", "https://api6.ipify.org")

    data class Addresses(
        val ipv4: String = "",
        val ipv6: String = "",
        val geoInfo: IPAPIInfo? = null,
    )

    private data class Candidate(
        val ipv4: String = "",
        val ipv6: String = "",
        val geoInfo: IPAPIInfo? = null,
    )

    fun lookupDualFast(
        url: String,
        timeoutMs: Long = 3_000L,
        includeIpv6: Boolean = true,
        fetch: (String) -> String?,
    ): Addresses? {
        val tasks = buildList {
            add(url to 0)
            ipv4Endpoints.forEach { add(it to 4) }
            if (includeIpv6) ipv6Endpoints.forEach { add(it to 6) }
        }
        val executor = Executors.newFixedThreadPool(tasks.size)
        val completion = ExecutorCompletionService<Candidate?>(executor)
        tasks.forEach { (endpoint, family) ->
            completion.submit {
                val body = fetch(endpoint) ?: return@submit null
                when (family) {
                    4 -> body.trim().takeIf(::isIpv4)?.let { Candidate(ipv4 = it) }
                    6 -> body.trim().takeIf(::isIpv6)?.let { Candidate(ipv6 = normalizeIpv6(it)) }
                    else -> JsonUtil.fromJsonSafe(body, IPAPIInfo::class.java)?.let { info ->
                        extractIp(info)?.let { ip ->
                            when {
                                isIpv4(ip) -> Candidate(ipv4 = ip, geoInfo = info.apply { this.ip = ip })
                                isIpv6(ip) -> Candidate(ipv6 = normalizeIpv6(ip), geoInfo = info.apply { this.ip = normalizeIpv6(ip) })
                                else -> null
                            }
                        }
                    }
                }
            }
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var ipv4 = ""
        var ipv6 = ""
        var ipv4Geo: IPAPIInfo? = null
        var ipv6Geo: IPAPIInfo? = null
        var remainingTasks = tasks.size
        var ipv6GraceDeadline = Long.MAX_VALUE
        try {
            while (remainingTasks > 0) {
                val remaining = minOf(deadline, ipv6GraceDeadline) - System.nanoTime()
                if (remaining <= 0) break
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                remainingTasks--
                val candidate = runCatching { future.get() }.getOrNull() ?: continue
                if (ipv4.isBlank() && candidate.ipv4.isNotBlank()) {
                    ipv4 = candidate.ipv4
                    if (!includeIpv6) break
                    ipv6GraceDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_000L)
                }
                if (ipv6.isBlank() && candidate.ipv6.isNotBlank()) ipv6 = candidate.ipv6
                if (candidate.ipv4.isNotBlank() && candidate.geoInfo != null) ipv4Geo = candidate.geoInfo
                if (candidate.ipv6.isNotBlank() && candidate.geoInfo != null) ipv6Geo = candidate.geoInfo
                if (ipv4.isNotBlank() && ipv6.isNotBlank()) break
            }
            if (ipv4.isBlank() && ipv6.isBlank()) return null
            return Addresses(ipv4, ipv6, ipv4Geo ?: ipv6Geo.takeIf { ipv4.isBlank() })
        } finally {
            executor.shutdownNow()
        }
    }

    fun lookupFast(
        url: String,
        timeoutMs: Long = 3_000L,
        fetch: (String) -> String?,
    ): IPAPIInfo? {
        val executor = Executors.newFixedThreadPool(1 + ipv4Endpoints.size)
        val completion = ExecutorCompletionService<IPAPIInfo?>(executor)
        val tasks = buildList {
            add(url to true)
            ipv4Endpoints.forEach { add(it to false) }
        }
        tasks.forEach { (endpoint, json) ->
            completion.submit {
                val body = fetch(endpoint) ?: return@submit null
                if (json) {
                    JsonUtil.fromJsonSafe(body, IPAPIInfo::class.java)?.let { info ->
                        extractIp(info)?.takeIf(::isIpv4)?.let { info.apply { ip = it } }
                    }
                } else {
                    body.trim().takeIf(::isIpv4)?.let { IPAPIInfo(ip = it) }
                }
            }
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var fallback: IPAPIInfo? = null
        var remainingTasks = tasks.size
        var fallbackDeadline = Long.MAX_VALUE
        try {
            while (remainingTasks > 0) {
                val now = System.nanoTime()
                val remaining = minOf(deadline, fallbackDeadline) - now
                if (remaining <= 0) break
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                remainingTasks--
                val info = runCatching { future.get() }.getOrNull() ?: continue
                if (hasLocation(info)) return info
                if (fallback == null) {
                    fallback = info
                    fallbackDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(350L)
                }
            }
            return fallback
        } finally {
            executor.shutdownNow()
        }
    }

    fun lookup(url: String, fetch: (String) -> String?): IPAPIInfo? {
        val primary = fetch(url)?.let { JsonUtil.fromJsonSafe(it, IPAPIInfo::class.java) }
        primary?.let { info ->
            val ip = extractIp(info)
            if (ip != null && isIpv4(ip)) return info.apply { this.ip = ip }
        }
        val ipv4 = ipv4Endpoints.asSequence().mapNotNull { fetch(it)?.trim()?.takeIf(::isIpv4) }
            .firstOrNull() ?: return null
        val lookupUrl = geoLookupUrl(url, ipv4)
        val info = lookupUrl?.let(fetch)?.let { JsonUtil.fromJsonSafe(it, IPAPIInfo::class.java) }
        return if (info != null && extractIp(info) == ipv4) info.apply { ip = ipv4 }
        else IPAPIInfo(ip = ipv4)
    }

    fun extractIp(info: IPAPIInfo): String? = listOf(info.ip, info.clientIp, info.ip_addr, info.query)
        .firstOrNull { !it.isNullOrBlank() }?.trim()

    fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
                (part.length == 1 || part[0] != '0') && part.toInt() in 0..255
        }
    }

    fun isIpv6(value: String): Boolean {
        val normalized = normalizeIpv6(value)
        return normalized.contains(':') && runCatching {
            InetAddress.getByName(normalized) is Inet6Address
        }.getOrDefault(false)
    }

    private fun normalizeIpv6(value: String): String = value.trim().substringBefore('%')

    private fun hasLocation(info: IPAPIInfo): Boolean =
        !info.country.isNullOrBlank() || !info.country_name.isNullOrBlank() ||
            !info.country_code.isNullOrBlank() || !info.countryCode.isNullOrBlank() ||
            info.latitude != null || info.lat != null

    fun geoLookupUrl(url: String, ip: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.host.equals("api.ip.sb", true) || uri.path.trimEnd('/') != "/geoip" ||
            uri.rawQuery != null || uri.userInfo != null
        ) return null
        return URI(uri.scheme, null, uri.host, uri.port, "/geoip/$ip", null, null).toASCIIString()
    }
}
