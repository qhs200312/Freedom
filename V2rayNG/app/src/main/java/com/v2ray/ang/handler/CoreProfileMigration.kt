package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.JsonUtil

/** Binds legacy Hysteria2 profiles to sing-box while preserving explicit user choices. */
object CoreProfileMigration {
    private const val VERSION = 2

    fun run() {
        val previousVersion = MmkvManager.decodeSettingsString(AppConfig.PREF_CORE_PROFILE_MIGRATION)
            ?.toIntOrNull()
            ?: 0
        if (previousVersion >= VERSION) return
        var updated = 0
        MmkvManager.decodeAllServerList().distinct().forEach { guid ->
            val raw = MmkvManager.decodeServerConfigRaw(guid) ?: return@forEach
            val migrated = migrateProfileJson(raw, previousVersion) ?: return@forEach
            if (migrated != raw) {
                MmkvManager.encodeProfileDirect(guid, migrated)
                updated++
            }
        }
        MmkvManager.encodeSettings(AppConfig.PREF_CORE_PROFILE_MIGRATION, VERSION.toString())
        LogUtil.i(AppConfig.TAG, "Core profile migration v$VERSION completed ($updated profiles updated)")
    }

    internal fun migrateProfileJson(raw: String, previousVersion: Int): String? {
        val json = JsonUtil.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val isHysteria2 = json.get("configType")?.takeIf { it.isJsonPrimitive }?.asString
            ?.equals("HYSTERIA2", ignoreCase = true) == true
        val storedCore = json.get("preferredCore")?.takeIf { it.isJsonPrimitive }?.asString

        val targetCore = when {
            isHysteria2 && storedCore == null -> "SING_BOX"
            isHysteria2 && previousVersion == 1 && storedCore.equals("AUTO", ignoreCase = true) -> "SING_BOX"
            storedCore == null -> "AUTO"
            else -> return raw
        }
        json.addProperty("preferredCore", targetCore)
        return json.toString()
    }
}
