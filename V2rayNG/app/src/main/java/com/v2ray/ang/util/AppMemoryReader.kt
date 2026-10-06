package com.v2ray.ang.util

import android.app.ActivityManager
import android.content.Context
import android.os.Process

internal object AppMemoryReader {
    fun readPssKb(context: Context): Long? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return readPssKb(manager, context.packageName, Process.myUid(), Process.myPid())
    }

    internal fun readPssKb(
        manager: ActivityManager,
        packageName: String,
        uid: Int,
        currentPid: Int,
    ): Long? = runCatching {
        val processes = manager.runningAppProcesses ?: return null
        val pids = processes.filter {
            it.uid == uid &&
                (it.processName == packageName || it.processName?.startsWith("$packageName:") == true)
        }.map { it.pid }.plus(currentPid).distinct().toIntArray()
        // PSS includes native allocations and apportions shared pages across app processes.
        val samples = manager.getProcessMemoryInfo(pids)
        if (samples.size != pids.size) return null
        samples.sumOf { it.totalPss.toLong() }.takeIf { it > 0L }
    }.getOrNull()
}
