package com.v2ray.ang.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CoreVpnServiceTest {
    @Test
    fun singBoxIncludesSelfWithoutChangingOtherApps() {
        assertEquals(VpnAppSelection(true, emptySet()),
            vpnAppSelection("self", true, false, false, emptySet()))
        assertEquals(VpnAppSelection(true, setOf("other")),
            vpnAppSelection("self", true, true, true, setOf("self", "other")))
        assertEquals(VpnAppSelection(false, setOf("self", "other")),
            vpnAppSelection("self", true, true, false, setOf("other")))
    }

    @Test
    fun xrayRetainsSelfExclusionAndDoesNotMutateSavedSelection() {
        val apps = mutableSetOf("self", "other")
        assertEquals(VpnAppSelection(false, setOf("other")),
            vpnAppSelection("self", false, true, false, apps))
        assertEquals(setOf("self", "other"), apps)
        assertEquals(VpnAppSelection(true, setOf("self")),
            vpnAppSelection("self", false, false, false, emptySet()))
        assertEquals(VpnAppSelection(true, setOf("self", "other")),
            vpnAppSelection("self", false, true, true, setOf("other")))
    }

    @Test
    fun shortcutTasksDoNotStopTheRunningService() {
        assertTrue(isTransientShortcutClassName("com.v2ray.ang.ui.shortcut.ScStartActivity"))
        assertTrue(isTransientShortcutClassName("com.v2ray.ang.ui.shortcut.TaskerActivity"))
        assertFalse(isTransientShortcutClassName("com.v2ray.ang.ui.main.MainActivity"))
        assertFalse(isTransientShortcutClassName(null))
    }

}
