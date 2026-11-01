package com.v2ray.ang.ui.checkupdate

import android.Manifest
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.VerifiedUpdateManager
import com.v2ray.ang.service.UpdateDownloadService
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A real foreground activity handles permission returns and notification taps on every Android version. */
class UpdateInstallActivity : BaseComponentActivity() {
    private var stage = ""
    private var result: CheckUpdateResult? = null

    private val notificationsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) toast(R.string.update_notifications_disabled)
        requestStorageOrDownload()
    }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDownload() else {
            toastError(R.string.update_storage_permission_required)
            finish()
        }
    }
    private val unknownSources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (canInstall()) openInstaller() else {
            toastError(R.string.update_install_permission_required)
            finish()
        }
    }
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Cancellation must leave both the package and its install notification available.
        lifecycleScope.launch {
            VerifiedUpdateManager.cleanupInstalled(applicationContext)
            finish()
        }
    }

    override fun onStorageReady(savedInstanceState: Bundle?) {
        super.onStorageReady(savedInstanceState)
        result = intent.getStringExtra(UpdateDownloadService.EXTRA_RESULT)?.let {
            JsonUtil.fromJsonSafe(it, CheckUpdateResult::class.java)
        }
        if (result == null) {
            finish()
            return
        }
        stage = savedInstanceState?.getString("stage").orEmpty()
        if (stage in setOf("notifications", "storage", "unknown_sources", "installer")) return
        lifecycleScope.launch {
            try {
                val ready = VerifiedUpdateManager.findDownloadedUpdate(applicationContext, result!!)
                if (ready != null) {
                    result = ready
                    requestInstallPermission()
                } else if (result!!.isDownloaded) {
                    toastError(R.string.update_download_missing)
                    finish()
                } else {
                    requestNotificationPermission()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error, R.string.update_verification_failed)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("stage", stage)
        super.onSaveInstanceState(outState)
    }

    @Composable
    override fun ScreenContent() {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.update_preparing))
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            stage = "notifications"
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else requestStorageOrDownload()
    }

    private fun requestStorageOrDownload() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            stage = "storage"
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else startDownload()
    }

    private fun startDownload() {
        try {
            val selected = checkNotNull(result)
            if (VerifiedUpdateManager.progress.value == null) {
                ContextCompat.startForegroundService(this, Intent(this, UpdateDownloadService::class.java).apply {
                    putExtra(UpdateDownloadService.EXTRA_RESULT, JsonUtil.toJson(selected))
                })
            }
            toast(R.string.update_download_started)
            finish()
        } catch (error: Exception) {
            fail(error, R.string.update_download_failed)
        }
    }

    private fun canInstall() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        packageManager.canRequestPackageInstalls()

    private fun requestInstallPermission() {
        if (canInstall()) {
            openInstaller()
            return
        }
        toast(R.string.update_install_permission_required)
        stage = "unknown_sources"
        try {
            unknownSources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                unknownSources.launch(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (error: Exception) {
                fail(error, R.string.update_install_permission_required)
            }
        }
    }

    private fun openInstaller() {
        stage = "preparing_installer"
        lifecycleScope.launch {
            try {
                val apk = VerifiedUpdateManager.installationFile(applicationContext, checkNotNull(result))
                if (apk == null) {
                    toastError(R.string.update_download_missing)
                    finish()
                    return@launch
                }
                val uri = FileProvider.getUriForFile(this@UpdateInstallActivity, "$packageName.updates", apk)
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, VerifiedUpdateManager.APK_MIME_TYPE)
                    clipData = ClipData.newRawUri("Update APK", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                }
                stage = "installer"
                try {
                    installer.launch(install)
                } catch (_: ActivityNotFoundException) {
                    @Suppress("DEPRECATION")
                    val fallback = Intent(install).setAction(Intent.ACTION_INSTALL_PACKAGE)
                    installer.launch(fallback)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error, R.string.update_installer_unavailable)
            }
        }
    }

    private fun fail(error: Exception, message: Int) {
        LogUtil.e(AppConfig.TAG, "Cannot prepare update installation", error)
        toastError(message)
        finish()
    }

    companion object {
        fun intent(context: Context, result: CheckUpdateResult) =
            Intent(context, UpdateInstallActivity::class.java).putExtra(
                UpdateDownloadService.EXTRA_RESULT, JsonUtil.toJson(result),
            )

        fun start(context: Context, result: CheckUpdateResult) {
            context.startActivity(intent(context, result))
        }
    }
}
