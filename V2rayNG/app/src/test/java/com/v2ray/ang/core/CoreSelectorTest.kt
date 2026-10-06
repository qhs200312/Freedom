package com.v2ray.ang.core

import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.PreferredCore
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Test

class CoreSelectorTest {
    private fun hy2(core: PreferredCore = PreferredCore.AUTO, finalMask: String? = null) = NodeProfile(
        remarks = "hy2",
        protocol = EConfigType.HYSTERIA2,
        server = "example.com",
        port = 443,
        password = "secret",
        tlsEnabled = true,
        insecure = false,
        preferredCore = core,
        xrayFinalMask = finalMask,
    )

    @Test
    fun hysteria2AutoUsesSingBox() {
        assertEquals(RuntimeCore.SING_BOX, CoreSelector.select(hy2()))
    }

    @Test
    fun missingHysteriaDefaultUsesSingBoxPolicy() {
        assertEquals(RuntimeCore.SING_BOX, CoreSelector.select(hy2(), PreferredCore.SING_BOX))
    }

    @Test
    fun globalHysteria2DefaultCanUseXray() {
        assertEquals(RuntimeCore.XRAY, CoreSelector.select(hy2(), PreferredCore.XRAY))
    }

    @Test
    fun forcedGlobalSingBoxFallsBackForXrayOnlySettings() {
        assertEquals(
            RuntimeCore.XRAY,
            CoreSelector.select(
                hy2(finalMask = "{\"tcp\":[{\"type\":\"fragment\"}]}"),
                PreferredCore.SING_BOX,
            ),
        )
    }

    @Test
    fun explicitNodeChoiceOverridesGlobalDefault() {
        assertEquals(
            RuntimeCore.SING_BOX,
            CoreSelector.select(hy2(PreferredCore.SING_BOX), PreferredCore.XRAY),
        )
    }

    @Test
    fun nonHysteriaProfilesUseXrayByDefault() {
        val profile = hy2().copy(protocol = EConfigType.VLESS)
        assertEquals(RuntimeCore.XRAY, CoreSelector.select(profile))
    }

    @Test
    fun xraySpecificFinalMaskFallsBackToXrayInAutoMode() {
        val profile = hy2(finalMask = "{\"tcp\":[{\"type\":\"fragment\"}]}")
        assertEquals(RuntimeCore.XRAY, CoreSelector.select(profile, PreferredCore.AUTO))
    }

    @Test
    fun explicitSingBoxFallsBackForXraySpecificFinalMask() {
        assertEquals(
            RuntimeCore.XRAY,
            CoreSelector.select(
                hy2(PreferredCore.SING_BOX, "{\"tcp\":[{\"type\":\"fragment\"}]}")
            ),
        )
    }

    @Test
    fun explicitSingBoxFallsBackForXhttp() {
        val profile = hy2(PreferredCore.SING_BOX).copy(
            protocol = EConfigType.VLESS,
            password = "11111111-1111-1111-1111-111111111111",
            network = "xhttp",
        )

        assertEquals(RuntimeCore.XRAY, CoreSelector.select(profile))
    }
}
