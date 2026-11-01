package com.v2ray.ang.handler

import com.v2ray.ang.dto.CheckUpdateResult
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val update = CheckUpdateResult(
        hasUpdate = true, latestVersion = "3.0.0",
        downloadUrl = "https://example.org/Freedom_3.0.0_arm64-v8a.apk",
        downloadDigest = "sha256:" + "ab".repeat(32),
    )

    @Test
    fun cachedUpdateMustMatchVersionAssetAndDigest() {
        assertTrue(VerifiedUpdateManager.sameDownload(update, update.copy(isDownloaded = true)))
        assertTrue(VerifiedUpdateManager.sameDownload(update, update.copy(downloadDigest = update.downloadDigest!!.uppercase())))
        assertFalse(VerifiedUpdateManager.sameDownload(update, update.copy(latestVersion = "3.0.1")))
        assertFalse(VerifiedUpdateManager.sameDownload(update, update.copy(downloadUrl = "https://example.org/other.apk")))
        assertFalse(VerifiedUpdateManager.sameDownload(update, update.copy(downloadDigest = null)))
        assertFalse(VerifiedUpdateManager.sameDownload(update, update.copy(downloadDigest = "sha256:" + "cd".repeat(32))))
    }

    @Test
    fun namesAreStableSafeAndDifferentForReplacedAssets() {
        val first = VerifiedUpdateManager.fileName(update)
        assertTrue(first.matches(Regex("[A-Za-z0-9._-]+\\.apk")))
        assertEquals(first, VerifiedUpdateManager.fileName(update.copy(isDownloaded = true)))
        assertNotEquals(first, VerifiedUpdateManager.fileName(update.copy(downloadDigest = null)))
        val hostile = update.copy(downloadUrl = "https://example.org/../../a%2Fb%5Cc.apk?name=ignored")
        assertFalse(VerifiedUpdateManager.fileName(hostile).contains('/'))
        assertFalse(VerifiedUpdateManager.fileName(hostile).contains('\\'))
    }

    @Test
    fun packagesAreOnlyRemovedOnceTheirVersionIsInstalledOrSuperseded() {
        assertFalse(VerifiedUpdateManager.isInstalled(100, 101))
        assertTrue(VerifiedUpdateManager.isInstalled(101, 101))
        assertTrue(VerifiedUpdateManager.isInstalled(102, 101))
        assertFalse(VerifiedUpdateManager.isInstalled(100, 0))
    }

    @Test
    fun unknownLengthIsIndeterminateAndKnownProgressIsBounded() {
        assertNull(UpdateDownloadProgress(update).percent)
        assertNull(UpdateDownloadProgress(update, 1024, 0).percent)
        assertEquals(25, UpdateDownloadProgress(update, 25, 100).percent)
        assertEquals(100, UpdateDownloadProgress(update, Long.MAX_VALUE, Long.MAX_VALUE).percent)
        assertEquals(100, UpdateDownloadProgress(update, 150, 100).percent)
    }
}
