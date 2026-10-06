package com.v2ray.ang.core.xray

import android.net.VpnService
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.Utils

internal object XrayVpnConfigurator {
    fun configure(builder: VpnService.Builder) {
        val addresses = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(addresses.ipv4Client, 30)
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                builder.addRoute(it.substringBefore('/'), it.substringAfter('/').toInt())
            }
        } else builder.addRoute("0.0.0.0", 0)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED)) {
            builder.addAddress(addresses.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3)
                builder.addRoute("fc00::", 18)
            } else builder.addRoute("::", 0)
        }
        SettingsManager.getVpnDnsServers().filter(Utils::isPureIpAddress).forEach(builder::addDnsServer)
    }
}
