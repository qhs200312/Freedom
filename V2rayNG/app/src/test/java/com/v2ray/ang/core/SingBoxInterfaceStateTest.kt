package com.v2ray.ang.core

import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class SingBoxInterfaceStateTest {
    private val updates = mutableListOf<String>()
    private var index = 7
    private val state = SingBoxInterfaceState(interfaceIndex = { index }) { name, _, _, _ ->
        updates += name
    }
    private val network = mock<Network>()
    private val capabilities = mock<NetworkCapabilities> {
        on { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } doReturn true
    }
    private fun link(
        name: String,
        addresses: Boolean = true,
        dns: Boolean = true,
        route: Boolean = true,
        address: String = "192.0.2.1",
    ): LinkProperties {
        val addressList = if (addresses) listOf(mock<LinkAddress> {
            on { getAddress() } doReturn InetAddress.getByName(address)
        }) else emptyList()
        val routes = if (route) listOf(mock<RouteInfo> {
            on { isDefaultRoute } doReturn true
        }) else emptyList()
        return mock {
            on { interfaceName } doReturn name
            on { linkAddresses } doReturn addressList
            on { dnsServers } doReturn if (dns) listOf(InetAddress.getByName("1.1.1.1")) else emptyList()
            on { getRoutes() } doReturn routes
        }
    }

    @Test
    fun coldStartWaitsForAddressRouteAndDnsOnMobileNetwork() {
        state.onAvailable(network)
        state.onCapabilitiesChanged(network, capabilities)
        state.onLinkPropertiesChanged(network, link("rmnet0", addresses = false))
        state.onLinkPropertiesChanged(network, link("rmnet0", address = "fe80::1"))
        state.onLinkPropertiesChanged(network, link("rmnet0", route = false))
        state.onLinkPropertiesChanged(network, link("rmnet0", dns = false))
        assertTrue(updates.isEmpty())
        state.onLinkPropertiesChanged(network, link("rmnet0"))
        assertEquals(listOf("rmnet0"), updates)
    }

    @Test
    fun coldStartWaitsForLinkProperties() {
        state.onAvailable(network)
        state.onCapabilitiesChanged(network, capabilities)
        assertTrue(updates.isEmpty())
        state.onLinkPropertiesChanged(network, link("wlan0"))
        assertEquals(listOf("wlan0"), updates)
    }

    @Test
    fun linkPropertiesCanArriveBeforeCapabilities() {
        state.onAvailable(network)
        state.onLinkPropertiesChanged(network, link("wlan0"))
        assertTrue(updates.isEmpty())
        state.onCapabilitiesChanged(network, capabilities)
        assertEquals(listOf("wlan0"), updates)
    }

    @Test
    fun lateOldNetworkEventsDoNotClearOrOverwriteReplacement() {
        state.onAvailable(network)
        state.onCapabilitiesChanged(network, capabilities)
        state.onLinkPropertiesChanged(network, link("wlan0"))
        val replacement = mock<Network>()
        state.onAvailable(replacement)
        state.onCapabilitiesChanged(replacement, capabilities)
        state.onLinkPropertiesChanged(replacement, link("rmnet0"))
        state.onCapabilitiesChanged(network, capabilities)
        state.onLinkPropertiesChanged(network, link("wlan0"))
        state.onLost(network)
        assertEquals(listOf("wlan0", "", "rmnet0"), updates)
        state.onLost(replacement)
        assertEquals(listOf("wlan0", "", "rmnet0", ""), updates)
    }

    @Test
    fun replacementMustNotReusePreviousLinkProperties() {
        state.onAvailable(network)
        state.onCapabilitiesChanged(network, capabilities)
        state.onLinkPropertiesChanged(network, link("wlan0"))
        val replacement = mock<Network>()
        state.onAvailable(replacement)
        state.onCapabilitiesChanged(replacement, capabilities)
        assertEquals(listOf("wlan0", ""), updates)
    }

    @Test
    fun differentAndroidNetworkOnSameWifiInterfaceInvalidatesOldConnections() {
        state.onAvailable(network)
        state.onCapabilitiesChanged(network, capabilities)
        state.onLinkPropertiesChanged(network, link("wlan0"))
        val hotspot = mock<Network>()
        state.onAvailable(hotspot)
        assertEquals(listOf("wlan0", ""), updates)
        state.onCapabilitiesChanged(hotspot, capabilities)
        state.onLinkPropertiesChanged(hotspot, link("wlan0"))
        state.onLost(network)
        assertEquals(listOf("wlan0", "", "wlan0"), updates)
    }

    @Test
    fun repeatedAvailabilityOfSameNetworkDoesNotInvalidateCurrentConnections() {
        state.onAvailable(network)
        state.onCapabilitiesChanged(network, capabilities)
        state.onLinkPropertiesChanged(network, link("wlan0"))
        state.onAvailable(network)
        assertEquals(listOf("wlan0"), updates)
    }

    @Test
    fun unavailableInterfaceIndexIsRetriedOnNextUpdate() {
        index = -1
        state.onAvailable(network)
        state.onCapabilitiesChanged(network, capabilities)
        state.onLinkPropertiesChanged(network, link("wlan0"))
        assertTrue(updates.isEmpty())
        index = 7
        state.onLinkPropertiesChanged(network, link("wlan0"))
        assertEquals(listOf("wlan0"), updates)
    }

    @Test
    fun vpnInterfaceIsNeverPublishedAsUpstream() {
        val vpn = mock<NetworkCapabilities> {
            on { hasTransport(NetworkCapabilities.TRANSPORT_VPN) } doReturn true
        }
        state.onAvailable(network)
        state.onCapabilitiesChanged(network, vpn)
        state.onLinkPropertiesChanged(network, link("tun0"))
        assertTrue(updates.isEmpty())
    }
}
