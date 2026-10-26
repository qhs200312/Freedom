package com.v2ray.ang.ui.backup

import android.app.Application
import android.net.Uri
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.WebDavConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SecureStorage
import com.v2ray.ang.handler.SensitiveCache
import com.v2ray.ang.handler.WebDavManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.ui.base.ViewModelEvent
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.ZipUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

class BackupViewModel(application: Application) : BaseViewModel(application) {

    private val _webDavConfig = MutableStateFlow(MmkvManager.decodeWebDavConfig())
    val webDavConfig: StateFlow<WebDavConfig?> = _webDavConfig.asStateFlow()

    sealed interface BackupViewModelEvent : ViewModelEvent {
        data class ShareFile(val filePath: String) : BackupViewModelEvent
        data class ExportLocal(val cachePath: String, val targetUri: Uri) : BackupViewModelEvent
        object RestoreSuccess : BackupViewModelEvent
    }

    fun saveWebDavConfig(config: WebDavConfig) {
        MmkvManager.encodeWebDavConfig(config)
        _webDavConfig.value = config
        toastSuccess(R.string.toast_success)
    }

    fun shareBackup(cacheDir: File, appName: String) {
        launchLoading {
            val ret = backupConfigurationToCache(cacheDir, appName)
            if (ret.first) {
                val directory = File(cacheDir, "shared-backups").apply { mkdirs() }
                val shared = File(directory, "${UUID.randomUUID()}.zip")
                val original = File(ret.second)
                try {
                    check(original.renameTo(shared))
                    SensitiveCache.scheduleCleanup(app)
                    _viewModelEvent.send(BackupViewModelEvent.ShareFile(shared.path))
                } catch (e: Exception) {
                    shared.delete()
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    LogUtil.e(AppConfig.TAG, "Cannot share backup", e)
                    toastError(R.string.toast_failure)
                } finally {
                    original.delete()
                }
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    fun prepareBackupForUri(cacheDir: File, appName: String, targetUri: Uri) {
        launchLoading {
            val ret = backupConfigurationToCache(cacheDir, appName)
            if (ret.first) {
                _viewModelEvent.send(BackupViewModelEvent.ExportLocal(ret.second, targetUri))
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    fun restoreConfiguration(cacheDir: File, zipFile: File) {
        launchLoading {
            SensitiveCache.scheduleCleanup(app)
            val success = try {
                withContext(Dispatchers.IO) { performRestore(cacheDir, zipFile) }
            } finally {
                zipFile.delete()
            }
            if (success) {
                toastSuccess(R.string.toast_success)
                _viewModelEvent.send(BackupViewModelEvent.RestoreSuccess)
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    fun backupViaWebDav(cacheDir: File, appName: String) {
        val config = _webDavConfig.value
        if (config == null || (config.baseUrl.isEmpty())) {
            toastError(R.string.title_webdav_config_setting_unknown)
            return
        }

        launchLoading {
            var tempFile: File? = null
            try {
                val ret = backupConfigurationToCache(cacheDir, appName)
                if (!ret.first) {
                    toastError(R.string.toast_failure)
                    return@launchLoading
                }

                tempFile = File(ret.second)
                WebDavManager.init(config)

                val ok = try {
                    WebDavManager.uploadFile(tempFile, AppConfig.WEBDAV_BACKUP_FILE_NAME)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "WebDAV upload error", e)
                    false
                }

                if (ok) {
                    toastSuccess(R.string.toast_success)
                } else {
                    toastError(R.string.toast_failure)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "WebDAV backup error", e)
                toastError(R.string.toast_failure)
            } finally {
                tempFile?.delete()
            }
        }
    }

    fun restoreViaWebDav(cacheDir: File) {
        val config = _webDavConfig.value
        if (config == null || (config.baseUrl.isEmpty())) {
            toastError(R.string.title_webdav_config_setting_unknown)
            return
        }

        launchLoading {
            var target: File? = null
            try {
                SensitiveCache.scheduleCleanup(app)
                val work = File(cacheDir, "backup-work").apply { mkdirs() }
                target = File(work, "download_${UUID.randomUUID()}.zip")
                WebDavManager.init(config)
                val ok = WebDavManager.downloadFile(AppConfig.WEBDAV_BACKUP_FILE_NAME, target)
                if (!ok) {
                    toastError(R.string.toast_failure)
                    return@launchLoading
                }

                if (performRestore(cacheDir, target)) {
                    toastSuccess(R.string.toast_success)
                    _viewModelEvent.send(BackupViewModelEvent.RestoreSuccess)
                } else {
                    toastError(R.string.toast_failure)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "WebDAV download error", e)
                toastError(R.string.toast_failure)
            } finally {
                target?.delete()
            }
        }
    }

    private suspend fun backupConfigurationToCache(cacheDir: File, appName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val dateFormatted = SimpleDateFormat(
            "yyyy-MM-dd-HH-mm-ss",
            Locale.getDefault()
        ).format(System.currentTimeMillis())
        val folderName = "${appName}_${dateFormatted}_${UUID.randomUUID()}"
        val work = File(cacheDir, "backup-work").apply { mkdirs() }
        val backupDir = File(work, folderName).path
        val outputZipFilePath = File(work, "$folderName.zip").path

        try {
            SensitiveCache.scheduleCleanup(app)
            if (!SecureStorage.backupToDirectory(File(backupDir))) {
                return@withContext Pair(false, "")
            }

            if (ZipUtil.zipFromFolder(backupDir, outputZipFilePath)) {
                Pair(true, outputZipFilePath)
            } else {
                File(outputZipFilePath).delete()
                Pair(false, "")
            }
        } catch (e: Exception) {
            File(outputZipFilePath).delete()
            if (e is kotlinx.coroutines.CancellationException) throw e
            LogUtil.e(AppConfig.TAG, "Cannot create backup", e)
            Pair(false, "")
        } finally {
            File(backupDir).deleteRecursively()
        }
    }

    private fun performRestore(cacheDir: File, zipFile: File): Boolean {
        val work = File(cacheDir, "backup-work").apply { mkdirs() }
        val backupDir = File(work, UUID.randomUUID().toString()).path

        try {
            if (!ZipUtil.unzipToFolder(zipFile, backupDir)) {
                return false
            }

            if (!SecureStorage.restoreFromDirectory(File(backupDir))) return false
            SettingsChangeManager.makeSetupGroupTab()
            SettingsChangeManager.makeRestartService()

            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Cannot restore backup", e)
            return false
        } finally {
            File(backupDir).deleteRecursively()
        }
    }
}
