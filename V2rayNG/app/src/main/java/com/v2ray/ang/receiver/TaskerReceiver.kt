package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.TaskerAuthorization
import com.v2ray.ang.util.LogUtil

class TaskerReceiver : BroadcastReceiver() {

    /**
     * This method is called when the BroadcastReceiver is receiving an Intent broadcast.
     * It retrieves the bundle from the intent and checks the switch and guid values.
     * Depending on the switch value, it starts or stops the V2Ray service.
     *
     * @param context The Context in which the receiver is running.
     * @param intent The Intent being received.
     */
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != "com.twofortyfouram.locale.intent.action.FIRE_SETTING") return
            val bundle = intent.getBundleExtra(AppConfig.TASKER_EXTRA_BUNDLE) ?: return
            if (!TaskerAuthorization.isAuthorized(context, bundle.getString(TaskerAuthorization.EXTRA_TOKEN))) return
            val guid = bundle.getString(AppConfig.TASKER_EXTRA_BUNDLE_GUID).orEmpty()

            if (!bundle.containsKey(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH) ||
                TextUtils.isEmpty(guid)
            ) return
            val switch = bundle.getBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, false)
            if (switch) {
                if (guid == AppConfig.TASKER_DEFAULT_GUID) {
                    LauncherManager.startServiceFromToggle(context)
                } else {
                    LauncherManager.startService(context, guid)
                }
            } else {
                LauncherManager.stopService(context)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing Tasker broadcast", e)
        }
    }
}
