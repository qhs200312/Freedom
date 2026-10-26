package com.v2ray.ang.handler

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.v2ray.ang.R
import java.io.File
import java.util.concurrent.TimeUnit

object SensitiveCache {
    const val MAX_AGE_MILLIS = 15L * 60 * 1000
    val directoryNames = setOf("shared-backups", "verified-updates", "backup-work")

    fun scheduleCleanup(context: Context) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<SensitiveCacheCleanupWorker>()
                .setInitialDelay(MAX_AGE_MILLIS, TimeUnit.MILLISECONDS)
                .build()
        )
    }

    fun cleanup(context: Context, now: Long = System.currentTimeMillis()) {
        directoryNames.forEach { name ->
            File(context.cacheDir, name).listFiles().orEmpty().forEach { file ->
                if (now - file.lastModified() >= MAX_AGE_MILLIS || file.lastModified() > now) {
                    val managed = File(context.cacheDir, name).canonicalFile
                    val target = file.canonicalFile
                    if (target.path.startsWith(managed.path + File.separator)) {
                        if (target.isDirectory) target.deleteRecursively() else target.delete()
                    }
                }
            }
        }
        val root = context.cacheDir.canonicalFile
        val appName = Regex.escape(context.getString(R.string.app_name))
        val legacyNames = Regex("^(?:${appName}_\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}(?:_[0-9a-f-]{36})?|\\d{13})(?:\\.zip)?$")
        root.listFiles().orEmpty().forEach { file ->
            if (legacyNames.matches(file.name) && now - file.lastModified() >= MAX_AGE_MILLIS) {
                val target = file.canonicalFile
                if (target.parentFile == root) {
                    if (target.isDirectory) {
                        val allowed = SecureStorage.storeIds.flatMap { listOf(it, "$it.crc") }.toSet()
                        if (target.listFiles().orEmpty().all { it.name in allowed }) target.deleteRecursively()
                    } else if (target.extension == "zip") {
                        target.delete()
                    }
                }
            }
        }
    }
}

class SensitiveCacheCleanupWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        SensitiveCache.cleanup(applicationContext)
        return Result.success()
    }
}
