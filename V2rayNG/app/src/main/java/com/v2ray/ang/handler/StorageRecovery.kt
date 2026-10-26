package com.v2ray.ang.handler

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.AtomicFile
import com.v2ray.ang.service.CoreWarmupService
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object StorageRecovery {
    fun exportRaw(context: Context, output: OutputStream) {
        val base = File(context.noBackupFilesDir, "secure-storage")
        SecureStorage.withLock(base) {
            ZipOutputStream(output).use { zip ->
                exportTree(zip, base, "secure-storage")
                exportTree(zip, File(context.filesDir, "mmkv"), "mmkv")
                exportTree(zip, File(context.noBackupFilesDir, "storage-recovery-archives"), "archives")
                val marker = File(SecureStorage.controlDirectory(base), "reset")
                if (marker.isFile) {
                    zip.putNextEntry(ZipEntry("recovery/reset"))
                    marker.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun archiveAndReset(context: Context): File {
        check(SecureStorage.isUnavailable) { "Healthy storage must not be reset" }
        val base = File(context.noBackupFilesDir, "secure-storage")
        return SecureStorage.withLock(base) {
            val control = SecureStorage.controlDirectory(base)
            val marker = File(control, "reset")
            val transaction = if (marker.isFile) {
                marker.readText(Charsets.US_ASCII)
            } else {
                "${System.currentTimeMillis()}-${UUID.randomUUID()}".also {
                    writeAtomically(marker, it)
                    syncDirectory(control)
                }
            }
            require(transaction.matches(Regex("[0-9]{13}-[0-9a-f-]{36}")))

            // New processes cannot initialize while the durable marker and stable lock are held.
            stopOtherProcesses(context, transaction)
            SecureStorage.closeForReset()
            val archive = File(context.noBackupFilesDir, "storage-recovery-archives/$transaction")
            check(archive.isDirectory || archive.mkdirs()) { "Cannot create recovery archive" }
            val archivedBase = File(archive, "secure-storage")
            val alias = "freedom.storage.v1.${transaction.substringAfter('-')}"
            if (!archivedBase.exists() && base.exists()) {
                check(base.renameTo(archivedBase)) { "Cannot preserve encrypted storage" }
            } else if (base.exists()) {
                val allowed = setOf("key-alias", "key-alias.new", "key-alias.bak")
                check(base.listFiles()?.all { it.name in allowed } == true) {
                    "Both original and archived storage exist"
                }
                val aliasFile = File(base, "key-alias")
                check(!aliasFile.exists() || aliasFile.readText(Charsets.US_ASCII) == alias)
            }
            val legacy = File(context.filesDir, "mmkv")
            if (legacy.exists()) {
                check(!File(archive, "mmkv").exists()) { "Legacy recovery archive already exists" }
                check(legacy.renameTo(File(archive, "mmkv"))) { "Cannot preserve legacy storage" }
            }
            check(base.isDirectory || base.mkdirs())
            // A new alias also permits recovery from an invalid old Keystore entry without deleting it.
            writeAtomically(File(base, "key-alias"), alias)
            syncDirectory(base)
            syncDirectory(archive)
            syncDirectory(checkNotNull(archive.parentFile))
            syncDirectory(context.filesDir)
            syncDirectory(context.noBackupFilesDir)
            check(marker.delete()) { "Cannot finish storage reset" }
            syncDirectory(control)
            archive
        }
    }

    private fun stopOtherProcesses(context: Context, transaction: String) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val daemon = checkNotNull(manager.runningAppProcesses).firstOrNull {
            it.uid == Process.myUid() &&
                it.processName == "${context.packageName}:RunSoLibV2RayDaemon"
        }
        if (daemon != null) {
            val stopped = CountDownLatch(1)
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultData?.getString(CoreWarmupService.EXTRA_TRANSACTION) == transaction &&
                        resultData.getInt("pid") == daemon.pid
                    ) stopped.countDown()
                }
            }
            context.startService(Intent(context, CoreWarmupService::class.java)
                .setAction(CoreWarmupService.ACTION_RECOVERY_STOP)
                .putExtra(CoreWarmupService.EXTRA_TRANSACTION, transaction)
                .putExtra(CoreWarmupService.EXTRA_RECEIVER, receiver))
            check(stopped.await(40, TimeUnit.SECONDS)) { "Proxy shutdown has not completed" }
        }
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (true) {
            val processes = checkNotNull(manager.runningAppProcesses) { "Cannot inspect app processes" }
                .filter {
                    it.uid == Process.myUid() && it.pid != Process.myPid() &&
                        (it.processName == context.packageName ||
                            it.processName.startsWith("${context.packageName}:"))
                }
            if (processes.isEmpty()) return
            check(SystemClock.elapsedRealtime() < deadline) { "Background processes are still active" }
            processes.forEach { Process.killProcess(it.pid) }
            Thread.sleep(50)
        }
    }

    private fun exportTree(zip: ZipOutputStream, root: File, prefix: String) {
        if (!root.exists()) return
        val canonicalRoot = root.canonicalFile
        root.walkTopDown().onFail { _, error -> throw error }.forEach { file ->
            require(file.canonicalFile == canonicalRoot ||
                file.canonicalPath.startsWith(canonicalRoot.path + File.separator))
            if (file.isFile) {
                zip.putNextEntry(ZipEntry("$prefix/${file.relativeTo(root).invariantSeparatorsPath}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun writeAtomically(file: File, value: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(value.toByteArray(Charsets.US_ASCII))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try {
            android.system.Os.fsync(descriptor)
        } finally {
            android.system.Os.close(descriptor)
        }
    }
}
