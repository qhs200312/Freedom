package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.core.singbox.SingBoxTrafficTracker
import com.v2ray.ang.core.singbox.SingBoxEnvironment
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.util.LogUtil
import libbox.CommandClient
import libbox.CommandClientHandler
import libbox.CommandClientOptions
import libbox.CommandServer
import libbox.CommandServerHandler
import libbox.ConnectionEvents
import libbox.Libbox
import libbox.LogIterator
import libbox.OutboundGroupItemIterator
import libbox.OutboundGroupIterator
import libbox.OverrideOptions
import libbox.StatusMessage
import libbox.StringIterator
import libbox.SystemProxyStatus

class SingBoxCoreManager {
    @Volatile private var controller: CommandServer? = null
    private var client: CommandClient? = null
    private var platformInterface: SingBoxPlatformInterface? = null
    private var traffic = SingBoxTrafficTracker()

    @Synchronized
    fun start(context: Context, config: String, platform: SingBoxPlatformInterface) {
        stop()
        val tracker = SingBoxTrafficTracker()
        traffic = tracker
        var instance: CommandServer? = null
        var subscriber: CommandClient? = null
        try {
            SingBoxEnvironment.initialize(context)
            Libbox.checkConfig(config)
            instance = Libbox.newCommandServer(object : CommandServerHandler {
                override fun serviceStop() = stop()
                override fun serviceReload() = Unit
                override fun getSystemProxyStatus(): SystemProxyStatus? = null
                override fun setSystemProxyEnabled(enabled: Boolean) = Unit
                override fun triggerNativeCrash() = Unit
                override fun writeDebugMessage(message: String?) = Unit
                override fun connectSSHAgent(): Int = -1
            }, platform)
            instance.start()
            instance.startOrReloadService(config, OverrideOptions())
            subscriber = Libbox.newCommandClient(trafficHandler(tracker), CommandClientOptions().apply {
                statusInterval = 1_000_000_000L
                addCommand(Libbox.CommandConnections)
            })
            subscriber.connect()
            controller = instance
            client = subscriber
            platformInterface = platform
            LogUtil.i("SingBoxCore", "sing-box ${version()} started")
        } catch (error: Exception) {
            runCatching { subscriber?.disconnect() }
            runCatching { instance?.closeService() }
            runCatching { instance?.close() }
            platform.close()
            throw error
        }
    }

    @Synchronized
    fun stop() {
        val instance = controller
        controller = null
        try {
            runCatching { client?.disconnect() }
            client = null
            runCatching { instance?.closeService() }
                .onFailure { LogUtil.w("SingBoxCore", "Failed to stop sing-box: ${it.message}") }
            instance?.close()
        } finally {
            platformInterface?.close()
            platformInterface = null
            traffic = SingBoxTrafficTracker()
        }
    }

    fun isRunning(): Boolean = controller != null

    @Synchronized
    fun queryTrafficStats(): List<OutboundTrafficStat> = traffic.drain()

    private fun trafficHandler(tracker: SingBoxTrafficTracker) = object : CommandClientHandler {
        override fun writeConnectionEvents(events: ConnectionEvents?) {
            val iterator = events?.iterator() ?: return
            while (iterator.hasNext()) {
                val event = iterator.next() ?: continue
                val connection = event.connection
                when (event.type.toLong()) {
                    Libbox.ConnectionEventNew, Libbox.ConnectionEventClosed -> {
                        if (connection != null) {
                            tracker.record(
                                event.id,
                                connection.outbound,
                                connection.uplinkTotal,
                                connection.downlinkTotal,
                                closed = connection.closedAt > 0L ||
                                    event.type.toLong() == Libbox.ConnectionEventClosed,
                                ignored = connection.outboundType == "dns",
                            )
                        }
                    }
                    Libbox.ConnectionEventUpdate -> tracker.addDelta(
                        event.id, event.uplinkDelta, event.downlinkDelta,
                    )
                }
            }
        }

        override fun disconnected(message: String?) {
            LogUtil.w("SingBoxCore", "Traffic event stream disconnected: $message")
        }
        override fun connected() = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun clearLogs() = Unit
        override fun writeLogs(messageList: LogIterator?) = Unit
        override fun writeStatus(message: StatusMessage?) = Unit
        override fun writeGroups(message: OutboundGroupIterator?) = Unit
        override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
        override fun updateClashMode(newMode: String?) = Unit
    }

    companion object {
        fun version(): String = Libbox.version()
    }
}
