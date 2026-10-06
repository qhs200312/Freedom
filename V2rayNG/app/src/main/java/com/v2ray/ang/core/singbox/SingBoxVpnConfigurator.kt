package com.v2ray.ang.core.singbox

import android.net.VpnService
import libbox.Libbox
import libbox.TunOptions

/** Configure Android from the options libbox actually uses, not a second settings snapshot. */
internal object SingBoxVpnConfigurator {
    fun configure(builder: VpnService.Builder, options: TunOptions) {
        builder.setSession("Freedom")
        builder.setMtu(options.mtu)
        val ipv4 = options.inet4Address
        while (ipv4.hasNext()) ipv4.next().let { builder.addAddress(it.address(), it.prefix()) }
        val ipv6 = options.inet6Address
        while (ipv6.hasNext()) ipv6.next().let { builder.addAddress(it.address(), it.prefix()) }
        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }
            val routes4 = options.inet4RouteRange
            while (routes4.hasNext()) routes4.next().let { builder.addRoute(it.address(), it.prefix()) }
            val routes6 = options.inet6RouteRange
            while (routes6.hasNext()) routes6.next().let { builder.addRoute(it.address(), it.prefix()) }
        }
    }
}
