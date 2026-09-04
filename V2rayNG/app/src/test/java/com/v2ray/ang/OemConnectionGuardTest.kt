package com.v2ray.ang

import com.v2ray.ang.service.OemConnectionGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemConnectionGuardTest {
    @Test
    fun recognizesSupportedVendorFamilies() {
        assertTrue(OemConnectionGuard.isSupportedVendor("OPPO", "OPPO"))
        assertTrue(OemConnectionGuard.isSupportedVendor("OnePlus", "OnePlus"))
        assertTrue(OemConnectionGuard.isSupportedVendor("realme", "realme"))
        assertTrue(OemConnectionGuard.isSupportedVendor("vivo", "iQOO"))
        assertTrue(OemConnectionGuard.isSupportedVendor("Xiaomi", "Redmi"))
        assertTrue(OemConnectionGuard.isSupportedVendor("unknown", "POCO"))
    }

    @Test
    fun leavesOtherVendorsOnTheStandardServicePath() {
        assertFalse(OemConnectionGuard.isSupportedVendor("Google", "Pixel"))
        assertFalse(OemConnectionGuard.isSupportedVendor("Samsung", "Galaxy"))
        assertFalse(OemConnectionGuard.isSupportedVendor(null, null))
    }
}
