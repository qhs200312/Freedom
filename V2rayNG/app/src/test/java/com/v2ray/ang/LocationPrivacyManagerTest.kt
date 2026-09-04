package com.v2ray.ang

import com.v2ray.ang.root.LocationPrivacyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPrivacyManagerTest {
    @Test
    fun parsesRootScriptStateValues() {
        val output = "noise\nORIGINAL_MODE=3\nDISABLED=1\n"

        assertEquals(3, LocationPrivacyManager.parseValue(output, "ORIGINAL_MODE"))
        assertEquals(1, LocationPrivacyManager.parseValue(output, "DISABLED"))
        assertNull(LocationPrivacyManager.parseValue(output, "MISSING"))
    }

    @Test
    fun disableScriptPreservesAlreadyDisabledState() {
        val script = LocationPrivacyManager.buildDisableScript()

        assertTrue(script.contains("ORIGINAL_MODE="))
        assertTrue(script.contains("if [ \"${'$'}mode\" = \"0\" ]"))
        assertTrue(script.contains("cmd location set-location-enabled false"))
    }

    @Test
    fun restoreScriptUsesRecordedMode() {
        val script = LocationPrivacyManager.buildRestoreScript(2)

        assertTrue(script.contains("settings put secure location_mode 2"))
        assertTrue(script.contains("RESTORED=1"))
    }
}
