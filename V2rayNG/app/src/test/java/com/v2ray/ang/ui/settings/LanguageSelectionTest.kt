package com.v2ray.ang.ui.settings

import com.v2ray.ang.handler.SettingsChangeManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LanguageSelectionTest {
    @Before
    fun clearPendingSettingsChanges() {
        SettingsChangeManager.consumeRestartService()
        SettingsChangeManager.consumeSetupGroupTab()
    }

    @Test
    fun newLanguageIsSavedBeforeRecreatingAndDoesNotRestartTheProxy() {
        var stored = "en"
        val calls = mutableListOf<String>()
        selectLanguage("zh-rCN", stored, persist = {
            stored = it
            calls += "saved"
            true
        }, refresh = {
            assertEquals("zh-rCN", stored)
            assertTrue(SettingsChangeManager.consumeSetupGroupTab())
            assertFalse(SettingsChangeManager.consumeRestartService())
            calls += "refreshed"
        })
        assertEquals(listOf("saved", "refreshed"), calls)
    }

    @Test
    fun selectingTheCurrentLanguageDoesNotRecreateTheActivity() {
        selectLanguage("en", "en", persist = { error("No change to persist") },
            refresh = { error("No recreation needed") })
        assertFalse(SettingsChangeManager.consumeSetupGroupTab())
        assertFalse(SettingsChangeManager.consumeRestartService())
    }

    @Test
    fun failedPersistenceDoesNotRecreateWithTheOldLanguage() {
        selectLanguage("zh-rCN", "en", persist = { false },
            refresh = { error("Do not refresh before successful persistence") })
        assertFalse(SettingsChangeManager.consumeSetupGroupTab())
        assertFalse(SettingsChangeManager.consumeRestartService())
    }

    @Test
    fun returningToSystemAndRtlLanguagesAlsoAppliesImmediately() {
        var stored = "zh-rCN"
        var refreshed = 0
        for (language in listOf("auto", "ar", "fa", "zh-rTW", "en")) {
            selectLanguage(language, stored, persist = { stored = it; true }, refresh = {
                assertEquals(language, stored)
                refreshed++
            })
        }
        assertEquals(5, refreshed)
        assertFalse(SettingsChangeManager.consumeRestartService())
    }
}
