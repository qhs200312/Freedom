package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
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

    fun getIPInfo(useProxy: Boolean): GeoLocation? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = if (useProxy) SettingsManager.getHttpPort() else 0
        if (useProxy && httpPort == 0) return null
        val content = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000,
                httpPort = httpPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword
            )
        ) ?: return null
        val ipInfo = JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java) ?: return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

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
            ip = ip.orEmpty(),
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
            .takeIf { it.ip.isNotBlank() || it.hasCoordinates }
    }
}
