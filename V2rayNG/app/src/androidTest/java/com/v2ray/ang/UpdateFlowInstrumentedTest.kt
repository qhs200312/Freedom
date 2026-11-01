package com.v2ray.ang

import android.app.Notification
import android.app.NotificationManager
import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.handler.UpdateDownloadProgress
import com.v2ray.ang.handler.UpdateNotifications
import com.v2ray.ang.handler.VerifiedUpdateManager
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.checkupdate.UpdateAvailableDialog
import com.v2ray.ang.ui.checkupdate.UpdateInstallActivity
import com.v2ray.ang.ui.compose.AppTheme
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UpdateFlowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val update = CheckUpdateResult(
        hasUpdate = true, latestVersion = "3.0.0",
        downloadUrl = "https://example.org/Freedom_update.apk",
        releaseNotes = "Update download and installation improvements.\nNotification progress and reusable installation packages.",
    )

    private fun fixture(version: Int, files: File): File {
        val source = InstrumentationRegistry.getArguments().getString("updateFixtureDir")
        assumeTrue("Pass updateFixtureDir after creating and pushing the signed APK fixtures", source != null)
        require(source!!.matches(Regex("/data/local/tmp/[A-Za-z0-9_-]+")))
        return File(files, "fixture-$version.part").also { target ->
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cat $source/update-$version.apk"),
            ).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }

    @Test
    fun signedApksAreVerifiedReusedAndReplacedInTheRealDownloadsDirectory(): Unit = runBlocking {
        assumeTrue(context.packageName == "com.v2ray.ang.fdroid")
        val isolatedFiles = File(context.cacheDir, "signed-update-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = isolatedFiles
        }
        try {
            val older = update.copy(latestVersion = "9.0.9000001", downloadUrl = "https://example.org/test-9000001.apk")
            val newer = update.copy(latestVersion = "9.0.9000002", downloadUrl = "https://example.org/test-9000002.apk")
            val first = fixture(9000001, isolatedFiles)
            assertTrue(VerifiedUpdateManager.verifyApk(context, first))
            VerifiedUpdateManager.saveDownloadedPackage(isolated, older, first)
            val originalRecord = JsonUtil.parseString(
                File(isolatedFiles, "app-updates/downloaded.json").readText(),
            )!!.asJsonObject.getAsJsonObject("ready")
            val originalUri = Uri.parse(originalRecord.get("publicUri").asString)
            assertNotNull(VerifiedUpdateManager.installationFile(isolated, older))
            VerifiedUpdateManager.saveDownloadedPackage(isolated, newer, fixture(9000002, isolatedFiles))
            assertNull(VerifiedUpdateManager.findDownloadedUpdate(isolated, older))
            assertEquals(newer.copy(isDownloaded = true), VerifiedUpdateManager.findDownloadedUpdate(isolated))
            assertFalse(File(isolatedFiles, "app-updates/${VerifiedUpdateManager.fileName(older)}").exists())
            assertFalse(context.contentResolver.query(originalUri, arrayOf("_id"), null, null, null)!!.use { it.moveToFirst() })
            val apk = checkNotNull(VerifiedUpdateManager.installationFile(isolated, newer))
            apk.appendText("tampered")
            assertNull(VerifiedUpdateManager.findDownloadedUpdate(isolated))
            assertFalse(apk.exists())
        } finally {
            // Retire any remaining fixture without changing the installed application.
            val record = File(isolatedFiles, "app-updates/downloaded.json")
            if (record.exists()) {
                val root = JsonUtil.parseString(record.readText())!!.asJsonObject
                root.getAsJsonObject("ready")?.addProperty("versionCode", 1)
                record.writeText(root.toString())
                VerifiedUpdateManager.cleanupInstalled(isolated)
            }
            isolatedFiles.walkBottomUp().forEach { it.delete() }
        }
    }

    @Test
    fun notificationTapUsesInstallPermissionAndGrantsAReadableVerifiedApk(): Unit = runBlocking {
        assumeTrue(context.packageName == "com.v2ray.ang.fdroid")
        retireInterruptedFixture()
        assumeTrue(VerifiedUpdateManager.findDownloadedUpdate(context) == null)
        val source = fixture(9000001, context.filesDir)
        val requested = update.copy(latestVersion = "9.0.9000001")
        val ready = VerifiedUpdateManager.saveDownloadedPackage(context, requested, source)
        val activityMonitor = instrumentation.addMonitor(UpdateInstallActivity::class.java.name, null, false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val permissionFilter = IntentFilter(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply { addDataScheme("package") }
                val permissionMonitor = instrumentation.addMonitor(
                    permissionFilter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true,
                )
                try {
                    val activity = instrumentation.startActivitySync(
                        UpdateInstallActivity.intent(context, ready).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    val deadline = SystemClock.elapsedRealtime() + 5000
                    while (permissionMonitor.hits == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
                    assertEquals("Missing permission must open the unknown-apps settings", 1, permissionMonitor.hits)
                    instrumentation.runOnMainSync { activity.finish() }
                } finally {
                    instrumentation.removeMonitor(permissionMonitor)
                }
                return@runBlocking
            }
            val apkFilter = IntentFilter(Intent.ACTION_VIEW).apply { addDataType(VerifiedUpdateManager.APK_MIME_TYPE) }
            val installerMonitor = instrumentation.addMonitor(
                apkFilter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true,
            )
            var foreground: Activity? = null
            try {
                foreground = instrumentation.startActivitySync(
                    Intent(context, CheckUpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                UpdateNotifications(context).showReady(ready)
                SystemClock.sleep(300)
                val notification = context.getSystemService(NotificationManager::class.java)
                    .activeNotifications.single { it.id == UpdateNotifications.NOTIFICATION_ID }.notification
                notification.contentIntent.send()
                val activity = activityMonitor.waitForActivityWithTimeout(5000)
                assertNotNull("Notification must open the installation activity", activity)
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (installerMonitor.hits == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
                assertEquals("A notification tap must launch the system installer", 1, installerMonitor.hits)
                val apk = checkNotNull(VerifiedUpdateManager.installationFile(context, ready))
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
                assertEquals(apk.length(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes().size.toLong() })
                assertNotNull("Cancelling installation must keep the update", VerifiedUpdateManager.findDownloadedUpdate(context))
                activity?.let { instrumentation.runOnMainSync { it.finish() } }
            } finally {
                foreground?.let { instrumentation.runOnMainSync { it.finish() } }
                instrumentation.removeMonitor(installerMonitor)
            }
        } finally {
            instrumentation.removeMonitor(activityMonitor)
            val record = File(context.filesDir, "app-updates/downloaded.json")
            val root = JsonUtil.parseString(record.readText())!!.asJsonObject
            root.getAsJsonObject("ready")?.addProperty("versionCode", 1)
            record.writeText(root.toString())
            VerifiedUpdateManager.cleanupInstalled(context)
        }
    }

    private suspend fun retireInterruptedFixture() {
        val record = File(context.filesDir, "app-updates/downloaded.json")
        if (!record.exists()) return
        val root = JsonUtil.parseString(record.readText())!!.asJsonObject
        val ready = root.getAsJsonObject("ready") ?: return
        val version = ready.getAsJsonObject("result").get("latestVersion").asString
        if (version != "9.0.9000001") return
        ready.addProperty("versionCode", 1)
        record.writeText(root.toString())
        VerifiedUpdateManager.cleanupInstalled(context)
    }

    @Test
    fun updateProviderAllowsAnOldReadOnlyApkWithoutTheBackupExpiry() {
        val directory = File(context.filesDir, "app-updates").apply { mkdirs() }
        val file = File(directory, "provider-test-${UUID.randomUUID()}.apk").apply {
            writeText("read-only-update")
            assertTrue(setLastModified(System.currentTimeMillis() - 24 * 60 * 60 * 1000))
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            assertEquals("read-only-update", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            try {
                context.contentResolver.openFileDescriptor(uri, "w")?.close()
                fail("The installer must never receive write access to the verified APK")
            } catch (_: FileNotFoundException) {
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun installedUpdateCleanupDeletesOnlyItsOwnMediaStoreDownloadAndPrivateCopy(): Unit = runBlocking {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runBlocking
        val isolatedFiles = File(context.cacheDir, "update-storage-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = isolatedFiles
        }
        val apkName = "cleanup-test-${UUID.randomUUID()}.apk"
        val privateApk = File(isolatedFiles, "app-updates/$apkName").apply {
            parentFile!!.mkdirs()
            writeText("fixture")
        }
        fun publish(name: String): Uri {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, VerifiedUpdateManager.APK_MIME_TYPE)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/${VerifiedUpdateManager.DOWNLOAD_DIRECTORY}")
            }
            return checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)).also { uri ->
                context.contentResolver.openOutputStream(uri)!!.use { it.write("fixture".toByteArray()) }
            }
        }
        val publicUri = publish(apkName)
        val unrelated = publish("unrelated-${UUID.randomUUID()}.apk")
        val record = File(isolatedFiles, "app-updates/downloaded.json")
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong()
        }
        fun saveRecord(version: Long) {
            record.writeText(JsonUtil.toJson(mapOf(
                "ready" to mapOf(
                    "result" to update, "fileName" to apkName, "digest" to "sha256:" + "00".repeat(32),
                    "versionCode" to version, "publicUri" to publicUri.toString(),
                ),
                "retired" to emptyList<Any>(),
            )))
        }
        fun exists(uri: Uri) = context.contentResolver.query(uri, arrayOf("_id"), null, null, null)!!.use { it.moveToFirst() }
        try {
            saveRecord(installed + 1)
            VerifiedUpdateManager.cleanupInstalled(isolated)
            assertTrue(privateApk.exists())
            assertTrue(exists(publicUri))
            saveRecord(installed)
            VerifiedUpdateManager.cleanupInstalled(isolated)
            assertFalse(privateApk.exists())
            assertFalse(exists(publicUri))
            assertTrue(exists(unrelated))
        } finally {
            context.contentResolver.delete(publicUri, null, null)
            context.contentResolver.delete(unrelated, null, null)
            isolatedFiles.walkBottomUp().forEach { it.delete() }
        }
    }

    @Test
    fun notificationsContainRealProgressAndAnInstallActionAfterCompletion() {
        val notifications = UpdateNotifications(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            notifications.showProgress(UpdateDownloadProgress(update, 25, 100))
            val progress = manager.activeNotifications.single { it.id == UpdateNotifications.NOTIFICATION_ID }.notification
            assertEquals(25, progress.extras.getInt(Notification.EXTRA_PROGRESS))
            assertEquals(100, progress.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertFalse(progress.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            notifications.showReady(update)
            val deadline = SystemClock.elapsedRealtime() + 3000
            var ready: Notification? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                ready = manager.activeNotifications.firstOrNull { it.id == UpdateNotifications.NOTIFICATION_ID }?.notification
                if (!ready?.actions.isNullOrEmpty()) break
                SystemClock.sleep(25)
            }
            val completed = checkNotNull(ready)
            assertNotNull(completed.contentIntent)
            assertEquals(context.getString(R.string.update_install), completed.actions.single().title.toString())
            assertFalse(completed.flags and Notification.FLAG_ONGOING_EVENT != 0)
        } finally {
            notifications.cancel()
        }
    }

    @Test
    fun captureDownloadedDialogInLightDarkAndLargeText() {
        val activity = instrumentation.startActivitySync(
            Intent(context, CheckUpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as CheckUpdateActivity
        try {
            listOf(false to 1f, true to 1.6f).forEach { (dark, scale) ->
                instrumentation.runOnMainSync {
                    activity.setContent {
                        AppTheme(darkTheme = dark) {
                            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                                Surface(Modifier.fillMaxSize()) {
                                    Box(Modifier.fillMaxSize()) {
                                        UpdateAvailableDialog(update.copy(
                                            isDownloaded = true,
                                            releaseNotes = update.releaseNotes!!.repeat(12),
                                        ), {}, {})
                                    }
                                }
                            }
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(500)
                val directory = File(context.filesDir, "update-test-screenshots").apply { mkdirs() }
                val file = File(directory, if (dark) "downloaded-dark-large-text.png" else "downloaded-light.png")
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
