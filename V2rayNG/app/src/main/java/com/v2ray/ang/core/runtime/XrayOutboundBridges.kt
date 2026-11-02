package com.v2ray.ang.core.runtime

import android.content.Context
import com.v2ray.ang.core.anytls.AnyTlsBridges
import com.v2ray.ang.core.nowhere.NowhereBridges
import com.v2ray.ang.dto.entities.ProfileItem
import java.io.Closeable

internal class XrayOutboundBridges(
    context: Context,
    protectSocket: ((Int) -> Boolean)? = null,
    onUnexpectedExit: () -> Unit = {},
    honorCorePreferences: Boolean = false,
) : Closeable {
    private val nowhere = NowhereBridges(context, onUnexpectedExit)
    private val anytls = if (protectSocket == null) {
        AnyTlsBridges(context, onUnexpectedExit = onUnexpectedExit)
    } else AnyTlsBridges(context, protectSocket, onUnexpectedExit)
    private val routing = if (honorCorePreferences) RoutingCoreBridges(
        context, com.v2ray.ang.core.RuntimeCore.XRAY,
        requireNotNull(protectSocket), onUnexpectedExit,
    ) else null
    val isAlive: Boolean get() = nowhere.isAlive && anytls.isAlive && routing?.isAlive != false

    fun prepare(profile: ProfileItem): ProfileItem {
        val selected = routing?.prepare(profile) ?: profile
        return anytls.prepare(nowhere.prepare(selected))
    }

    fun start() { routing?.start() }

    override fun close() {
        try {
            routing?.close()
        } finally {
            try { anytls.close() } finally { nowhere.close() }
        }
    }
}
