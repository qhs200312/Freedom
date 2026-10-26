package com.v2ray.ang.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.v2ray.ang.core.LauncherManager

/** Remains reachable after an OEM cleaner has killed the core's dynamic receiver. */
class NotificationStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_STOP) LauncherManager.stopService(context)
    }

    companion object {
        private const val ACTION_STOP = "com.v2ray.ang.action.NOTIFICATION_STOP"

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, NotificationStopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
