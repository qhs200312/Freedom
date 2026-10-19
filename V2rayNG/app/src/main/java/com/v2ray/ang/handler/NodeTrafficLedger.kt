package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.NodeTrafficTotal
import com.v2ray.ang.dto.OutboundTrafficStat

internal class NodeTrafficLedger(initial: Map<String, NodeTrafficTotal> = emptyMap()) {
    private val totals = initial.toMutableMap()
    private val dirty = mutableSetOf<String>()

    fun record(stats: List<OutboundTrafficStat>) {
        stats.forEach { stat ->
            if (stat.value <= 0 || stat.tag == AppConfig.TAG_DIRECT ||
                stat.direction !in listOf(AppConfig.UPLINK, AppConfig.DOWNLINK)) return@forEach
            stat.profileIds.filter(String::isNotBlank).distinct().forEach { id ->
                val old = totals[id] ?: NodeTrafficTotal()
                totals[id] = if (stat.direction == AppConfig.UPLINK) old.copy(uplink = old.uplink + stat.value)
                    else old.copy(downlink = old.downlink + stat.value)
                dirty += id
            }
        }
    }

    fun snapshot(): Map<String, NodeTrafficTotal> = totals.toMap()

    fun persist(write: (String, NodeTrafficTotal) -> Unit) {
        dirty.forEach { id -> write(id, totals.getValue(id)) }
        dirty.clear()
    }
}
