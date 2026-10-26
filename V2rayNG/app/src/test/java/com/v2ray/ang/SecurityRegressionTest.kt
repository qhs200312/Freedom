package com.v2ray.ang

import android.webkit.URLUtil
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.handler.requiresHttpSubscriptionConsent
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.ZipUtil
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.kotlin.any
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class SecurityRegressionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logLevel = LogUtil::class.java.getDeclaredField("cachedMinPriority").apply {
        isAccessible = true
    }
    private var previousLogLevel = 0

    @Before
    fun disableAndroidLogging() {
        previousLogLevel = logLevel.getInt(LogUtil)
        logLevel.setInt(LogUtil, Int.MAX_VALUE)
    }

    @After
    fun restoreAndroidLogging() {
        logLevel.setInt(LogUtil, previousLogLevel)
    }

    @Test
    fun publicHttpCannotMasqueradeAsLoopback() = withUrlChecks {
        listOf(
            "http://evil.example/?x=127.0.0.1",
            "http://127.0.0.1.evil.example/sub",
            "http://127.0.0.1@evil.example/sub",
            "http://evil.example/#127.0.0.1",
            "HTTP://evil.example/sub",
        ).forEach {
            assertFalse(it, Utils.isValidSubUrl(it))
            assertTrue(it, requiresHttpSubscriptionConsent(it))
        }
    }

    @Test
    fun localAndHttpsSubscriptionsRemainUsable() = withUrlChecks {
        listOf(
            "http://127.0.0.1/sub",
            "http://localhost/sub",
            "http://[::1]/sub",
            "http://192.168.1.2/sub",
            "http://10.0.0.1/sub",
            "https://example.org/sub",
        ).forEach {
            assertTrue(it, Utils.isValidSubUrl(it))
            assertFalse(it, requiresHttpSubscriptionConsent(it))
        }
        assertFalse(Utils.isValidSubUrl(null))
        assertFalse(Utils.isValidSubUrl("http:///missing-host"))
    }

    @Test
    fun zipRoundTripPreservesContentsAndClosesFiles() {
        val source = temporaryFolder.newFolder("source")
        File(source, "settings").writeText("test-settings")
        File(source, "settings.crc").writeBytes(byteArrayOf(1, 2, 3))
        val zip = File(temporaryFolder.root, "backup.zip")
        assertTrue(ZipUtil.zipFromFolder(source.path, zip.path))
        val dest = File(temporaryFolder.root, "restored")
        assertTrue(ZipUtil.unzipToFolder(zip, dest.path))
        assertEquals("test-settings", File(dest, "settings").readText())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dest, "settings.crc").readBytes())
        assertTrue(zip.delete())
        assertTrue(File(source, "settings").delete())
    }

    @Test
    fun zipRejectsTraversalAndSiblingPrefix() {
        val names = listOf("../escape", "../restored-other/escape") +
            if (File.separatorChar == '\\') listOf("..\\escape") else emptyList()
        names.forEachIndexed { i, name ->
            val zip = archive("traversal-$i.zip") {
                putNextEntry(ZipEntry(name))
                write(byteArrayOf(1))
                closeEntry()
            }
            assertFalse(name, ZipUtil.unzipToFolder(zip, File(temporaryFolder.root, "restored").path))
            assertFalse(File(temporaryFolder.root, "escape").exists())
            assertFalse(File(temporaryFolder.root, "restored-other/escape").exists())
        }
    }

    @Test
    fun zipCreatesNestedParentsWithoutDirectoryEntries() {
        val zip = archive("nested.zip") {
            putNextEntry(ZipEntry("nested/child/settings"))
            write(byteArrayOf(1, 2))
            closeEntry()
        }
        val dest = File(temporaryFolder.root, "nested-output")
        assertTrue(ZipUtil.unzipToFolder(zip, dest.path))
        assertArrayEquals(byteArrayOf(1, 2), File(dest, "nested/child/settings").readBytes())
    }

    @Test
    fun zipRejectsExcessiveEntryCount() {
        val zip = archive("many.zip") {
            repeat(2049) {
                putNextEntry(ZipEntry("entry-$it"))
                closeEntry()
            }
        }
        val dest = File(temporaryFolder.root, "many-output")
        assertFalse(ZipUtil.unzipToFolder(zip, dest.path))
        assertFalse(File(dest, "entry-2048").exists())
    }

    @Test
    fun zipRejectsOversizedExpansion() {
        val zip = archive("large.zip") {
            putNextEntry(ZipEntry("large"))
            val chunk = ByteArray(1024 * 1024)
            repeat(257) { write(chunk) }
            closeEntry()
        }
        val dest = File(temporaryFolder.root, "large-output")
        assertFalse(ZipUtil.unzipToFolder(zip, dest.path))
        assertTrue(File(dest, "large").length() <= 256L * 1024 * 1024)
    }

    @Test
    fun prereleaseAndBuildSuffixesDoNotCrashVersionComparison() {
        val compare = UpdateCheckerManager::class.java.getDeclaredMethod(
            "compareVersions", String::class.java, String::class.java,
        ).apply { isAccessible = true }
        fun versions(first: String, second: String) =
            compare.invoke(UpdateCheckerManager, first, second) as Int

        assertTrue(versions("v2.9.0-beta.1", "2.8.0") > 0)
        assertEquals(0, versions("2.8.0+build.1", "v2.8"))
        assertTrue(versions("2.7.9", "2.8.0") < 0)
        assertTrue(versions("2147483647.0", "1.0") > 0)
    }

    @Test
    fun updateAssetsMatchApkDistributionAndExactAbi() {
        val fdroid = BuildConfig.APPLICATION_ID.contains("fdroid", ignoreCase = true)
        val suffix = if (fdroid) "-fdroid" else ""
        val other = if (fdroid) "" else "-fdroid"
        fun asset(name: String) = GitHubRelease.Asset(name, "https://example.org/$name")
        val release = GitHubRelease("2.9.0", "", listOf(
            asset("Freedom_2.9.0${suffix}_x86.apk.sha256"),
            asset("Freedom_2.9.0${other}_x86.apk"),
            asset("Freedom_2.9.0${suffix}_x86_64.apk"),
            asset("Freedom_2.9.0${suffix}_x86.apk"),
            asset("Freedom_2.9.0${suffix}_universal.apk"),
        ))
        val download = UpdateCheckerManager::class.java.getDeclaredMethod(
            "getDownloadUrl", GitHubRelease::class.java, String::class.java,
        ).apply { isAccessible = true }
        assertEquals("https://example.org/Freedom_2.9.0${suffix}_x86.apk",
            download.invoke(UpdateCheckerManager, release, "x86"))
        assertEquals("https://example.org/Freedom_2.9.0${suffix}_universal.apk",
            download.invoke(UpdateCheckerManager, release, "arm64-v8a"))
    }

    @Test
    fun emptyDownloadUrlHasExplicitError() {
        listOf(null, "").forEach {
            try {
                HttpUtil.getUrlContentWithUserAgent(UrlContentRequest(url = it))
                fail("An empty URL must fail")
            } catch (e: IOException) {
                assertEquals("Empty URL", e.message)
            }
        }
    }

    @Test
    fun smallDownloadStillSucceeds() {
        val body = "test-body".toByteArray()
        serve("Content-Length: ${body.size}\r\n", body) { url ->
            val target = File(temporaryFolder.root, "small-download")
            assertTrue(HttpUtil.downloadToFile(UrlContentRequest(url = url), target))
            assertArrayEquals(body, target.readBytes())
        }
    }

    @Test
    fun declaredOversizedDownloadIsRejectedBeforeWriting() {
        serve("Content-Length: 268435457\r\n", byteArrayOf()) { url ->
            val target = File(temporaryFolder.root, "declared-large")
            assertFalse(HttpUtil.downloadToFile(UrlContentRequest(url = url), target))
            assertFalse(target.exists())
        }
    }

    @Test
    fun downloadLimitAlsoAppliesWithoutContentLength() {
        val compressed = ByteArrayOutputStream().also { bytes ->
            GZIPOutputStream(bytes).use { gzip ->
                val chunk = ByteArray(1024 * 1024)
                repeat(257) { gzip.write(chunk) }
            }
        }.toByteArray()
        serve("Content-Encoding: gzip\r\nContent-Length: ${compressed.size}\r\n", compressed) { url ->
            val target = File(temporaryFolder.root, "streamed-large")
            assertFalse(HttpUtil.downloadToFile(UrlContentRequest(url = url), target))
            assertTrue(target.length() <= 256L * 1024 * 1024)
        }
    }

    @Test
    fun unapprovedPublicHttpRedirectIsRejectedBeforeFetching() = withUrlChecks {
        serve("Location: http://untrusted.invalid/sub\r\nContent-Length: 0\r\n", byteArrayOf(), "302 Found") { url ->
            try {
                HttpUtil.getUrlContentWithUserAgent(UrlContentRequest(url = url, allowInsecureHttp = false))
                fail("A redirect must not bypass subscription consent")
            } catch (e: IOException) {
                assertEquals("HTTP subscription requires consent", e.message)
            }
        }
    }

    @Test
    fun approvedHttpRedirectWorksWithoutLeakingCustomHeadersAcrossOrigins() {
        serve("Content-Length: 2\r\n", "ok".toByteArray(), observe = { lines ->
            assertFalse(lines.any { it.startsWith("Authorization:", ignoreCase = true) })
            assertFalse(lines.any { it.startsWith("X-Private-Key:", ignoreCase = true) })
        }) { destination ->
            serve("Location: $destination\r\nContent-Length: 0\r\n", byteArrayOf(), "302 Found", observe = { lines ->
                assertTrue(lines.any { it == "Authorization: Bearer fixture" })
            }) { source ->
                assertEquals("ok", HttpUtil.getUrlContentWithUserAgent(UrlContentRequest(
                    url = source, allowInsecureHttp = true,
                    requestHeaders = """{"Authorization":"Bearer fixture","X-Private-Key":"fixture"}""",
                )))
            }
        }
    }

    @Test
    fun updateDownloadsRejectHttpBeforeOpeningAnOutputFile() {
        val target = File(temporaryFolder.root, "https-required")
        assertFalse(HttpUtil.downloadToFile(UrlContentRequest(
            url = "http://127.0.0.1:1/update.apk", requireHttps = true,
        ), target))
        assertFalse(target.exists())
    }

    private fun withUrlChecks(block: () -> Unit) {
        Mockito.mockStatic(URLUtil::class.java).use { urls ->
            urls.`when`<Boolean> { URLUtil.isHttpUrl(any()) }
                .thenAnswer { it.getArgument<String>(0).startsWith("http://", ignoreCase = true) }
            urls.`when`<Boolean> { URLUtil.isHttpsUrl(any()) }
                .thenAnswer { it.getArgument<String>(0).startsWith("https://", ignoreCase = true) }
            block()
        }
    }

    private fun archive(name: String, write: ZipOutputStream.() -> Unit): File =
        File(temporaryFolder.root, name).also { zip ->
            ZipOutputStream(zip.outputStream()).use { it.write() }
        }

    private fun serve(
        headers: String,
        body: ByteArray,
        status: String = "200 OK",
        observe: (List<String>) -> Unit = {},
        check: (String) -> Unit,
    ) {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val workerFailure = AtomicReference<Throwable>()
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val requestLines = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            requestLines.add(line)
                        }
                        observe(requestLines)
                        socket.getOutputStream().use {
                            it.write("HTTP/1.1 $status\r\n${headers}Connection: close\r\n\r\n".toByteArray())
                            it.write(body)
                        }
                    }
                } catch (_: IOException) {
                    // Rejected oversized responses may close before the server finishes writing.
                } catch (e: Throwable) {
                    workerFailure.set(e)
                }
            }
            try {
                check("http://127.0.0.1:${server.localPort}/file")
            } finally {
                server.close()
                worker.join(5000)
            }
            assertFalse("HTTP fixture did not stop", worker.isAlive)
            workerFailure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
        }
    }
}
