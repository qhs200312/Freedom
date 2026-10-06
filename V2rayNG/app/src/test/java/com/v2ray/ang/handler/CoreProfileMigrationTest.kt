package com.v2ray.ang.handler

import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreProfileMigrationTest {
    @Test
    fun legacyHysteria2IsBoundToSingBox() {
        val migrated = CoreProfileMigration.migrateProfileJson(
            """{"configType":"HYSTERIA2","remarks":"old","futureField":42}""",
            previousVersion = 0,
        ).orEmpty()
        val json = JsonUtil.parseString(migrated)!!.asJsonObject

        assertEquals("SING_BOX", json.get("preferredCore").asString)
        assertEquals(42, json.get("futureField").asInt)
    }

    @Test
    fun versionOneAutoHysteria2IsUpgradedToSingBox() {
        val migrated = CoreProfileMigration.migrateProfileJson(
            """{"configType":"HYSTERIA2","preferredCore":"AUTO"}""",
            previousVersion = 1,
        ).orEmpty()

        assertEquals("SING_BOX", JsonUtil.parseString(migrated)!!.asJsonObject.get("preferredCore").asString)
    }

    @Test
    fun explicitXrayChoiceIsPreserved() {
        val raw = """{"configType":"HYSTERIA2","preferredCore":"XRAY"}"""

        assertEquals(raw, CoreProfileMigration.migrateProfileJson(raw, previousVersion = 1))
    }

    @Test
    fun nonHysteriaProfileKeepsLegacyAutoBehavior() {
        val migrated = CoreProfileMigration.migrateProfileJson(
            """{"configType":"VLESS","futureField":true}""",
            previousVersion = 0,
        ).orEmpty()
        val json = JsonUtil.parseString(migrated)!!.asJsonObject

        assertEquals("AUTO", json.get("preferredCore").asString)
        assertTrue(json.get("futureField").asBoolean)
    }
}
