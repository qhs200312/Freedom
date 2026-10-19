package com.v2ray.ang.core.singbox

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.OutboundTrafficStat

/** Accumulates native events until the single traffic poller consumes them. */
internal class SingBoxTrafficTracker {
    private data class Connection(
        val tag: String,
        var uplink: Long,
        var downlink: Long,
        val ignored: Boolean,
    )
    private val active = mutableMapOf<String, Connection>()
    private val closed = LinkedHashMap<String, Connection>()
    private val pending = mutableMapOf<String, LongArray>()

    @Synchronized
    fun record(
        id: String,
        outbound: String,
        uplink: Long,
        downlink: Long,
        closed: Boolean,
        ignored: Boolean = false,
    ) {
        val previous = active[id] ?: this.closed[id]
        val tag = outbound.ifBlank { AppConfig.TAG_PROXY }
        val up = uplink.coerceAtLeast(0L)
        val down = downlink.coerceAtLeast(0L)
        if (!ignored) {
            add(tag, (up - (previous?.uplink ?: 0L)).coerceAtLeast(0L),
                (down - (previous?.downlink ?: 0L)).coerceAtLeast(0L))
        }
        val connection = Connection(tag, up, down, ignored)
        if (closed) {
            active.remove(id)
            this.closed[id] = connection
            // Native initial snapshots retain at most 1000 closed connections.
            while (this.closed.size > 2048) this.closed.remove(this.closed.keys.first())
        } else {
            active[id] = connection
        }
    }

    @Synchronized
    fun addDelta(id: String, uplink: Long, downlink: Long) {
        val connection = active[id] ?: return
        val up = uplink.coerceAtLeast(0L)
        val down = downlink.coerceAtLeast(0L)
        connection.uplink += up
        connection.downlink += down
        if (!connection.ignored) add(connection.tag, up, down)
    }

    @Synchronized
    fun drain(): List<OutboundTrafficStat> = buildList {
        pending.forEach { (tag, bytes) ->
            add(OutboundTrafficStat(tag, AppConfig.UPLINK, bytes[0]))
            add(OutboundTrafficStat(tag, AppConfig.DOWNLINK, bytes[1]))
        }
        pending.clear()
    }

    private fun add(tag: String, up: Long, down: Long) {
        val bytes = pending.getOrPut(tag) { LongArray(2) }
        bytes[0] += up
        bytes[1] += down
    }
}
