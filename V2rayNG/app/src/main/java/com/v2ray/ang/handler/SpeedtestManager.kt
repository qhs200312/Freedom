package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.AngApplication
import com.v2ray.ang.util.RuntimeDiagnostics
import com.v2ray.ang.dto.GeoLocation
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

object SpeedtestManager {

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        var socket: Socket? = null
        val start = System.currentTimeMillis()

        try {
            socket = Socket()
            socket.connect(InetSocketAddress(url, port), timeoutMs)

            return System.currentTimeMillis() - start
        } catch (e: UnknownHostException) {
            LogUtil.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "socketConnectTime IOException: ${e.message}")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        } finally {
            socket?.let { s ->
                try {
                    if (!s.isClosed) {
                        s.close()
                    }
                } catch (closeEx: IOException) {
                }
            }
        }
        return -1
    }

    fun getRemoteIPInfo(): String? {
        val location = getIPInfo(useProxy = true) ?: return null
        return "(${location.countryCode.ifBlank { "unknown" }}) ${location.ip.ifBlank { "unknown" }}"
    }

    fun getIPInfo(
        useProxy: Boolean,
        enrichLocation: Boolean = true,
        knownLocation: GeoLocation? = null,
    ): GeoLocation? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val socksPort = if (useProxy) SettingsManager.getSocksPort() else 0
        if (useProxy && socksPort == 0) return null
        fun fetch(endpoint: String, timeout: Int) = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = endpoint,
                timeout = timeout,
                callTimeout = timeout,
                socksPort = socksPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword,
            ),
            onFailure = { category ->
                val target = if (endpoint == url) "primary" else if (endpoint.startsWith("$url/")) "location" else "fallback"
                RuntimeDiagnostics.exitIp(AngApplication.application, "target=$target proxy=$useProxy failure=$category")
            },
        )

        var base = knownLocation
        // A known exit address is sufficient for enrichment; do not repeat all IP probes.
        if (base == null) {
            val result = ExitIpLookup.lookupDualFast(
                url = url,
                includeIpv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED),
            ) { endpoint -> fetch(endpoint, 2500) }
            if (result != null) {
                val detected = result.geoInfo?.let { toGeoLocation(it, result.ipv4, result.ipv6) }
                    ?: GeoLocation(
                        ip = result.ipv4.ifBlank { result.ipv6 },
                        ipv4 = result.ipv4,
                        ipv6 = result.ipv6,
                    )
                base = detected.copy(
                    ip = result.ipv4.ifBlank { result.ipv6.ifBlank { detected.ip } },
                    ipv4 = result.ipv4.ifBlank { detected.effectiveIpv4 },
                    ipv6 = result.ipv6.ifBlank { detected.effectiveIpv6 },
                )
            }
        }
        base ?: return null

        if (!enrichLocation || base.hasLocationDetails) return base
        val lookupIp = base.effectiveIpv4.ifBlank { base.effectiveIpv6 }
        val lookupUrl = ExitIpLookup.geoLookupUrl(url, lookupIp) ?: return base
        val info = fetch(lookupUrl, 6000)?.let { JsonUtil.fromJsonSafe(it, IPAPIInfo::class.java) }
            ?: return base
        if (ExitIpLookup.extractIp(info) != lookupIp) return base
        return toGeoLocation(info, base.effectiveIpv4, base.effectiveIpv6)
    }

    private fun extractIp(ipInfo: IPAPIInfo): String? = listOf(
        ipInfo.ip,
        ipInfo.clientIp,
        ipInfo.ip_addr,
        ipInfo.query,
    ).firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun toGeoLocation(ipInfo: IPAPIInfo, ipv4: String = "", ipv6: String = ""): GeoLocation {

        val ip = extractIp(ipInfo).orEmpty()

        val countryCode = listOf(
            ipInfo.country_code,
            ipInfo.countryCode,
            ipInfo.location?.country_code,
            ipInfo.country?.takeIf { it.trim().length == 2 }
        ).firstOrNull { !it.isNullOrBlank() }

        val regionCode = listOf(
            ipInfo.region_code,
            ipInfo.regionCode,
            ipInfo.region?.takeIf { it.trim().length <= 3 }
        ).firstOrNull { !it.isNullOrBlank() }

        val location = GeoLocation(
            ip = ipv4.ifBlank { ipv6.ifBlank { ip.orEmpty() } },
            ipv4 = ipv4.ifBlank { ip.orEmpty().takeIf(ExitIpLookup::isIpv4).orEmpty() },
            ipv6 = ipv6.ifBlank { ip.orEmpty().takeIf(ExitIpLookup::isIpv6).orEmpty() },
            countryCode = countryCode.orEmpty(),
            country = listOf(
                ipInfo.country_name,
                ipInfo.country?.takeUnless { it.trim().length == 2 }
            )
                .firstOrNull { !it.isNullOrBlank() }
                .orEmpty(),
            regionCode = regionCode.orEmpty(),
            region = listOf(ipInfo.region_name, ipInfo.regionName, ipInfo.region)
                .firstOrNull { !it.isNullOrBlank() }
                .orEmpty(),
            city = ipInfo.city.orEmpty(),
            latitude = ipInfo.latitude ?: ipInfo.lat,
            longitude = ipInfo.longitude ?: ipInfo.lon,
        )
        return ChineseGeoNameLocalizer.localize(location, SettingsManager.getLocale())
    }
}
