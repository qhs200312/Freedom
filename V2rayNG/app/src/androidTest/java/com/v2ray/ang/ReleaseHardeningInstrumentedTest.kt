package com.v2ray.ang

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ipquality.IpQuality
import com.v2ray.ang.ipquality.IpQualityCacheEntry
import com.v2ray.ang.ipquality.IpQualitySource
import com.v2ray.ang.ipquality.IpType
import go.Seq
import libbox.Libbox
import libv2ray.Libv2ray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Baseline native/storage contracts; release smoke checks also need a matching minified test runtime. */
@RunWith(AndroidJUnit4::class)
class ReleaseHardeningInstrumentedTest {
    @Test
    fun nativeBindingsAndPersistedModelFieldsSurviveR8() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Seq.setContext(context)
        assertTrue(Libv2ray.checkVersionX().contains("26.10.31"))
        assertEquals("1.14.4", Libbox.version())
        val original = IpQualityCacheEntry(
            1000, IpQuality("203.0.113.8", IpQualitySource.ipApiIs, IpType.mobile, asn = "AS64500", isVpn = true),
        )
        assertEquals(IpQualitySource.ipApiIs, original.quality!!.source)
        assertEquals(IpType.mobile, original.quality.type)
        assertTrue(ProfileItem::class.java.declaredFields.map { it.name }.containsAll(
            listOf("server", "serverPort", "configType", "preferredCore"),
        ))
        assertTrue(IpQuality::class.java.declaredFields.map { it.name }.containsAll(
            listOf("ip", "source", "type", "organization", "asn", "isProxy", "isVpn", "isTor", "isAbuser", "inferred"),
        ))
        assertNotNull(Class.forName("com.v2ray.ang.handler.VerifiedUpdateManager\$Store"))
        assertNotNull(Class.forName("com.v2ray.ang.handler.VerifiedUpdateManager\$DownloadedUpdate"))
    }
}
