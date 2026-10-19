package com.v2ray.ang.core

import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.PreferredCore
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AnyTlsFmt
import com.v2ray.ang.ui.server.ServerUiState
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class AnyTlsConfigTest {
    private fun item() = AnyTlsFmt.parse("anytls://secret@server.example?sni=tls.example")!!

    @Test
    fun alwaysSelectsSingBoxEvenWithStaleXrayPreference() {
        PreferredCore.entries.forEach { preference ->
            assertEquals(RuntimeCore.SING_BOX, CoreSelector.select(
                NodeProfile.from(item()).copy(preferredCore = preference), PreferredCore.XRAY,
            ))
        }
    }

    @Test
    fun generatedMainAndAssignedOutboundsPreserveTlsAndSessionFields() {
        val profile = NodeProfile.from(item().copy(
            anytlsIdleSessionCheckInterval = "45s",
            anytlsIdleSessionTimeout = "2m",
            anytlsMinIdleSession = 2,
            anytlsClientMetadata = "test",
        ))
        val root = JsonUtil.parseString(SingBoxConfigGenerator.generate(
            profile, socksPort = 10808, httpPort = 10808, logLevel = "warn",
            routingRules = listOf(
                RulesetItem(domain = listOf("domain:example.net"), outboundTag = "assigned"),
                RulesetItem(process = listOf("com.example.app"), outboundTag = "assigned"),
            ),
            routingProfiles = mapOf("assigned" to profile.copy(server = "other.example")),
            enableTun = true,
        ))!!.asJsonObject
        val outbounds = root.getAsJsonArray("outbounds")
        assertEquals(2, outbounds.count { it.asJsonObject.get("type").asString == "anytls" })
        val outbound = outbounds[0].asJsonObject
        assertEquals("45s", outbound.get("idle_session_check_interval").asString)
        assertEquals("2m", outbound.get("idle_session_timeout").asString)
        assertEquals(2, outbound.get("min_idle_session").asInt)
        assertEquals("test", outbound.get("client_metadata").asString)
        val tls = outbound.getAsJsonObject("tls")
        assertTrue(tls.get("enabled").asBoolean)
        assertFalse(tls.get("insecure").asBoolean)
        assertEquals("tls.example", tls.get("server_name").asString)
        assertTrue(root.getAsJsonObject("route").getAsJsonArray("rules").any {
            it.asJsonObject.getAsJsonArray("package_name")?.get(0)?.asString == "com.example.app"
        })
    }

    @Test
    fun editorSaveAndRestorePreservesSessionSettings() {
        val profile = item().copy(
            anytlsIdleSessionCheckInterval = "1m", anytlsIdleSessionTimeout = "15s",
            anytlsMinIdleSession = 5, anytlsClientMetadata = "client",
        )
        val saved = ServerUiState.from(profile).toProfileItem(profile)
        assertEquals(profile.anytlsIdleSessionCheckInterval, saved.anytlsIdleSessionCheckInterval)
        assertEquals(profile.anytlsIdleSessionTimeout, saved.anytlsIdleSessionTimeout)
        assertEquals(profile.anytlsMinIdleSession, saved.anytlsMinIdleSession)
        assertEquals(profile.anytlsClientMetadata, saved.anytlsClientMetadata)
    }

    @Test
    fun invalidTlsCredentialsAndSessionFieldsCannotGenerate() {
        val valid = NodeProfile.from(item())
        listOf(
            valid.copy(password = ""),
            valid.copy(tlsEnabled = false),
            valid.copy(security = "reality"),
            valid.copy(anytlsMinIdleSession = -1),
            valid.copy(anytlsIdleSessionTimeout = "invalid"),
            valid.copy(pinnedCertificateSha256 = "a".repeat(64)),
        ).forEach { assertFalse(CoreSelector.canGenerateSingBox(it)) }
        assertTrue(CoreSelector.canGenerateSingBox(valid))
    }

    @Test(expected = IllegalStateException::class)
    fun unpreparedAnyTlsCannotBeSilentlySkippedByXray() {
        CoreOutboundBuilder.convert(ProfileItem.create(EConfigType.ANYTLS))
    }
}
