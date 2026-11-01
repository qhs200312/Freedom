package com.v2ray.ang.handler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.ui.checkupdate.UpdateInstallActivity

internal class UpdateNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, context.getString(R.string.update_notification_channel), NotificationManager.IMPORTANCE_LOW,
            ))
        }
    }

    fun downloading(progress: UpdateDownloadProgress): Notification {
        val text = when {
            progress.verifying -> context.getString(R.string.update_verifying_package)
            progress.percent != null -> context.getString(
                R.string.update_download_progress, progress.percent,
                Formatter.formatFileSize(context, progress.downloaded),
                Formatter.formatFileSize(context, progress.total),
            )
            else -> context.getString(
                R.string.update_download_bytes, Formatter.formatFileSize(context, progress.downloaded),
            )
        }
        val openApp = Intent(context, com.v2ray.ang.ui.checkupdate.CheckUpdateActivity::class.java)
        return base()
            .setContentTitle(context.getString(R.string.update_downloading_version, progress.result.latestVersion.orEmpty()))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(
                context, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            .setProgress(100, progress.percent ?: 0, progress.verifying || progress.percent == null)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    fun showProgress(progress: UpdateDownloadProgress) = notify(downloading(progress))

    fun showReady(result: CheckUpdateResult) {
        val install = PendingIntent.getActivity(
            context, 1, UpdateInstallActivity.intent(context, result.copy(isDownloaded = true)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.update_notification_ready, downloadPath())
        notify(base()
            .setSmallIcon(R.drawable.ic_check_update_24dp)
            .setContentTitle(context.getString(R.string.update_downloaded_version, result.latestVersion.orEmpty()))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(install)
            .addAction(R.drawable.ic_check_update_24dp, context.getString(R.string.update_install), install)
            .setAutoCancel(false)
            .build())
    }

    fun showFailure(result: CheckUpdateResult) {
        val retry = PendingIntent.getActivity(
            context, 2, UpdateInstallActivity.intent(context, result.copy(isDownloaded = false)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.update_download_failed)
        notify(base()
            .setContentTitle(context.getString(R.string.update_download_failed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(retry)
            .setAutoCancel(true)
            .build())
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun base() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_cloud_download_24dp)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun notify(notification: Notification) {
        // Downloading remains available in-app when the user has disabled notifications.
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "app_updates"
        fun downloadPath() = "Download/${VerifiedUpdateManager.DOWNLOAD_DIRECTORY}"
    }
}
