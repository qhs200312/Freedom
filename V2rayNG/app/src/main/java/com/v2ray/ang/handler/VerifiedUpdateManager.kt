package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object VerifiedUpdateManager {
    private val mutex = Mutex()

    suspend fun downloadAndInstall(context: Context, result: CheckUpdateResult) {
        if (!mutex.tryLock()) return
        val directory = File(context.cacheDir, "verified-updates").apply { mkdirs() }
        val download = File(directory, "${UUID.randomUUID()}.part")
        var ready: File? = null
        try {
            context.toast(R.string.update_verifying_download)
            withContext(Dispatchers.IO) {
                val url = requireNotNull(result.downloadUrl)
                val httpPort = SettingsManager.getHttpPort()
                val useProxy = HttpUtil.isTcpPortOpen(AppConfig.LOOPBACK, httpPort)
                val request = UrlContentRequest(
                    url = url,
                    timeout = 30000,
                    httpPort = if (useProxy) httpPort else 0,
                    proxyUsername = SettingsManager.getSocksUsername(),
                    proxyPassword = SettingsManager.getSocksPassword(),
                    requireHttps = true,
                )
                check(HttpUtil.downloadToFile(request, download)) { "Update download failed" }
                if (result.downloadDigest != null) {
                    check(matchesDigest(download, result.downloadDigest)) { "Update checksum mismatch" }
                }
                check(verifyApk(context, download)) { "Update identity or signature mismatch" }
                val apk = File(directory, "${UUID.randomUUID()}.apk")
                check(download.renameTo(apk))
                ready = apk
                SensitiveCache.scheduleCleanup(context)
            }
            val apk = checkNotNull(ready)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.cache", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            ready = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Verified update failed", e)
            context.toastError(R.string.update_verification_failed)
        } finally {
            download.delete()
            ready?.delete()
            mutex.unlock()
        }
    }

    @Suppress("DEPRECATION")
    internal fun verifyApk(context: Context, apk: File): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archive = manager.getPackageArchiveInfo(apk.path, flags) ?: return false
        fun version(info: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        fun signers(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else info.signatures
            return signatures.orEmpty().map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }.toSet()
        }
        return acceptsIdentity(
            context.packageName, archive.packageName, version(installed), version(archive),
            signers(installed), signers(archive),
        )
    }

    internal fun acceptsIdentity(
        installedPackage: String,
        archivePackage: String?,
        installedVersion: Long,
        archiveVersion: Long,
        installedSigners: Set<String>,
        archiveSigners: Set<String>,
    ): Boolean = installedPackage == archivePackage && archiveVersion > installedVersion &&
        installedSigners.isNotEmpty() && installedSigners == archiveSigners

    internal fun matchesDigest(file: File, digest: String): Boolean {
        if (!digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) return false
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                hash.update(buffer, 0, read)
            }
        }
        val expected = digest.substringAfter(':').lowercase().toByteArray(Charsets.US_ASCII)
        val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(expected, actual)
    }
}
