package com.v2ray.ang.handler

import android.content.Context
import android.content.ContentValues
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

data class UpdateDownloadProgress(
    val result: CheckUpdateResult,
    val downloaded: Long = 0,
    val total: Long = -1,
    val verifying: Boolean = false,
) {
    val percent: Int? get() = if (total > 0) {
        (downloaded.toDouble() / total * 100).toInt().coerceIn(0, 100)
    } else null
}

object VerifiedUpdateManager {
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    const val DOWNLOAD_DIRECTORY = "Freedom/Updates"
    private val mutex = Mutex()
    private val _progress = MutableStateFlow<UpdateDownloadProgress?>(null)
    val progress = _progress.asStateFlow()
    private val _completed = MutableSharedFlow<CheckUpdateResult>(extraBufferCapacity = 1)
    val completed = _completed.asSharedFlow()

    private data class DownloadedUpdate(
        val result: CheckUpdateResult,
        val fileName: String,
        val digest: String,
        val versionCode: Long,
        val publicUri: String,
    )

    private data class Store(
        val ready: DownloadedUpdate? = null,
        val retired: List<DownloadedUpdate> = emptyList(),
    )

    internal fun sameDownload(first: CheckUpdateResult, second: CheckUpdateResult): Boolean =
        first.latestVersion == second.latestVersion && first.downloadUrl == second.downloadUrl &&
            first.downloadDigest.equals(second.downloadDigest, ignoreCase = true)

    internal fun fileName(result: CheckUpdateResult): String {
        val remoteName = runCatching { URI(result.downloadUrl.orEmpty()).path.substringAfterLast('/') }
            .getOrDefault("")
        val name = remoteName.substringBeforeLast('.', remoteName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').take(100).ifBlank { "Freedom_update" }
        val identity = "${result.latestVersion}\n${result.downloadUrl}\n${result.downloadDigest.orEmpty().lowercase()}"
        val suffix = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "${name}_$suffix.apk"
    }

    internal fun isInstalled(installedVersion: Long, downloadedVersion: Long): Boolean =
        downloadedVersion > 0 && installedVersion >= downloadedVersion

    fun reportProgress(progress: UpdateDownloadProgress?) {
        _progress.value = progress
    }

    fun reportCompleted(result: CheckUpdateResult) {
        _completed.tryEmit(result.copy(isDownloaded = true))
    }

    suspend fun findDownloadedUpdate(
        context: Context,
        requested: CheckUpdateResult? = null,
    ): CheckUpdateResult? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ready = verifiedReady(context) ?: return@withLock null
            if (requested != null && !sameDownload(ready.result, requested)) return@withLock null
            ready.result.copy(isDownloaded = true)
        }
    }

    suspend fun installationFile(context: Context, requested: CheckUpdateResult): File? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val ready = verifiedReady(context) ?: return@withLock null
                if (!sameDownload(ready.result, requested)) return@withLock null
                managedFile(context, ready.fileName)
            }
        }

    suspend fun download(
        context: Context,
        result: CheckUpdateResult,
        onProgress: (UpdateDownloadProgress) -> Unit,
    ): CheckUpdateResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            verifiedReady(context)?.takeIf { sameDownload(it.result, result) }?.let {
                return@withLock it.result.copy(isDownloaded = true)
            }
            require(result.hasUpdate && !result.latestVersion.isNullOrBlank())
            val directory = directory(context)
            val download = File(directory, "${UUID.randomUUID()}.part")
            try {
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
                val coroutineContext = currentCoroutineContext()
                check(HttpUtil.downloadToFile(request, download) { bytes, total ->
                    coroutineContext.ensureActive()
                    onProgress(UpdateDownloadProgress(result, bytes, total))
                }) { "Update download failed" }
                coroutineContext.ensureActive()
                onProgress(UpdateDownloadProgress(result, verifying = true))
                saveDownloadedLocked(context, result, download)
            } finally {
                download.delete()
            }
        }
    }

    internal suspend fun saveDownloadedPackage(context: Context, result: CheckUpdateResult, download: File) =
        withContext(Dispatchers.IO) {
            mutex.withLock { saveDownloadedLocked(context, result, download) }
        }

    private fun saveDownloadedLocked(context: Context, result: CheckUpdateResult, download: File): CheckUpdateResult {
        if (result.downloadDigest != null) {
            check(matchesDigest(download, result.downloadDigest)) { "Update checksum mismatch" }
        }
        check(verifyApk(context, download)) { "Update identity or signature mismatch" }
        val version = archiveVersion(context, download)
        val store = readStore(context)
        check(store.ready == null || version >= store.ready.versionCode) { "A newer update is already downloaded" }
        val name = fileName(result)
        val apk = managedFile(context, name)
        check(store.ready?.fileName != name) { "This update is already saved" }
        check(download.renameTo(apk)) { "Cannot save update" }
        var publicUri: Uri? = null
        var committed = false
        try {
            val digest = sha256(apk)
            publicUri = publishDownload(context, apk)
            val ready = DownloadedUpdate(result.copy(isDownloaded = true), name, digest, version, publicUri.toString())
            // Commit the new package before removing the old one, so interruption keeps a usable update.
            val promoted = Store(ready, store.retired + listOfNotNull(store.ready))
            writeStore(context, promoted)
            committed = true
            runCatching { cleanupRetired(context, promoted) }.onFailure {
                LogUtil.w(AppConfig.TAG, "Old update cleanup will be retried: ${it.javaClass.simpleName}")
            }
            return ready.result
        } finally {
            if (!committed) {
                publicUri?.let { runCatching { deletePublicDownload(context, it.toString()) } }
                apk.delete()
            }
        }
    }

    suspend fun cleanupInstalled(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val store = readStore(context)
            val ready = store.ready
            val updated = if (ready != null && isInstalled(installedVersion(context), ready.versionCode)) {
                Store(retired = store.retired + ready).also { writeStore(context, it) }
            } else store
            cleanupRetired(context, updated)
            val referenced = (updated.retired + listOfNotNull(updated.ready)).map { it.fileName }.toSet()
            directory(context).listFiles().orEmpty().filter {
                it.isFile && (it.extension == "part" || it.extension == "apk" && it.name !in referenced)
            }.forEach { it.delete() }
            if (updated.ready == null) UpdateNotifications(context).cancel()
        }
    }

    private fun verifiedReady(context: Context): DownloadedUpdate? {
        val store = readStore(context)
        val ready = store.ready ?: return null
        val file = managedFile(context, ready.fileName)
        if (isInstalled(installedVersion(context), ready.versionCode) ||
            !file.isFile || !matchesDigest(file, ready.digest) || !verifyApk(context, file)) {
            val retired = Store(retired = store.retired + ready)
            writeStore(context, retired)
            cleanupRetired(context, retired)
            return null
        }
        return ready
    }

    private fun directory(context: Context): File =
        File(context.filesDir, "app-updates").apply { check(isDirectory || mkdirs()) }

    private fun managedFile(context: Context, name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+\\.apk")) && !name.startsWith("."))
        return File(directory(context), name)
    }

    private fun readStore(context: Context): Store {
        val file = AtomicFile(File(directory(context), "downloaded.json"))
        return if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) Store() else
            JsonUtil.fromJson(String(file.readFully(), Charsets.UTF_8), Store::class.java) ?: Store()
    }

    private fun writeStore(context: Context, store: Store) {
        val file = AtomicFile(File(directory(context), "downloaded.json"))
        val output = file.startWrite()
        try {
            output.write(JsonUtil.toJson(store).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun cleanupRetired(context: Context, store: Store) {
        val remaining = store.retired.filter { retired ->
            val publicDeleted = runCatching { deletePublicDownload(context, retired.publicUri) }.getOrElse {
                LogUtil.w(AppConfig.TAG, "Cannot remove old public update: ${it.javaClass.simpleName}")
                false
            }
            val file = managedFile(context, retired.fileName)
            val privateDeleted = store.ready?.fileName == retired.fileName || !file.exists() || file.delete()
            !publicDeleted || !privateDeleted
        }
        if (remaining != store.retired) writeStore(context, store.copy(retired = remaining))
    }

    private fun publishDownload(context: Context, apk: File): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, apk.name)
                put(MediaStore.Downloads.MIME_TYPE, APK_MIME_TYPE)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_DIRECTORY/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            try {
                checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                }
                check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null) == 1)
                return uri
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_DIRECTORY)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create update download directory" }
        val target = File(directory, apk.name)
        val temporary = File(directory, "${apk.name}.part")
        try {
            apk.copyTo(temporary, overwrite = true)
            check(temporary.renameTo(target)) { "Cannot save public update" }
        } finally {
            temporary.delete()
        }
        return Uri.fromFile(target)
    }

    private fun deletePublicDownload(context: Context, value: String): Boolean {
        val uri = Uri.parse(value)
        if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
            context.contentResolver.delete(uri, null, null)
            return true
        }
        if (uri.scheme == "file") {
            @Suppress("DEPRECATION")
            val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_DIRECTORY).canonicalFile
            val file = File(requireNotNull(uri.path)).canonicalFile
            require(file.parentFile == root && file.extension == "apk")
            return !file.exists() || file.delete()
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(context: Context): Long =
        version(context.packageManager.getPackageInfo(context.packageName, 0))

    @Suppress("DEPRECATION")
    private fun archiveVersion(context: Context, file: File): Long =
        version(checkNotNull(context.packageManager.getPackageArchiveInfo(file.path, 0)))

    @Suppress("DEPRECATION")
    private fun version(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
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
