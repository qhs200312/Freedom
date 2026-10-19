package com.v2ray.ang.core

import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.*
import org.junit.Test

class NodeTrafficBindingsTest {
    private fun node(name: String) = ProfileItem(
        configType = EConfigType.HTTP, remarks = name, server = "127.0.0.1", serverPort = "8080", addedTime = 0,
    )

    @Test
    fun selectedIdentityWinsOverAnIdenticalSavedNodeAndRoutingKeepsExplicitIdentity() {
        val selected = node("same")
        val assigned = node("other")
        val bindings = NodeTrafficBindings.resolve("selected", listOf(
            CoreConfigContext.ResolvedOutbound("proxy", selected, listOf(selected), CoreResolvedType.NORMAL),
            CoreConfigContext.ResolvedOutbound("routing-profile-assigned", assigned, listOf(assigned), CoreResolvedType.NORMAL),
        ), linkedMapOf("duplicate" to selected, "selected" to selected, "assigned" to assigned))
        assertEquals(listOf("selected"), bindings.getValue("proxy").profileIds)
        assertEquals(listOf("assigned"), bindings.getValue("routing-profile-assigned").profileIds)
    }

    @Test
    fun chainHopsMapToTheirRealProfilesAndOnlyTheFirstHopCountsInGlobalTotals() {
        val chain = node("chain").copy(configType = EConfigType.PROXYCHAIN)
        val exit = node("exit")
        val entry = node("entry")
        val bindings = NodeTrafficBindings.resolve("chain", listOf(
            CoreConfigContext.ResolvedOutbound("proxy", chain, listOf(exit, entry), CoreResolvedType.PROXYCHAIN),
        ), mapOf("chain" to chain, "exit" to exit, "entry" to entry))
        assertEquals(listOf("chain", "exit"), bindings.getValue("proxy").profileIds)
        assertEquals(listOf("entry"), bindings.getValue("proxy-proxy-1").profileIds)
        assertTrue(bindings.getValue("proxy").countInTotal)
        assertFalse(bindings.getValue("proxy-proxy-1").countInTotal)
    }

    @Test
    fun balancedMemberTagsPreserveMemberAndGroupAccounting() {
        val group = node("group").copy(configType = EConfigType.POLICYGROUP)
        val first = node("US")
        val second = node("JP")
        val bindings = NodeTrafficBindings.resolve("group", listOf(
            CoreConfigContext.ResolvedOutbound("proxy", group, listOf(first, second), CoreResolvedType.POLICYGROUP),
        ), mapOf("group" to group, "us" to first, "jp" to second))
        assertEquals(listOf("group", "us"), bindings.getValue("proxy-proxy-1-US").profileIds)
        assertEquals(listOf("group", "jp"), bindings.getValue("proxy-proxy-2-JP").profileIds)
    }

    @Test
    fun customNamedOutboundsCountTheProfileOnceAcrossAnInternalChain() {
        val bindings = NodeTrafficBindings.custom("""{"outbounds":[
            {"tag":"exit","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"entry"}}},
            {"tag":"entry","protocol":"socks"},
            {"tag":"direct","protocol":"freedom"},
            {"tag":"blocked","protocol":"blackhole"}
        ]}""", "custom")
        assertEquals(listOf("custom"), bindings.getValue("exit").profileIds)
        assertTrue(bindings.getValue("exit").countInTotal)
        assertEquals(emptyList<String>(), bindings.getValue("entry").profileIds)
        assertFalse(bindings.getValue("entry").countInTotal)
        assertFalse(bindings.containsKey("direct"))
        assertFalse(bindings.containsKey("blocked"))
    }
}
