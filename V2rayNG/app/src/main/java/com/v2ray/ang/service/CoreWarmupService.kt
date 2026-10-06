package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/** Keeps the isolated VPN process alive without loading a core or opening any listener. */
class CoreWarmupService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder = binder
}
