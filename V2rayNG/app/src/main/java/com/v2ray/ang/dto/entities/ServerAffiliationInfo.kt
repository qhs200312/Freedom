package com.v2ray.ang.dto.entities

data class ServerAffiliationInfo(var testDelayMillis: Long = 0L, var testMethod: String? = null) {
    fun getTestDelayString(): String {
        if (testDelayMillis == 0L) {
            return ""
        }
        val prefix = when (testMethod) {
            "ping" -> "Ping "
            "tcp" -> "TCP "
            else -> ""
        }
        return prefix + testDelayMillis.toString() + " ms"
    }
}
