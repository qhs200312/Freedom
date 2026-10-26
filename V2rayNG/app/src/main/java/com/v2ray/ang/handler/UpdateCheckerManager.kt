package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val url = if (includePreRelease) {
            AppConfig.APP_API_URL
        } else {
            AppConfig.APP_API_URL.concatUrl("latest")
        }

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        val proxyAvailable = HttpUtil.isTcpPortOpen(AppConfig.LOOPBACK, httpPort)

        var response = if (proxyAvailable) {
            HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    requireHttps = true,
                    timeout = 5000,
                    httpPort = httpPort,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword
                )
            )
        } else {
            null
        }
        if (response.isNullOrEmpty()) {
            response = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    requireHttps = true,
                    timeout = 5000
                )
            )
                ?: throw IllegalStateException("Failed to get response")
        }

        val latestRelease = if (includePreRelease) {
            JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)
                ?.firstOrNull()
                ?: throw IllegalStateException("No pre-release found")
        } else {
            JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
        }
        if (latestRelease == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        val latestVersion = latestRelease.tagName.removePrefix("v")
        LogUtil.i(
            AppConfig.TAG,
            "Found new version: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        return@withContext if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            val downloadUrl = getDownloadUrl(latestRelease, Build.SUPPORTED_ABIS[0])
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = downloadUrl,
                downloadDigest = latestRelease.assets.firstOrNull { it.browserDownloadUrl == downloadUrl }?.digest,
                isPreRelease = latestRelease.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    private fun compareVersions(version1: String, version2: String): Int {
        fun parse(v: String): List<Int> = v.trim()
            .removePrefix("v")
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }

        val v1 = parse(version1)
        val v2 = parse(version2)

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = v1.getOrElse(i) { 0 }
            val num2 = v2.getOrElse(i) { 0 }
            if (num1 != num2) return num1.compareTo(num2)
        }
        return 0
    }

    private fun getDownloadUrl(release: GitHubRelease, abi: String): String {
        val fDroid = "fdroid"
        val isFdroid = BuildConfig.APPLICATION_ID.contains(fDroid, ignoreCase = true)
        val apks = release.assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) &&
                it.name.contains(fDroid, ignoreCase = true) == isFdroid
        }
        val abiPattern = Regex("(^|[-_.])(arm64-v8a|armeabi-v7a|x86_64|x86|universal)(?=[-_.]|$)", RegexOption.IGNORE_CASE)
        fun assetAbi(asset: GitHubRelease.Asset): String? = abiPattern.find(asset.name)?.groupValues?.get(2)
        val asset = apks.firstOrNull { assetAbi(it).equals(abi, ignoreCase = true) }
            ?: apks.firstOrNull { assetAbi(it).equals("universal", ignoreCase = true) }
            ?: throw IllegalStateException("No compatible APK found")
        return asset.browserDownloadUrl
    }
}
