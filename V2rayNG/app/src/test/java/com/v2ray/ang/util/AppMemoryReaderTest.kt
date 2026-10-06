package com.v2ray.ang.util

import android.app.ActivityManager
import android.os.Debug
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AppMemoryReaderTest {
    private val packageName = "com.v2ray.ang.fdroid"
    private val uid = 10_001

    @Test
    fun sumsUiAndNativeCoreProcessesWithoutCountingOtherAppsOrDuplicatePids() {
        val manager = mock<ActivityManager>()
        whenever(manager.runningAppProcesses).thenReturn(listOf(
            process(packageName, 101),
            process("$packageName:RunSoLibV2RayDaemon", 102),
            process("$packageName:bg", 103),
            process("$packageName:RunSoLibV2RayDaemon", 102),
            process("com.other.app", 104),
            process("$packageName.other", 105),
            process("$packageName:foreign", 106, uid + 1),
        ))
        val samples = arrayOf(
            memoryInfo(32_768), memoryInfo(65_536), memoryInfo(8_192),
        )
        whenever(manager.getProcessMemoryInfo(any())).thenReturn(samples)

        assertEquals(106_496L, AppMemoryReader.readPssKb(manager, packageName, uid, 101))
        val pids = argumentCaptor<IntArray>()
        verify(manager).getProcessMemoryInfo(pids.capture())
        assertEquals(listOf(101, 102, 103), pids.firstValue.toList())
    }

    @Test
    fun includesCurrentProcessWhenMissingFromProcessList() {
        val manager = mock<ActivityManager>()
        whenever(manager.runningAppProcesses).thenReturn(listOf(
            process("$packageName:RunSoLibV2RayDaemon", 102),
        ))
        val samples = arrayOf(
            memoryInfo(65_536), memoryInfo(32_768),
        )
        whenever(manager.getProcessMemoryInfo(any())).thenReturn(samples)

        assertEquals(98_304L, AppMemoryReader.readPssKb(manager, packageName, uid, 101))
    }

    @Test
    fun unavailableProcessesDoNotFallBackToJavaHeap() {
        val manager = mock<ActivityManager>()
        whenever(manager.runningAppProcesses).thenReturn(null)
        assertNull(AppMemoryReader.readPssKb(manager, packageName, uid, 101))
    }

    @Test
    fun failedOrIncompleteSamplesAreUnavailable() {
        val manager = mock<ActivityManager>()
        whenever(manager.runningAppProcesses).thenReturn(listOf(process(packageName, 101)))
        whenever(manager.getProcessMemoryInfo(any())).thenReturn(emptyArray())
        assertNull(AppMemoryReader.readPssKb(manager, packageName, uid, 101))
        val zeroSample = arrayOf(memoryInfo(0))
        whenever(manager.getProcessMemoryInfo(any())).thenReturn(zeroSample)
        assertNull(AppMemoryReader.readPssKb(manager, packageName, uid, 101))
        whenever(manager.getProcessMemoryInfo(any())).thenThrow(SecurityException("unavailable"))
        assertNull(AppMemoryReader.readPssKb(manager, packageName, uid, 101))
    }

    private fun process(name: String, id: Int, processUid: Int = uid) =
        mock<ActivityManager.RunningAppProcessInfo>().apply {
            processName = name
            pid = id
            uid = processUid
        }

    private fun memoryInfo(pssKb: Int): Debug.MemoryInfo =
        mock<Debug.MemoryInfo>().also { whenever(it.totalPss).thenReturn(pssKb) }
}
