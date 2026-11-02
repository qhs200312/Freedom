package com.v2ray.ang.routing

import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.RulesetItem

internal object RoutingRuleConversion {
    fun toXray(item: RulesetItem) = V2rayConfig.RoutingBean.RulesBean(
        ip = item.ip?.toList(),
        domain = item.domain?.toList(),
        process = item.process?.toList(),
        outboundTag = item.outboundTag,
        port = item.port,
        network = item.network,
        protocol = item.protocol?.toList(),
    )
}
