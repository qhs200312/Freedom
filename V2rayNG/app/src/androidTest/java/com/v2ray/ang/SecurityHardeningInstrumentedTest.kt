package com.v2ray.ang

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import com.v2ray.ang.handler.SecureStorage
import com.v2ray.ang.handler.SensitiveCache
import com.v2ray.ang.handler.TaskerAuthorization
import com.v2ray.ang.handler.VerifiedUpdateManager
import com.v2ray.ang.receiver.TaskerReceiver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.lang.reflect.InvocationTargetException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecurityHardeningInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun taskerRejectsMissingAndForgedCapabilitiesAndReusesSavedAuthorization() {
        val root = File(context.cacheDir, "authorization-${UUID.randomUUID()}").apply { mkdirs() }
        var serviceStops = 0
        val scoped = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = root
            override fun getApplicationContext(): Context = this
            override fun stopService(service: Intent): Boolean { serviceStops++; return true }
            override fun sendBroadcast(intent: Intent) = Unit
        }
        try {
            assertFalse(TaskerAuthorization.isAuthorized(scoped, null))
            assertFalse(TaskerAuthorization.isAuthorized(scoped, "0".repeat(64)))
            val first = TaskerAuthorization.issue(scoped, null)
            assertTrue(TaskerAuthorization.isAuthorized(scoped, first))
            assertEquals(first, TaskerAuthorization.issue(scoped, first))
            val second = TaskerAuthorization.issue(scoped, null)
            assertNotEquals(first, second)
            assertTrue(TaskerAuthorization.isAuthorized(scoped, second))
            val registry = File(root, "tasker-authorization/tokens").readText()
            assertFalse(registry.contains(first))
            assertFalse(registry.contains(second))
            fun fire(token: String?) {
                val bundle = Bundle().apply {
                    putString(AppConfig.TASKER_EXTRA_BUNDLE_GUID, AppConfig.TASKER_DEFAULT_GUID)
                    putBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, false)
                    if (token != null) putString(TaskerAuthorization.EXTRA_TOKEN, token)
                }
                TaskerReceiver().onReceive(scoped, Intent("com.twofortyfouram.locale.intent.action.FIRE_SETTING")
                    .putExtra(AppConfig.TASKER_EXTRA_BUNDLE, bundle))
            }
            fire(null)
            fire("0".repeat(64))
            assertEquals(0, serviceStops)
            fire(first)
            assertEquals(3, serviceStops)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nativeMigrationPreservesTypesAndLeavesSourceUntilActivation() {
        val base = File(context.cacheDir, "migration-${UUID.randomUUID()}").apply { mkdirs() }
        val legacy = File(base, "legacy").apply { mkdirs() }
        val secureBase = File(base, "secure").apply { mkdirs() }
        val destination = File(secureBase, "stores-v1")
        val key = "test-key-12345678"
        val marker = "private-node-${UUID.randomUUID()}"
        try {
            val original = MMKV.backedUpMMKVWithID("MAIN", MMKV.MULTI_PROCESS_MODE, null, legacy.path)
            requireNotNull(original)
            repeat(6) { assertTrue(original.encode("node", "$marker-${"x".repeat(256)}-$it")) }
            assertTrue(original.encode("node", marker))
            assertTrue(original.encode("enabled", true))
            assertTrue(original.encode("port", 10808))
            assertTrue(original.encode("traffic", 8_000_000_000L))
            assertTrue(original.encode("values", setOf("one", "two")))
            original.sync()
            original.close()
            File(secureBase, "migration").mkdirs()
            File(secureBase, "migration/interrupted").writeText("partial")
            val migrate = SecureStorage::class.java.getDeclaredMethod(
                "migrate", File::class.java, File::class.java, File::class.java, String::class.java,
            ).apply { isAccessible = true }
            migrate.invoke(SecureStorage, legacy, secureBase, destination, key)
            assertTrue(File(destination, "activated").isFile)
            assertTrue(File(legacy, "MAIN").isFile)
            assertFalse(File(destination, "MAIN").readBytes().toString(Charsets.ISO_8859_1).contains(marker))
            val encrypted = MMKV.backedUpMMKVWithID("MAIN", MMKV.MULTI_PROCESS_MODE, key, destination.path)
            requireNotNull(encrypted)
            try {
                assertEquals(marker, encrypted.decodeString("node"))
                assertTrue(encrypted.decodeBool("enabled"))
                assertEquals(10808, encrypted.decodeInt("port"))
                assertEquals(8_000_000_000L, encrypted.decodeLong("traffic"))
                assertEquals(setOf("one", "two"), encrypted.decodeStringSet("values"))
            } finally {
                encrypted.close()
            }
            val failedBase = File(base, "failed").apply { mkdirs() }
            val blocked = File(failedBase, "stores-v1").apply { writeText("cannot replace") }
            try {
                migrate.invoke(SecureStorage, legacy, failedBase, blocked, key)
                fail("Activation must fail without modifying the source")
            } catch (_: InvocationTargetException) {
                assertTrue(File(legacy, "MAIN").isFile)
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun encryptedStorageExportsPortableBackupAndRestoresIntoDifferentDeviceKey() {
        val base = File(context.cacheDir, "portable-${UUID.randomUUID()}").apply { mkdirs() }
        val backup = File(base, "backup")
        val marker = "portable-private-${UUID.randomUUID()}"
        val stateField = SecureStorage::class.java.getDeclaredField("state").apply { isAccessible = true }
        val originalState = stateField.get(SecureStorage)
        val fixtureKey = "fixture-${UUID.randomUUID()}"
        val main = SecureStorage.open("MAIN")
        try {
            assertTrue(main.encode(fixtureKey, marker))
            assertTrue(SecureStorage.backupToDirectory(backup))
            assertEquals(SecureStorage.storeIds.flatMap { listOf(it, "$it.crc") }.toSet(),
                backup.listFiles().orEmpty().map { it.name }.toSet())
            val portable = MMKV.backedUpMMKVWithID("MAIN", MMKV.MULTI_PROCESS_MODE, null, backup.path)
            requireNotNull(portable)
            assertEquals(marker, portable.decodeString(fixtureKey))
            repeat(6) { portable.encode(fixtureKey, "$marker-${"y".repeat(256)}-$it") }
            portable.encode(fixtureKey, marker)
            portable.sync()
            portable.close()
            val device = File(base, "other-device").apply { mkdirs() }
            val root = File(device, "stores-v1")
            val migrate = SecureStorage::class.java.getDeclaredMethod(
                "migrate", File::class.java, File::class.java, File::class.java, String::class.java,
            ).apply { isAccessible = true }
            val legacy = File(device, "empty-legacy").apply { mkdirs() }
            val otherKey = "other-key-123456"
            migrate.invoke(SecureStorage, legacy, device, root, otherKey)
            val constructor = originalState.javaClass.declaredConstructors.single().apply { isAccessible = true }
            stateField.set(SecureStorage, constructor.newInstance(device, root, otherKey))
            assertTrue(SecureStorage.restoreFromDirectory(backup))
            val restored = SecureStorage.open("MAIN")
            assertEquals(marker, restored.decodeString(fixtureKey))
            assertFalse(File(root, "MAIN").readBytes().toString(Charsets.ISO_8859_1).contains(marker))
            SecureStorage.storeIds.forEach { SecureStorage.open(it).close() }
        } finally {
            stateField.set(SecureStorage, originalState)
            main.removeValueForKey(fixtureKey)
            base.deleteRecursively()
        }
    }

    @Test
    fun expiredSharesCannotBeOpenedAndCleanupKeepsRecentFiles() {
        val directory = File(context.cacheDir, "shared-backups").apply { mkdirs() }
        val recent = File(directory, "${UUID.randomUUID()}.zip").apply { writeText("backup-fixture") }
        val expired = File(directory, "${UUID.randomUUID()}.zip").apply {
            writeText("expired-fixture")
            setLastModified(System.currentTimeMillis() - SensitiveCache.MAX_AGE_MILLIS - 5000)
        }
        try {
            fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.cache", file)
            context.contentResolver.openFileDescriptor(uri(recent), "r").use { assertNotNull(it) }
            try {
                context.contentResolver.openFileDescriptor(uri(expired), "r")?.close()
                fail("Expired shares must not open")
            } catch (_: FileNotFoundException) {
                assertTrue(recent.exists())
            }
            SensitiveCache.cleanup(context)
            assertFalse(expired.exists())
            assertTrue(recent.exists())
        } finally {
            recent.delete()
            expired.delete()
        }
    }

    @Test
    fun apkVerifierRejectsInstalledVersionAndTampering() {
        val source = File(context.applicationInfo.sourceDir)
        assertFalse(VerifiedUpdateManager.verifyApk(context, source))
        val matching = File(context.filesDir, "security-update/matching.apk")
        val foreign = File(context.filesDir, "security-update/foreign.apk")
        assertTrue("Signed fixtures must be provisioned", matching.isFile && foreign.isFile)
        assertTrue(VerifiedUpdateManager.verifyApk(context, matching))
        assertFalse(VerifiedUpdateManager.verifyApk(context, foreign))
        val fixture = File(context.cacheDir, "tampered-${UUID.randomUUID()}.apk")
        try {
            matching.copyTo(fixture)
            val bytes = fixture.readBytes()
            val marker = "SECURITY_SIGNATURE_PAYLOAD_0123456789".toByteArray()
            val position = (0..bytes.size - marker.size).firstOrNull { offset ->
                marker.indices.all { bytes[offset + it] == marker[it] }
            }
            assertNotNull("Fixture must contain an uncompressed signed payload", position)
            val offset = requireNotNull(position)
            bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
            fixture.writeBytes(bytes)
            assertFalse(VerifiedUpdateManager.verifyApk(context, fixture))
        } finally {
            fixture.delete()
        }
    }
}
