package com.v2ray.ang.ui.settings

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SettingsChangeManager

/** Save synchronously: a recreated activity must read the new language on attach. */
internal fun selectLanguage(
    selected: String,
    current: String,
    persist: (String) -> Boolean,
    refresh: () -> Unit,
) {
    if (selected == current || !persist(selected)) return
    SettingsChangeManager.notifySettingChanged(AppConfig.PREF_LANGUAGE)
    refresh()
}
