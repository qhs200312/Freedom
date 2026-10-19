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
) : Closeable {
    private val nowhere = NowhereBridges(context, onUnexpectedExit)
    private val anytls = if (protectSocket == null) {
        AnyTlsBridges(context, onUnexpectedExit = onUnexpectedExit)
    } else AnyTlsBridges(context, protectSocket, onUnexpectedExit)
    val isAlive: Boolean get() = nowhere.isAlive && anytls.isAlive

    fun prepare(profile: ProfileItem): ProfileItem = anytls.prepare(nowhere.prepare(profile))

    override fun close() {
        try {
            anytls.close()
        } finally {
            nowhere.close()
        }
    }
}
