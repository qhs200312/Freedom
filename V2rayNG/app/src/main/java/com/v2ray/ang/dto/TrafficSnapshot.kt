package com.v2ray.ang.dto

import java.io.Serializable

data class TrafficSnapshot(
    val proxyUplinkSpeed: Long = 0L,
    val proxyDownlinkSpeed: Long = 0L,
    val directUplinkSpeed: Long = 0L,
    val directDownlinkSpeed: Long = 0L,
    val sessionUplink: Long = 0L,
    val sessionDownlink: Long = 0L,
    val totalUplink: Long = 0L,
    val totalDownlink: Long = 0L,
) : Serializable {
    val uplinkSpeed: Long get() = proxyUplinkSpeed + directUplinkSpeed
    val downlinkSpeed: Long get() = proxyDownlinkSpeed + directDownlinkSpeed
    val sessionTotal: Long get() = sessionUplink + sessionDownlink
    val total: Long get() = totalUplink + totalDownlink
}
