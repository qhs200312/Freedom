package com.v2ray.ang.handler

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Environment
import android.util.AtomicFile
import com.v2ray.ang.dto.CheckUpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import java.io.File
import java.io.FileOutputStream
import java.net.URI

/** Mock Android OS wrappers; exercise real file copies, verification decisions, records and deletion. */
@Suppress("DEPRECATION")
class UpdateStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var atomicFiles: MockedConstruction<AtomicFile>
    private lateinit var environment: MockedStatic<Environment>
    private lateinit var uris: MockedStatic<Uri>
    private lateinit var files: File
    private lateinit var downloads: File
    private var installedVersion = 100

    private fun setUp() {
        files = temporary.newFolder("private")
        downloads = temporary.newFolder("Download")
        val signer = Mockito.mock(Signature::class.java)
        whenever(signer.toByteArray()).thenReturn("same-signing-certificate".toByteArray())
        fun packageInfo(version: Int) = Mockito.mock(PackageInfo::class.java).apply {
            packageName = "com.v2ray.ang"
            versionCode = version
            signatures = arrayOf(signer)
        }
        val packages = Mockito.mock(PackageManager::class.java)
        whenever(packages.getPackageInfo(any<String>(), any<Int>())).thenAnswer { packageInfo(installedVersion) }
        whenever(packages.getPackageArchiveInfo(any<String>(), any<Int>())).thenAnswer { call ->
            File(call.getArgument<String>(0)).takeIf { it.isFile }?.readText()
                ?.removePrefix("apk-version:")?.toIntOrNull()?.let(::packageInfo)
        }
        context = Mockito.mock(Context::class.java)
        whenever(context.filesDir).thenReturn(files)
        whenever(context.packageName).thenReturn("com.v2ray.ang")
        whenever(context.packageManager).thenReturn(packages)
        whenever(context.getSystemService(NotificationManager::class.java))
            .thenReturn(Mockito.mock(NotificationManager::class.java))

        environment = Mockito.mockStatic(Environment::class.java)
        environment.`when`<File> { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
            .thenReturn(downloads)
        uris = Mockito.mockStatic(Uri::class.java)
        uris.`when`<Uri> { Uri.fromFile(any()) }.thenAnswer { call -> uri(call.getArgument<File>(0).toURI()) }
        uris.`when`<Uri> { Uri.parse(any()) }.thenAnswer { call -> uri(URI(call.getArgument<String>(0))) }
        atomicFiles = Mockito.mockConstruction(AtomicFile::class.java) { mock, construction ->
            val file = construction.arguments()[0] as File
            whenever(mock.baseFile).thenReturn(file)
            whenever(mock.readFully()).thenAnswer { file.readBytes() }
            whenever(mock.startWrite()).thenAnswer { file.outputStream() }
            doAnswer { call -> call.getArgument<FileOutputStream>(0).close(); null }
                .whenever(mock).finishWrite(any())
            doAnswer { call -> call.getArgument<FileOutputStream>(0).close(); null }
                .whenever(mock).failWrite(any())
        }
    }

    private fun uri(value: URI): Uri = Mockito.mock(Uri::class.java).also {
        whenever(it.scheme).thenReturn(value.scheme)
        whenever(it.authority).thenReturn(value.authority)
        whenever(it.path).thenReturn(value.path)
        whenever(it.toString()).thenReturn(value.toString())
    }

    private fun tearDown() {
        atomicFiles.close()
        uris.close()
        environment.close()
    }

    private fun withStorage(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) {
        // Mockito's Android OS mocks are thread-local; storage operations stay on this IO dispatcher.
        setUp()
        try {
            block()
        } finally {
            tearDown()
        }
    }

    private fun result(version: Int) = CheckUpdateResult(
        hasUpdate = true, latestVersion = "3.0.$version",
        downloadUrl = "https://example.org/Freedom_$version.apk",
    )

    private fun source(version: Int) = File(files, "source-$version.part").apply {
        writeText("apk-version:$version")
    }

    private fun publicApks() = File(downloads, VerifiedUpdateManager.DOWNLOAD_DIRECTORY)
        .listFiles().orEmpty().filter { it.extension == "apk" }

    @Test
    fun savedPackageIsReusedAndDoesNotExpireAfterFifteenMinutes() = withStorage {
        val requested = result(101)
        val saved = VerifiedUpdateManager.saveDownloadedPackage(context, requested, source(101))
        val apk = File(files, "app-updates/${VerifiedUpdateManager.fileName(requested)}")
        assertTrue(apk.setLastModified(System.currentTimeMillis() - 24 * 60 * 60 * 1000))
        assertTrue(saved.isDownloaded)
        assertEquals(saved, VerifiedUpdateManager.findDownloadedUpdate(context, requested))
        assertEquals(apk, VerifiedUpdateManager.installationFile(context, requested))
        assertEquals(listOf(apk.name), publicApks().map { it.name })
        assertEquals(apk.readText(), publicApks().single().readText())
    }

    @Test
    fun newerVerifiedDownloadReplacesBothCopiesOfTheOldPackage() = withStorage {
        val older = result(101)
        val newer = result(102)
        VerifiedUpdateManager.saveDownloadedPackage(context, older, source(101))
        VerifiedUpdateManager.saveDownloadedPackage(context, newer, source(102))
        assertNull(VerifiedUpdateManager.findDownloadedUpdate(context, older))
        assertEquals(newer.copy(isDownloaded = true), VerifiedUpdateManager.findDownloadedUpdate(context))
        assertFalse(File(files, "app-updates/${VerifiedUpdateManager.fileName(older)}").exists())
        assertEquals(listOf(VerifiedUpdateManager.fileName(newer)), publicApks().map { it.name })
    }

    @Test
    fun failedVerificationPreservesThePreviousUpdateAndItsPublicCopy() = withStorage {
        val older = result(101)
        VerifiedUpdateManager.saveDownloadedPackage(context, older, source(101))
        val invalid = File(files, "invalid.part").apply { writeText("not-an-apk") }
        try {
            VerifiedUpdateManager.saveDownloadedPackage(context, result(102), invalid)
            fail("Invalid update must be rejected")
        } catch (_: IllegalStateException) {
        }
        assertEquals(older.copy(isDownloaded = true), VerifiedUpdateManager.findDownloadedUpdate(context))
        assertEquals(listOf(VerifiedUpdateManager.fileName(older)), publicApks().map { it.name })
    }

    @Test
    fun cancellingInstallKeepsPackagesButSuccessfulInstallDeletesBothCopies() = withStorage {
        val requested = result(101)
        VerifiedUpdateManager.saveDownloadedPackage(context, requested, source(101))
        VerifiedUpdateManager.cleanupInstalled(context)
        assertNotNull(VerifiedUpdateManager.findDownloadedUpdate(context))
        assertEquals(1, publicApks().size)
        installedVersion = 101
        VerifiedUpdateManager.cleanupInstalled(context)
        assertNull(VerifiedUpdateManager.findDownloadedUpdate(context))
        assertTrue(publicApks().isEmpty())
        assertFalse(File(files, "app-updates/${VerifiedUpdateManager.fileName(requested)}").exists())
    }
}
