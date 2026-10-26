package com.v2ray.ang

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.SecureStorage
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.StorageRecovery
import com.v2ray.ang.handler.StorageUnavailableException
import com.v2ray.ang.receiver.BootReceiver
import com.v2ray.ang.util.LogUtil
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class StorageRecoveryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun missingKeyIsContainedRawExportPreservesBytesAndRetryWorks() = fixture { scoped, base ->
        SecureStorage.initialize(scoped)
        assertFalse(SecureStorage.isUnavailable)
        val store = SecureStorage.open("MAIN")
        store.encode("recovery-control", "retained-value")
        store.sync()
        store.close()
        val encrypted = File(base, "stores-v1/MAIN").readBytes()
        val wrapped = File(base, "key").readBytes()
        assertTrue(File(base, "key").renameTo(File(base, "key.repro-held")))
        coldState()
        SecureStorage.initialize(scoped)
        assertTrue(SecureStorage.isUnavailable)
        assertThrows(StorageUnavailableException::class.java) { SecureStorage.open("MAIN") }
        LogUtil.refreshLogLevel()
        LogUtil.e(AppConfig.TAG, "Storage recovery logging control")
        assertNotNull(SettingsManager.getLocale())
        assertFalse(LauncherManager.startServiceFromToggle(scoped))
        assertFalse(LauncherManager.startService(scoped, "unavailable-fixture"))
        BootReceiver().onReceive(scoped, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertArrayEquals(encrypted, File(base, "stores-v1/MAIN").readBytes())
        val exported = export(scoped)
        assertArrayEquals(encrypted, exported["secure-storage/stores-v1/MAIN"])
        assertArrayEquals(wrapped, exported["secure-storage/key.repro-held"])
        assertTrue(File(base, "key.repro-held").renameTo(File(base, "key")))
        SecureStorage.initialize(scoped)
        assertFalse(SecureStorage.isUnavailable)
        assertEquals("retained-value", SecureStorage.open("MAIN").decodeString("recovery-control"))
    }

    @Test
    fun existingFailedProcessReopensStorageAfterFilesAreRepaired() = fixture { scoped, base ->
        SecureStorage.initialize(scoped)
        val store = SecureStorage.open("MAIN")
        store.encode("recovered-process", "retained")
        store.sync()
        store.close()
        val key = File(base, "key")
        val held = File(base, "key.held")
        assertTrue(key.renameTo(held))
        coldState()
        SecureStorage.initialize(scoped)
        assertFalse(SecureStorage.ensureAvailable(scoped))
        assertTrue(held.renameTo(key))
        assertTrue(SecureStorage.ensureAvailable(scoped))
        assertEquals("retained", SecureStorage.open("MAIN").decodeString("recovered-process"))
        val marker = File(SecureStorage.controlDirectory(base), "reset")
        marker.writeText("reset-in-progress")
        assertFalse(SecureStorage.ensureAvailable(scoped))
        assertTrue(marker.delete())
    }

    @Test
    fun corruptedLegacyStoreIsPreservedAndExportable() = fixture { scoped, base ->
        val legacy = File(scoped.filesDir, "mmkv").apply { mkdirs() }
        val original = "damaged-legacy-fixture".toByteArray()
        File(legacy, "MAIN").writeBytes(original)
        SecureStorage.initialize(scoped)
        assertTrue(SecureStorage.isUnavailable)
        assertFalse(File(base, "stores-v1").exists())
        assertArrayEquals(original, File(legacy, "MAIN").readBytes())
        assertArrayEquals(original, export(scoped)["mmkv/MAIN"])
    }

    @Test
    fun missingActivationMarkerDoesNotDiscardEncryptedStores() = fixture { scoped, base ->
        SecureStorage.initialize(scoped)
        val original = File(base, "stores-v1/MAIN").readBytes()
        assertTrue(File(base, "stores-v1/activated").delete())
        coldState()
        SecureStorage.initialize(scoped)
        assertTrue(SecureStorage.isUnavailable)
        assertArrayEquals(original, File(base, "stores-v1/MAIN").readBytes())
        File(base, "stores-v1/activated").writeBytes(byteArrayOf(1))
        SecureStorage.initialize(scoped)
        assertFalse(SecureStorage.isUnavailable)
    }

    @Test
    fun failedRestoreRollbackLeavesJournalAndDataForRecovery() = fixture { scoped, base ->
        SecureStorage.initialize(scoped)
        val original = File(base, "stores-v1/MAIN").readBytes()
        val journal = File(base, "restore").apply { mkdirs() }
        File(journal, "prepared").writeBytes(byteArrayOf(1))
        coldState()
        SecureStorage.initialize(scoped)
        assertTrue(SecureStorage.isUnavailable)
        assertTrue(File(journal, "prepared").isFile)
        assertArrayEquals(original, File(base, "stores-v1/MAIN").readBytes())
        assertTrue(export(scoped).containsKey("secure-storage/restore/prepared"))
    }

    @Test
    fun invalidWrappedKeyDoesNotRegenerateOverExistingData() = fixture { scoped, base ->
        SecureStorage.initialize(scoped)
        val original = File(base, "stores-v1/MAIN").readBytes()
        val key = File(base, "key")
        val wrapped = key.readBytes()
        val damaged = wrapped.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        key.writeBytes(damaged)
        coldState()
        SecureStorage.initialize(scoped)
        assertTrue(SecureStorage.isUnavailable)
        assertArrayEquals(damaged, key.readBytes())
        assertArrayEquals(original, File(base, "stores-v1/MAIN").readBytes())
        key.writeBytes(wrapped)
        SecureStorage.initialize(scoped)
        assertFalse(SecureStorage.isUnavailable)
    }

    @Test
    fun lostKeystoreAliasKeepsCiphertextAndCanResetIntoNewGeneration() = fixture { scoped, base ->
        SecureStorage.initialize(scoped)
        val original = File(base, "stores-v1/MAIN").readBytes()
        val alias = File(base, "key-alias").readText()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        coldState()
        SecureStorage.initialize(scoped)
        assertTrue(SecureStorage.isUnavailable)
        assertArrayEquals(original, File(base, "stores-v1/MAIN").readBytes())
        field("nativeInitialized").setBoolean(SecureStorage, false)
        val archive = StorageRecovery.archiveAndReset(scoped)
        field("restartRequired").setBoolean(SecureStorage, false)
        SecureStorage.initialize(scoped)
        assertFalse(SecureStorage.isUnavailable)
        assertNotEquals(alias, File(base, "key-alias").readText())
        assertArrayEquals(original, File(archive, "secure-storage/stores-v1/MAIN").readBytes())
    }

    @Test
    fun resetArchivesOldGenerationAndPreservesItsKeystoreAlias() = fixture { scoped, base ->
        SecureStorage.initialize(scoped)
        val original = File(base, "stores-v1/MAIN").readBytes()
        val oldAlias = File(base, "key-alias").readText()
        val oldKey = File(base, "key").readBytes()
        File(base, "key").delete()
        coldState()
        SecureStorage.initialize(scoped)
        assertTrue(SecureStorage.isUnavailable)
        // Fixture stores have no live handles; do not close the instrumentation app's own handles.
        field("nativeInitialized").setBoolean(SecureStorage, false)
        val archive = StorageRecovery.archiveAndReset(scoped)
        assertArrayEquals(original, File(archive, "secure-storage/stores-v1/MAIN").readBytes())
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(oldAlias))
        val newAlias = File(base, "key-alias").readText()
        assertNotEquals(oldAlias, newAlias)
        assertTrue(SecureStorage.restartRequired)
        SecureStorage.initialize(scoped)
        assertTrue(SecureStorage.isUnavailable)
        field("restartRequired").setBoolean(SecureStorage, false)
        SecureStorage.initialize(scoped)
        assertFalse(SecureStorage.isUnavailable)
        assertFalse(oldKey.contentEquals(File(base, "key").readBytes()))
        assertArrayEquals(original, File(archive, "secure-storage/stores-v1/MAIN").readBytes())
    }

    @Test
    fun interruptedResetResumesWithoutOverwritingArchivedFiles() = fixture { scoped, base ->
        SecureStorage.initialize(scoped)
        val original = File(base, "key").readBytes()
        val transaction = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val archive = File(scoped.noBackupFilesDir, "storage-recovery-archives/$transaction")
            .apply { mkdirs() }
        assertTrue(base.renameTo(File(archive, "secure-storage")))
        val control = SecureStorage.controlDirectory(base)
        File(control, "reset").writeText(transaction)
        coldState()
        SecureStorage.initialize(scoped)
        assertTrue(SecureStorage.isUnavailable)
        field("nativeInitialized").setBoolean(SecureStorage, false)
        assertEquals(archive, StorageRecovery.archiveAndReset(scoped))
        assertArrayEquals(original, File(archive, "secure-storage/key").readBytes())
        assertFalse(File(control, "reset").exists())
        assertTrue(File(base, "key-alias").isFile)
    }

    private fun export(scoped: Context): Map<String, ByteArray> {
        val bytes = ByteArrayOutputStream()
        StorageRecovery.exportRaw(scoped, bytes)
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return files
    }

    private fun field(name: String) = SecureStorage::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun coldState() {
        field("state").set(SecureStorage, null)
    }

    private fun fixture(test: (Context, File) -> Unit) {
        val root = File(context.cacheDir, "storage-recovery-test-${UUID.randomUUID()}")
            .apply { mkdirs() }
        val scoped = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        val base = File(scoped.noBackupFilesDir, "secure-storage").apply { mkdirs() }
        File(base, "key-alias").writeText("freedom.storage.v1.${UUID.randomUUID()}")
        val fields = listOf("state", "initializationFailure", "restartRequired", "nativeInitialized")
        val saved = fields.associateWith { field(it).get(SecureStorage) }
        coldState()
        field("initializationFailure").set(SecureStorage, null)
        field("restartRequired").setBoolean(SecureStorage, false)
        try {
            test(scoped, base)
        } finally {
            if (!SecureStorage.restartRequired) {
                SecureStorage.storeIds.forEach { runCatching { SecureStorage.open(it).close() } }
            }
            val aliases = root.walkTopDown().filter { it.name == "key-alias" && it.isFile }
                .map { it.readText() }.toSet()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            aliases.forEach { runCatching { keys.deleteEntry(it) } }
            MMKV.initialize(context)
            saved.forEach { (name, value) -> field(name).set(SecureStorage, value) }
            root.deleteRecursively()
        }
    }
}
