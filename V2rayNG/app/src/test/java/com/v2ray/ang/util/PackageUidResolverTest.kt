package com.v2ray.ang.util

import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PackageUidResolverTest {
    private var previousLogPriority = 0
    private val logPriority = LogUtil::class.java.getDeclaredField("cachedMinPriority").apply { isAccessible = true }

    @Before
    fun disableAndroidLogging() {
        previousLogPriority = logPriority.getInt(null)
        logPriority.setInt(null, Int.MAX_VALUE)
    }

    @After
    fun restoreAndroidLogging() {
        logPriority.setInt(null, previousLogPriority)
    }

    @Test
    fun reinstallResolvesTheNewUidInsteadOfUsingTheCachedOne() {
        val context = mock<Context>()
        val manager = mock<PackageManager>()
        whenever(context.packageManager).thenReturn(manager)
        whenever(manager.getPackageUid("com.example.reinstall", 0)).thenReturn(10001, 10002)
        assertEquals(listOf("10001"), PackageUidResolver.packageNamesToUids(context, listOf("com.example.reinstall")))
        assertEquals(listOf("10002"), PackageUidResolver.packageNamesToUids(context, listOf("com.example.reinstall")))
    }

    @Test
    fun uninstallRemovesThePreviousUidFromTheCache() {
        val context = mock<Context>()
        val manager = mock<PackageManager>()
        whenever(context.packageManager).thenReturn(manager)
        whenever(manager.getPackageUid("com.example.removed", 0))
            .thenReturn(10003).thenThrow(PackageManager.NameNotFoundException())
        assertEquals(listOf("10003"), PackageUidResolver.packageNamesToUids(context, listOf("com.example.removed")))
        assertTrue(PackageUidResolver.packageNamesToUids(context, listOf("com.example.removed")).isEmpty())
        assertFalse(PackageUidResolver.packageUidMap.containsKey("com.example.removed"))
    }
}
