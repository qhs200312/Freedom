package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.handler.UpdateDownloadProgress
import com.v2ray.ang.handler.UpdateNotifications
import com.v2ray.ang.handler.VerifiedUpdateManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class UpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var notifications: UpdateNotifications
    private var currentResult: CheckUpdateResult? = null
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        notifications = UpdateNotifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        val result = intent?.getStringExtra(EXTRA_RESULT)?.let {
            JsonUtil.fromJsonSafe(it, CheckUpdateResult::class.java)
        }
        if (result == null || !result.hasUpdate || result.downloadUrl.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        currentResult = result
        val progress = UpdateDownloadProgress(result)
        VerifiedUpdateManager.reportProgress(progress)
        ServiceCompat.startForeground(
            this, UpdateNotifications.NOTIFICATION_ID, notifications.downloading(progress),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        job = scope.launch {
            try {
                val ready = VerifiedUpdateManager.download(applicationContext, result, ::updateProgress)
                ServiceCompat.stopForeground(this@UpdateDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notifications.showReady(ready)
                VerifiedUpdateManager.reportCompleted(ready)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Update download or verification failed", error)
                ServiceCompat.stopForeground(this@UpdateDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notifications.showFailure(result)
            } finally {
                VerifiedUpdateManager.reportProgress(null)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun updateProgress(progress: UpdateDownloadProgress) {
        val now = SystemClock.elapsedRealtime()
        if (progress.verifying || now - lastNotificationAt >= 500) {
            lastNotificationAt = now
            VerifiedUpdateManager.reportProgress(progress)
            notifications.showProgress(progress)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        currentResult?.let(notifications::showFailure)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        VerifiedUpdateManager.reportProgress(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RESULT = "update_result"
    }
}
