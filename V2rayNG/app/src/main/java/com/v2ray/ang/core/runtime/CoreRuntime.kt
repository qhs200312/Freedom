package com.v2ray.ang.core.runtime

import com.v2ray.ang.core.RuntimeCore
import com.v2ray.ang.dto.OutboundTrafficStat

/** Native APIs stay behind the runtime implementation selected for this session. */
internal interface CoreRuntime {
    val type: RuntimeCore
    val isRunning: Boolean
    fun start()
    fun stop()
    fun restart(shouldContinue: () -> Boolean): Boolean {
        stop()
        if (!shouldContinue()) return false
        start()
        return true
    }
    fun measureDelay(url: String): Long
    fun trafficStats(): List<OutboundTrafficStat>
    fun onScreenOn() {}
    fun onScreenOff() {}
}
