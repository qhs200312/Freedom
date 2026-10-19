package com.v2ray.ang.dto

import java.io.Serializable

data class NodeTrafficTotal(
    val uplink: Long = 0L,
    val downlink: Long = 0L,
) : Serializable {
    val total: Long get() = uplink + downlink
}

data class NodeTrafficBinding(
    val profileIds: List<String>,
    val countInTotal: Boolean = true,
)
