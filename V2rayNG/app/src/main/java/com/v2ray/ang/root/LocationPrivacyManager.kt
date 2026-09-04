package com.v2ray.ang.root

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

/** Root-only synchronization between the proxy lifecycle and Android's location master switch. */
object LocationPrivacyManager {
    private const val DEFAULT_ENABLED_LOCATION_MODE = 3
    private val stateLock = Any()

    fun onProxyStarted(context: Context) = synchronized(stateLock) {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_DISABLE_LOCATION_WITH_PROXY, false)) {
            return@synchronized
        }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCATION_DISABLED_BY_FREEDOM, false)) {
            return@synchronized
        }
        if (!hasRootAccess()) {
            LogUtil.w(AppConfig.TAG, "Location privacy: root is unavailable; location was not changed")
            return@synchronized
        }

        val result = RootShell.runScript(
            context,
            "location_privacy_disable.sh",
            buildDisableScript(),
        )
        val originalMode = parseValue(result.output, "ORIGINAL_MODE")
        val disabled = parseValue(result.output, "DISABLED") == 1
        if (!result.success || originalMode == null) {
            LogUtil.w(AppConfig.TAG, "Location privacy: failed to read or disable location")
            return@synchronized
        }

        if (disabled) {
            MmkvManager.encodeSettings(AppConfig.PREF_LOCATION_MODE_BEFORE_PROXY, originalMode)
            MmkvManager.encodeSettings(AppConfig.PREF_LOCATION_DISABLED_BY_FREEDOM, true)
            LogUtil.i(AppConfig.TAG, "Location privacy: location disabled while proxy is active")
        } else {
            clearManagedState()
            LogUtil.i(AppConfig.TAG, "Location privacy: location was already disabled")
        }
    }

    fun onProxyStopped(context: Context) = synchronized(stateLock) {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCATION_DISABLED_BY_FREEDOM, false)) {
            return@synchronized
        }
        if (!hasRootAccess()) {
            LogUtil.w(AppConfig.TAG, "Location privacy: root is unavailable; location restore is pending")
            return@synchronized
        }

        val originalMode = MmkvManager.decodeSettingsInt(
            AppConfig.PREF_LOCATION_MODE_BEFORE_PROXY,
            DEFAULT_ENABLED_LOCATION_MODE,
        ).takeIf { it in 1..3 } ?: DEFAULT_ENABLED_LOCATION_MODE
        val result = RootShell.runScript(
            context,
            "location_privacy_restore.sh",
            buildRestoreScript(originalMode),
        )
        if (result.success && parseValue(result.output, "RESTORED") == 1) {
            clearManagedState()
            LogUtil.i(AppConfig.TAG, "Location privacy: original location mode restored")
        } else {
            LogUtil.w(AppConfig.TAG, "Location privacy: failed to restore location; retry on next stop")
        }
    }

    internal fun buildDisableScript(): String = """
        mode=${'$'}(settings get secure location_mode 2>/dev/null)
        case "${'$'}mode" in
          0|1|2|3) ;;
          *)
            if [ "${'$'}(cmd location is-location-enabled 2>/dev/null)" = "true" ]; then
              mode=$DEFAULT_ENABLED_LOCATION_MODE
            else
              mode=0
            fi
            ;;
        esac
        echo "ORIGINAL_MODE=${'$'}mode"
        if [ "${'$'}mode" = "0" ]; then
          echo "DISABLED=0"
          exit 0
        fi
        cmd location set-location-enabled false --user 0 >/dev/null 2>&1 || \
          settings put secure location_mode 0
        new_mode=${'$'}(settings get secure location_mode 2>/dev/null)
        if [ "${'$'}new_mode" = "0" ]; then
          echo "DISABLED=1"
          exit 0
        fi
        echo "DISABLED=0"
        exit 1
    """.trimIndent()

    internal fun buildRestoreScript(originalMode: Int): String = """
        cmd location set-location-enabled true --user 0 >/dev/null 2>&1 || true
        settings put secure location_mode $originalMode
        new_mode=${'$'}(settings get secure location_mode 2>/dev/null)
        if [ "${'$'}new_mode" = "$originalMode" ]; then
          echo "RESTORED=1"
          exit 0
        fi
        echo "RESTORED=0"
        exit 1
    """.trimIndent()

    internal fun parseValue(output: String, key: String): Int? =
        output.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.toIntOrNull()

    private fun hasRootAccess(): Boolean =
        RootManager.cachedRoot() || RootManager.isRootAvailable(forceRefresh = true)

    private fun clearManagedState() {
        MmkvManager.encodeSettings(AppConfig.PREF_LOCATION_DISABLED_BY_FREEDOM, false)
        MmkvManager.encodeSettings(AppConfig.PREF_LOCATION_MODE_BEFORE_PROXY, 0)
    }
}
