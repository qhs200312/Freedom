package com.v2ray.ang.routing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import corebundle.Corebundle

internal object YamlRuleNativeValidator {
    /** Both bundled cores use Go regular expressions; Java Pattern accepts incompatible features. */
    fun regexIsValid(pattern: String): Boolean = runCatching {
        val config = JsonObject().apply {
            add("outbounds", JsonArray().apply {
                add(JsonObject().apply { addProperty("type", "direct"); addProperty("tag", "direct") })
            })
            add("route", JsonObject().apply {
                add("rules", JsonArray().apply {
                    add(JsonObject().apply {
                        add("domain_regex", JsonArray().apply { add(pattern) })
                        addProperty("action", "route")
                        addProperty("outbound", "direct")
                    })
                })
            })
        }
        Corebundle.checkSingBoxConfig(config.toString())
    }.isSuccess
}
