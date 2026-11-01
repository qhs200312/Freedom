package com.v2ray.ang.ui.checkupdate

import android.app.Application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.handler.VerifiedUpdateManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException

class CheckUpdateViewModel(application: Application) : BaseViewModel(application) {

    private val _checkPreRelease = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)
    )
    val checkPreRelease: StateFlow<Boolean> = _checkPreRelease.asStateFlow()

    private val _updateResult = MutableStateFlow<CheckUpdateResult?>(null)
    val updateResult: StateFlow<CheckUpdateResult?> = _updateResult.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    fun toggleCheckPreRelease(enabled: Boolean) {
        _checkPreRelease.value = enabled
        MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, enabled)
    }

    fun checkForUpdates() {
        launchLoading {
            try {
                val downloaded = VerifiedUpdateManager.findDownloadedUpdate(app)
                if (downloaded != null) {
                    _updateResult.value = downloaded
                    _showUpdateDialog.value = true
                }
                toast(R.string.update_checking_for_update)
                val result = UpdateCheckerManager.checkForUpdate(_checkPreRelease.value)
                if (result.hasUpdate) {
                    _updateResult.value = VerifiedUpdateManager.findDownloadedUpdate(app, result) ?: result
                    _showUpdateDialog.value = true
                } else if (downloaded == null) {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val downloaded = VerifiedUpdateManager.findDownloadedUpdate(app)
                if (downloaded != null) {
                    _updateResult.value = downloaded
                    _showUpdateDialog.value = true
                    return@launchLoading
                }
                LogUtil.e(AppConfig.TAG, "Failed to check for updates: ${e.message}")
                if (e.message == null) {
                    toastError(R.string.toast_failure)
                } else {
                    toastError(e.message.orEmpty())
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }
}
