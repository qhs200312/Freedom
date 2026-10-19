package com.v2ray.ang.fmt

import com.v2ray.ang.core.nowhere.NowhereEndpoint
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

object NowhereFmt {
    fun parse(value: String): ProfileItem? = runCatching {
        val endpoint = NowhereEndpoint.parse(value)
        ProfileItem(
            configType = EConfigType.NOWHERE,
            remarks = endpoint.name,
            server = endpoint.host,
            serverPort = endpoint.port.toString(),
            password = endpoint.key,
            network = endpoint.carrier,
            nowhereMorph = endpoint.morph,
            sni = endpoint.sni,
            pinnedCA256 = endpoint.pin,
        )
    }.getOrNull()

    fun endpoint(profile: ProfileItem): NowhereEndpoint = NowhereEndpoint(
        host = profile.server.orEmpty(),
        port = profile.serverPort?.toIntOrNull() ?: 0,
        key = profile.password.orEmpty(),
        carrier = profile.network?.ifBlank { "tcp" } ?: "tcp",
        morph = profile.nowhereMorph,
        sni = profile.sni?.takeUnless { it.isBlank() || it == "none" },
        pin = profile.pinnedCA256?.takeUnless { it.isBlank() || it == "none" },
        name = profile.remarks,
    )

    fun toUri(profile: ProfileItem): String = endpoint(profile).shareLink().removePrefix("nowhere://")
}
