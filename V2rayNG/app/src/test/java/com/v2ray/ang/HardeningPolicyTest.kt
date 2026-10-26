package com.v2ray.ang

import com.v2ray.ang.handler.VerifiedUpdateManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class HardeningPolicyTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun updatesRequireExactPackageNewerVersionAndAllCurrentSigners() {
        fun accept(
            name: String? = "com.v2ray.ang",
            version: Long = 101,
            installed: Set<String> = setOf("current"),
            candidate: Set<String> = setOf("current"),
        ) = VerifiedUpdateManager.acceptsIdentity("com.v2ray.ang", name, 100, version, installed, candidate)
        assertTrue(accept())
        assertFalse(accept(name = "com.attacker.app"))
        assertFalse(accept(name = null))
        assertFalse(accept(version = 100))
        assertFalse(accept(version = 99))
        assertFalse(accept(candidate = setOf("old")))
        assertFalse(accept(candidate = setOf("current", "other")))
        assertFalse(accept(installed = emptySet(), candidate = emptySet()))
        assertTrue(accept(installed = setOf("one", "two"), candidate = setOf("two", "one")))
    }

    @Test
    fun releaseDigestMustBeSha256AndMatchAllDownloadedBytes() {
        val apk = files.newFile("update.apk").apply { writeText("signed-apk-fixture") }
        val hash = MessageDigest.getInstance("SHA-256").digest(apk.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertTrue(VerifiedUpdateManager.matchesDigest(apk, "sha256:$hash"))
        assertTrue(VerifiedUpdateManager.matchesDigest(apk, "sha256:${hash.uppercase()}"))
        assertFalse(VerifiedUpdateManager.matchesDigest(apk, "md5:$hash"))
        assertFalse(VerifiedUpdateManager.matchesDigest(apk, "sha256:0"))
        apk.appendText("tampered")
        assertFalse(VerifiedUpdateManager.matchesDigest(apk, "sha256:$hash"))
    }
}
