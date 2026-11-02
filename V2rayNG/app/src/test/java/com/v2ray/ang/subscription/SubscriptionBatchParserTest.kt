package com.v2ray.ang.subscription

import com.v2ray.ang.dto.VmessQRCode
import com.v2ray.ang.routing.YamlImportFailure
import com.v2ray.ang.routing.YamlRuleImportException
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.Reader
import java.util.Base64

class SubscriptionBatchParserTest {
    private fun read(content: String, encoded: Boolean = false): List<Pair<String, VmessQRCode?>> {
        val result = ArrayList<Pair<String, VmessQRCode?>>()
        SubscriptionBatchParser.withInput(content) { file ->
            SubscriptionBatchParser.forEach(file, encoded) { line, vmess -> result += line to vmess }
        }
        return result
    }

    @Test fun plainAndBase64KeepLegacyOrderingAndExactDedupIdentity() {
        val input = "vless://id@host:443?type=tcp#one\r\nss://example\r" +
            "vless://id@host:443?type=tcp#one\n vless://different\n\nhy2://key@host"
        val expected = input.lines().distinct().reversed().filter(String::isNotEmpty)
        assertEquals(expected, read(input).map { it.first })
        for (encoder in listOf(Base64.getEncoder(), Base64.getUrlEncoder().withoutPadding())) {
            val encoded = encoder.encodeToString(input.toByteArray()).chunked(60).joinToString("\r\n")
            assertEquals(expected, read(encoded, true).map { it.first })
        }
    }

    @Test fun nativeVmessMatchesGsonDefaultsScalarsUnicodeAndEscaping() {
        val cases = listOf(
            """{"add":"node.example","port":443,"id":"test-id","net":"tcp"}""",
            """{"ps":"\u4e2d\u6587 \"quoted\"","add":"node","port":"443","id":"id","net":"grpc","type":"multi","host":"authority","path":"service","scy":"auto","tls":"tls","sni":"sni","fp":"chrome","alpn":"h2","insecure":"1","vcn":"name","pcs":"hash"}""",
            """{"ps":123,"add":"node","port":443,"id":"id","net":"ws","insecure":true,"extra":{"ignored":true}}""",
        )
        cases.forEach { json ->
            val link = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())
            val actual = read(link).single().second
            assertNotNull(actual)
            assertEquals(JsonUtil.fromJson(json, VmessQRCode::class.java), actual)
        }
    }

    @Test fun nonFastPathProtocolsAndUnusualVmessRetainOriginalInput() {
        val unusual = "vmess://" + Base64.getEncoder().encodeToString(
            """{"add":"node","port":443,"id":"id","net":"tcp","ps":null}""".toByteArray(),
        )
        val cases = listOf(
            "vmess://" + Base64.getEncoder().encodeToString(
                """{"add":"node","port":443,"id":"id","net":"tcp","ps":1e6}""".toByteArray(),
            ),
            unusual, "vmess://id@host:443?type=tcp&security=tls",
            "vless://id@host:443?type=ws&path=%2Fa#A%2BB",
            "trojan://key@[2001:db8::1]:443", "ss://cipher:key@host:443",
            "socks5://user:key@host:1080", "wireguard://key@host:443",
            "hy2://key@host:443", "anytls://key@host:443", "nowhere://key@host:443",
        )
        val result = read(cases.joinToString("\n"))
        assertEquals(cases.reversed(), result.map { it.first })
        assertTrue(result.all { it.second == null })
    }

    @Test fun refusesTooManyNodesBeforeDeliveringPartialResults() {
        val text = (0..SubscriptionBatchParser.MAX_NODES).joinToString("\n") { "node-$it" }
        var delivered = false
        val error = assertThrows(YamlRuleImportException::class.java) {
            SubscriptionBatchParser.withInput(text) { file ->
                SubscriptionBatchParser.forEach(file, false) { _, _ -> delivered = true }
            }
        }
        assertEquals(YamlImportFailure.TOO_LARGE, error.failure)
        assertFalse(delivered)
    }

    @Test fun boundedUtf8WriterStopsWithoutBufferingEntireResponse() {
        var remaining = SubscriptionBatchParser.MAX_BYTES
        var read = 0
        val input = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                if (remaining == 0) return -1
                val count = minOf(length, remaining)
                java.util.Arrays.fill(buffer, offset, offset + count, '\u4e2d')
                remaining -= count
                read += count
                return count
            }
            override fun close() {}
        }
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { SubscriptionBatchParser.writeBounded(input, output) }
        assertTrue(output.size() <= SubscriptionBatchParser.MAX_BYTES)
        assertTrue(read < SubscriptionBatchParser.MAX_BYTES / 2)
    }

    @Test fun temporaryInputsAreRemovedOnSuccessAndFailure() {
        lateinit var path: java.io.File
        SubscriptionBatchParser.withInput("node") { path = it; assertTrue(it.isFile) }
        assertFalse(path.exists())
        assertThrows(IllegalStateException::class.java) {
            SubscriptionBatchParser.withInput("node") { path = it; error("stop") }
        }
        assertFalse(path.exists())
    }
}
