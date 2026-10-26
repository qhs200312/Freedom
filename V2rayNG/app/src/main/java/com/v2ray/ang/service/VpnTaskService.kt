package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.util.RuntimeDiagnostics

/**
 * Preserves graceful TUN shutdown when the core uses stopWithTask for system-owned
 * cleanup. This service has no notification and shares the VPN process and lifetime.
 */
class VpnTaskService : Service() {
    private var owner: CoreVpnService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        owner = CoreServiceManager.serviceControl?.get()?.getService() as? CoreVpnService
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        RuntimeDiagnostics.core(this, "vpn_task_removed component=${rootIntent?.component?.className}")
        if (!isTransientShortcutTask(rootIntent)) {
            owner?.stopService()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        RuntimeDiagnostics.core(this, "vpn_task_owner_destroyed")
        owner?.stopService()
        owner = null
        super.onDestroy()
    }
}
