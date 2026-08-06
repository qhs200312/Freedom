package com.v2ray.ang.enums

enum class ProxyMode(val value: String) {
    STANDARD("standard"),
    ROOT_TUN("root_tun"),
    TPROXY("tproxy");

    companion object {
        fun fromValue(value: String?): ProxyMode =
            entries.firstOrNull { it.value == value } ?: STANDARD
    }
}
