package com.v2ray.ang.routing

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object RoutingRuleImportLoader {
    fun normalizeUrl(value: String): String {
        val url = value.trim().toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
            ?: throw YamlRuleImportException(YamlImportFailure.URL)
        val path = url.encodedPath.split('/').drop(1)
        return if (url.host == "github.com" && path.size >= 5 && path[2] in setOf("blob", "raw")) {
            url.newBuilder().host("raw.githubusercontent.com")
                .encodedPath("/" + (path.take(2) + path.drop(3)).joinToString("/"))
                .query(null).fragment(null).build().toString()
        } else url.newBuilder().fragment(null).build().toString()
    }

    fun copyToFile(input: InputStream, file: File, checkCancelled: () -> Unit = {}) {
        input.use { source ->
            file.outputStream().buffered().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    checkCancelled()
                    val count = source.read(buffer)
                    if (count == -1) break
                    total += count
                    if (total > ClashYamlRules.MAX_BYTES) throw YamlRuleImportException(YamlImportFailure.TOO_LARGE)
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    suspend fun downloadToFile(value: String, client: OkHttpClient, file: File) {
        request(value, client) { copyToFile(it, file) }
    }

    private suspend fun <T> request(value: String, client: OkHttpClient, consume: (InputStream) -> T): T {
        val request = Request.Builder().url(normalizeUrl(value)).get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val content = response.use {
                            if (!it.isSuccessful) throw YamlRuleImportException(YamlImportFailure.HTTP, detail = "${it.code}")
                            val body = it.body
                            if (body.contentLength() > ClashYamlRules.MAX_BYTES) {
                                throw YamlRuleImportException(YamlImportFailure.TOO_LARGE)
                            }
                            consume(body.byteStream())
                        }
                        if (continuation.isActive) continuation.resume(content)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}
