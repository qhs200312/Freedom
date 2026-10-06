package com.v2ray.ang.core

import android.content.Context
import corebundle.Corebundle
import corebundle.SingBoxController
import com.v2ray.ang.util.LogUtil

class SingBoxCoreManager {
    @Volatile
    private var controller: SingBoxController? = null
    private var platformInterface: SingBoxPlatformInterface? = null
    private var lastUplink = 0L
    private var lastDownlink = 0L

    @Synchronized
    fun start(context: Context, config: String, platform: SingBoxPlatformInterface) {
        stop()
        val instance = Corebundle.newSingBoxController(
            context.applicationContext.filesDir.absolutePath,
            platform,
        )
        try {
            Corebundle.checkSingBoxConfig(config)
            instance.start(config)
            controller = instance
            platformInterface = platform
            lastUplink = instance.uplinkTotal()
            lastDownlink = instance.downlinkTotal()
            LogUtil.i("SingBoxCore", "sing-box ${Corebundle.singBoxVersion()} started")
        } catch (e: Exception) {
            runCatching { instance.close() }
            platform.close()
            controller = null
            platformInterface = null
            throw e
        }
    }

    @Synchronized
    fun stop() {
        try {
            controller?.let {
                runCatching { it.stop() }
                    .onFailure { e -> LogUtil.w("SingBoxCore", "Failed to stop sing-box: ${e.message}") }
                it.close()
            }
        } finally {
            controller = null
            platformInterface?.close()
            platformInterface = null
            lastUplink = 0L
            lastDownlink = 0L
        }
    }

    fun isRunning(): Boolean = controller?.isRunning == true
    companion object {
        fun version(): String = Corebundle.singBoxVersion()
    }

    @Synchronized
    fun queryTrafficDelta(): Pair<Long, Long> {
        val instance = controller ?: return 0L to 0L
        val uplink = instance.uplinkTotal()
        val downlink = instance.downlinkTotal()
        val uplinkDelta = (uplink - lastUplink).coerceAtLeast(0L)
        val downlinkDelta = (downlink - lastDownlink).coerceAtLeast(0L)
        lastUplink = uplink
        lastDownlink = downlink
        return uplinkDelta to downlinkDelta
    }
}
