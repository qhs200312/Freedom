package com.v2ray.ang.routing

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RoutingRuleImportLoaderTest {
    @Test
    fun streamingCopyNeverReadsOrRetainsAnUnboundedFile() {
        val file = File.createTempFile("rules-stream-", ".yaml")
        try {
            RoutingRuleImportLoader.copyToFile(ByteArrayInputStream("payload:\n- DOMAIN,example.com".toByteArray()), file)
            assertEquals(1, RustRuleParser.parseBlocking(file).count)
            assertEquals(YamlImportFailure.TOO_LARGE, assertThrows(YamlRuleImportException::class.java) {
                RoutingRuleImportLoader.copyToFile(ByteArrayInputStream(ByteArray(ClashYamlRules.MAX_BYTES + 1)), file)
            }.failure)
            assertTrue(file.length() <= ClashYamlRules.MAX_BYTES)
        } finally { file.delete() }
    }
    @Test
    fun githubFilePagesBecomeRawUrlsAndOtherHttpsLinksRemainUsable() {
        val cases = mapOf(
            "https://github.com/MetaCubeX/meta-rules-dat/blob/meta/geo/geosite/classical/meta.yaml"
                to "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo/geosite/classical/meta.yaml",
            "https://github.com/owner/repo/raw/main/rules/test.yml?raw=1#L1"
                to "https://raw.githubusercontent.com/owner/repo/main/rules/test.yml",
            "https://raw.githubusercontent.com/owner/repo/main/test.yaml"
                to "https://raw.githubusercontent.com/owner/repo/main/test.yaml",
            " https://example.com/rules.yaml?token=test#rules "
                to "https://example.com/rules.yaml?token=test",
        )
        cases.forEach { (input, expected) -> assertEquals(expected, RoutingRuleImportLoader.normalizeUrl(input)) }
        listOf("http://example.com/test.yaml", "file:///test.yaml", "ftp://example.com/test.yaml",
            "https://user:secret@example.com/rules.yaml", "not a url").forEach {
            assertEquals(YamlImportFailure.URL, assertThrows(YamlRuleImportException::class.java) {
                RoutingRuleImportLoader.normalizeUrl(it)
            }.failure)
        }
    }

    @Test
    fun boundedReaderClosesFilesAndRejectsMalformedUtf8() {
        val file = File.createTempFile("rules-utf8-", ".yaml")
        var closed = false
        val input = object : ByteArrayInputStream("\uFEFFpayload:\n- DOMAIN,example.com".toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        try {
            RoutingRuleImportLoader.copyToFile(input, file)
            assertEquals(1, RustRuleParser.parseBlocking(file).count)
            assertTrue(closed)
            RoutingRuleImportLoader.copyToFile(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)), file)
            assertEquals(YamlImportFailure.FORMAT, assertThrows(YamlRuleImportException::class.java) {
                RustRuleParser.parseBlocking(file)
            }.failure)
            assertEquals(YamlImportFailure.TOO_LARGE, assertThrows(YamlRuleImportException::class.java) {
                RoutingRuleImportLoader.copyToFile(ByteArrayInputStream(ByteArray(ClashYamlRules.MAX_BYTES + 1)), file)
            }.failure)
        } finally { file.delete() }
    }

    @Test
    fun downloaderUsesNormalizedUrlAndReportsHttpErrors() = runBlocking {
        val seen = AtomicReference<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            seen.set(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("payload:\n- DOMAIN,example.com".toResponseBody()).build()
        }.build()
        val file = File.createTempFile("rules-http-", ".yaml")
        try {
            RoutingRuleImportLoader.downloadToFile("https://github.com/owner/repo/blob/main/test.yaml", client, file)
            assertEquals("https://raw.githubusercontent.com/owner/repo/main/test.yaml", seen.get())
            assertEquals(1, RustRuleParser.parseBlocking(file).count)
            for (status in listOf(404, 429, 503)) {
            val rejected = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("Rejected").body("error".toResponseBody()).build()
            }.build()
            try {
                RoutingRuleImportLoader.downloadToFile("https://example.com/rules.yaml", rejected, file)
                fail("HTTP $status should fail")
            } catch (error: YamlRuleImportException) {
                assertEquals(YamlImportFailure.HTTP, error.failure)
                assertEquals("$status", error.detail)
            }
            }
        } finally { file.delete() }
    }

    @Test
    fun dismissingImportCancelsTheInFlightHttpCall() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val activeCall = AtomicReference<Call>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            activeCall.set(chain.call())
            started.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("payload:\n- DOMAIN,example.com".toResponseBody()).build()
        }.build()
        val file = File.createTempFile("rules-cancel-", ".yaml")
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            RoutingRuleImportLoader.downloadToFile("https://example.com/rules.yaml", client, file)
        }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(activeCall.get().isCanceled())
        } finally {
            finish.countDown()
            file.delete()
        }
    }
}
