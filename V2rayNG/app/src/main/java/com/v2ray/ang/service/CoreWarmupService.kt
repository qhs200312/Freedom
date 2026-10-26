package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Bundle
import android.os.Process
import android.os.ResultReceiver
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Keeps the isolated VPN process alive without loading a core or opening any listener. */
class CoreWarmupService : Service() {
    companion object {
        const val ACTION_RECOVERY_STOP = "storage_recovery_stop"
        const val EXTRA_TRANSACTION = "recovery_transaction"
        const val EXTRA_RECEIVER = "recovery_receiver"
    }

    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder {
        SecureStorage.ensureAvailable(this)
        return binder
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_RECOVERY_STOP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val transaction = intent.getStringExtra(EXTRA_TRANSACTION)
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)
        if (transaction == null || receiver == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        CoroutineScope(Dispatchers.IO).launch {
            CoreServiceManager.stopForStorageRecovery {
                receiver.send(0, Bundle().apply {
                    putString(EXTRA_TRANSACTION, transaction)
                    putInt("pid", Process.myPid())
                })
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}
