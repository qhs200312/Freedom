package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.NodeTrafficTotal
import com.v2ray.ang.dto.OutboundTrafficStat
import org.junit.Assert.*
import org.junit.Test

class NodeTrafficLedgerTest {
    @Test
    fun switchedAndAssignedNodesKeepIndependentTotalsAndIgnoreDirectTraffic() {
        val ledger = NodeTrafficLedger()
        ledger.record(listOf(
            OutboundTrafficStat("proxy", AppConfig.UPLINK, 20, listOf("local")),
            OutboundTrafficStat("routing-profile-us", AppConfig.DOWNLINK, 200, listOf("us")),
            OutboundTrafficStat("direct", AppConfig.DOWNLINK, 900, listOf("local")),
        ))
        val previous = ledger.snapshot()
        ledger.record(listOf(OutboundTrafficStat("proxy", AppConfig.DOWNLINK, 80, listOf("us"))))
        assertEquals(NodeTrafficTotal(20, 0), ledger.snapshot()["local"])
        assertEquals(NodeTrafficTotal(0, 280), ledger.snapshot()["us"])
        assertEquals(NodeTrafficTotal(0, 200), previous["us"])
    }

    @Test
    fun chainHopsAndGroupMembersCountTheirOwnBytesWithoutDuplicatingAliases() {
        val ledger = NodeTrafficLedger(mapOf("hop" to NodeTrafficTotal(5, 10)))
        ledger.record(listOf(
            OutboundTrafficStat("proxy", AppConfig.UPLINK, 40, listOf("group", "member", "member")),
            OutboundTrafficStat("proxy-proxy-1", AppConfig.DOWNLINK, 100, listOf("hop"), countInTotal = false),
            OutboundTrafficStat("proxy", AppConfig.DOWNLINK, -50, listOf("member")),
            OutboundTrafficStat("proxy", "unknown", 100, listOf("member")),
        ))
        assertEquals(NodeTrafficTotal(40, 0), ledger.snapshot()["member"])
        assertEquals(NodeTrafficTotal(40, 0), ledger.snapshot()["group"])
        assertEquals(NodeTrafficTotal(5, 110), ledger.snapshot()["hop"])
        val writes = mutableMapOf<String, NodeTrafficTotal>()
        ledger.persist { id, total -> writes[id] = total }
        assertEquals(ledger.snapshot(), writes)
        writes.clear()
        ledger.persist { id, total -> writes[id] = total }
        assertTrue(writes.isEmpty())
    }
}
