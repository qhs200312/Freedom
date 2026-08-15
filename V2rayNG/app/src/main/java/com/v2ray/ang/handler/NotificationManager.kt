package com.v2ray.ang.handler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.TrafficSnapshot
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

object NotificationManager {
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1
    private const val NOTIFICATION_PENDING_INTENT_RESTART_V2RAY = 2
    private const val NOTIFICATION_ICON_THRESHOLD = 3000

    private var mBuilder: NotificationCompat.Builder? = null
    private var speedNotificationJob: Job? = null
    private var mNotificationManager: NotificationManager? = null

    /**
     * Starts the speed notification.
     * @param currentConfig The current profile configuration.
     */
    fun startSpeedNotification() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED, true) == false) return
        if (speedNotificationJob != null || CoreServiceManager.isRunning() == false) return

        var lastZeroSpeed = false

        speedNotificationJob = CoroutineScope(Dispatchers.IO).launch {
            TrafficStatsManager.snapshot.collect { snapshot ->
                lastZeroSpeed = updateSpeedNotificationOnce(
                    snapshot,
                    lastZeroSpeed,
                )
            }
        }
    }

    /**
     * Shows the notification.
     * @param currentConfig The current profile configuration.
     */
    fun showNotification(currentConfig: ProfileItem?) {
        val service = getService() ?: return

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val startMainIntent = Intent(service, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(service, NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent, flags)

        val stopV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        stopV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        stopV2RayIntent.putExtra("key", AppConfig.MSG_STATE_STOP)
        val stopV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_STOP_V2RAY, stopV2RayIntent, flags)

        val restartV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        restartV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        restartV2RayIntent.putExtra("key", AppConfig.MSG_STATE_RESTART)
        val restartV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_RESTART_V2RAY, restartV2RayIntent, flags)

        val channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel()
            } else {
                // If earlier version channel ID is not used
                // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
                ""
            }

        mBuilder = NotificationCompat.Builder(service, channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(currentConfig?.remarks ?: service.getString(R.string.app_name))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_delete_24dp,
                service.getString(R.string.notification_action_stop_v2ray),
                stopV2RayPendingIntent
            )
            .addAction(
                R.drawable.ic_restore_24dp,
                service.getString(R.string.title_service_restart),
                restartV2RayPendingIntent
            )

        //mBuilder?.setDefaults(NotificationCompat.FLAG_ONLY_ALERT_ONCE)

        service.startForeground(NOTIFICATION_ID, mBuilder?.build())
    }

    /**
     * Fulfills or refreshes the foreground-service contract before a start command can
     * return early. A duplicate startForegroundService call still requires the service
     * to enter foreground state promptly, even when the core is already running.
     */
    fun ensureForeground() {
        val service = getService() ?: return
        val notification = mBuilder?.build()
        if (notification == null) showNotification(null) else service.startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Cancels the notification.
     */
    @Synchronized
    fun cancelNotification() {
        val service = getService() ?: return
        val notificationManager = getNotificationManager()

        // Stop updates before removing the foreground notification. TrafficStatsManager emits
        // one final zero-speed snapshot during shutdown; without serialization that collector
        // can repost the notification immediately after stopForeground removes it.
        speedNotificationJob?.cancel()
        speedNotificationJob = null
        mBuilder = null
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        notificationManager?.cancel(NOTIFICATION_ID)

        mNotificationManager = null
    }

    /**
     * Stops the speed notification.
     */
    fun stopSpeedNotification() {
        speedNotificationJob?.let {
            it.cancel()
            speedNotificationJob = null
            updateNotification("", "", 0, 0)
        }
    }

    /**
     * Creates a notification channel for Android O and above.
     * @return The channel ID.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = AppConfig.RAY_NG_CHANNEL_ID
        val channelName = AppConfig.RAY_NG_CHANNEL_NAME
        // Foreground-service notifications must remain visible; LOW is silent but valid.
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        chan.lightColor = Color.DKGRAY
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        getNotificationManager()?.createNotificationChannel(chan)
        return channelId
    }

    /**
     * Updates the notification with the given content text and traffic data.
     * @param contentText The content text.
     * @param proxyTraffic The proxy traffic.
     * @param directTraffic The direct traffic.
     */
    @Synchronized
    private fun updateNotification(
        proxyText: String,
        directText: String,
        proxyTraffic: Long,
        directTraffic: Long,
    ) {
        if (mBuilder != null) {
            if (proxyTraffic < NOTIFICATION_ICON_THRESHOLD && directTraffic < NOTIFICATION_ICON_THRESHOLD) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_name)
            } else if (proxyTraffic > directTraffic) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_proxy)
            } else {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_direct)
            }
            if (proxyText.isNotBlank()) {
                mBuilder?.setContentTitle(proxyText)
            }
            mBuilder?.setContentText(directText)
            mBuilder?.setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOf(proxyText, directText).filter { it.isNotBlank() }.joinToString("\n"),
                ),
            )
            getNotificationManager()?.notify(NOTIFICATION_ID, mBuilder?.build())
        }
    }

    /**
     * Gets the notification manager.
     * @return The notification manager.
     */
    private fun getNotificationManager(): NotificationManager? {
        if (mNotificationManager == null) {
            val service = getService() ?: return null
            mNotificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        return mNotificationManager
    }

    /**
     * Appends the speed string to the given text.
     * @param text The text to append to.
     * @param name The name of the tag.
     * @param up The uplink speed.
     * @param down The downlink speed.
     */
    private fun formatSpeedLine(name: String, up: Long, down: Long): String =
        "$name  ↑ ${up.toSpeedString()}  ↓ ${down.toSpeedString()}"

    /**
     * Updates the speed notification once.
     * Queries traffic stats, separates proxy and direct, and updates the notification.
     * @param lastZeroSpeed The previous zero speed state.
     * @return The current zero speed state.
     */
    private fun updateSpeedNotificationOnce(
        snapshot: TrafficSnapshot,
        lastZeroSpeed: Boolean,
    ): Boolean {
        val proxyTotal = snapshot.proxyUplinkSpeed + snapshot.proxyDownlinkSpeed
        val directTotal = snapshot.directUplinkSpeed + snapshot.directDownlinkSpeed
        val zeroSpeed = proxyTotal + directTotal == 0L
        if (!zeroSpeed || !lastZeroSpeed) {
            val service = getService() ?: return lastZeroSpeed
            val proxyText = formatSpeedLine(
                service.getString(R.string.notification_speed_proxy),
                snapshot.proxyUplinkSpeed,
                snapshot.proxyDownlinkSpeed,
            )
            val directText = formatSpeedLine(
                service.getString(R.string.notification_speed_direct),
                snapshot.directUplinkSpeed,
                snapshot.directDownlinkSpeed,
            )
            updateNotification(proxyText, directText, proxyTotal, directTotal)
        }
        return zeroSpeed
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    private fun getService(): Service? {
        return CoreServiceManager.serviceControl?.get()?.getService()
    }
}
