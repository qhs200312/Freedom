package com.v2ray.ang.ipquality

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.runtime.XrayOutboundBridges
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Explicit node quality tests only. No query uses the application's active SOCKS listener. */
internal class NodeIpQualityProbe(private val context: Context) {
    private val ipLocks = ConcurrentHashMap<String, Mutex>()
    suspend fun lookup(guid: String): NodeIpQualityResult = slots.withPermit {
        try {
            withContext(Dispatchers.IO) { probe(guid) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NodeIpQualityResult()
        }
    }

    private suspend fun probe(guid: String): NodeIpQualityResult {
        val bridges = XrayOutboundBridges(context)
        var client: OkHttpClient? = null
        val core = CoreNativeManager.newCoreController(object : CoreCallbackHandler {
            override fun startup() = 0L
            override fun shutdown() = 0L
            override fun onEmitStatus(l: Long, s: String?) = 0L
        })
        try {
            val config = runInterruptible {
                CoreConfigManager.getV2rayConfig4Speedtest(context, guid, bridges::prepare)
            }
            check(config.status)
            val port = Utils.findRandomFreePort()
            val username = UUID.randomUUID().toString()
            val secret = UUID.randomUUID().toString()
            val content = NodeIpQualityConfig.build(config.content, port, username, secret)
            runInterruptible { core.startLoop(content, 0) }
            check(core.isRunning && bridges.isAlive)
            val http = HttpUtil.buildOkHttpClient(
                timeout = 8_000, httpPort = 0, socksPort = port,
                proxyUsername = username, proxyPassword = secret,
                followRedirects = true, requireHttps = true,
            ).newBuilder().callTimeout(8, TimeUnit.SECONDS).build().also { client = it }
            val repository = IpQualityPlatform.repositoryForClient(http)
            return NodeIpQualityLookup(
                exitIp = {
                    var found: String? = null
                    for (url in listOf("https://api4.ipify.org", "https://api64.ipify.org")) {
                        found = fetchIp(http, url)
                        if (found != null) break
                    }
                    found
                },
                classify = { ip ->
                    // Nodes sharing an exit reuse the IP cache even when probed concurrently.
                    ipLocks.getOrPut(ip) { Mutex() }.withLock { repository.get(ip) }
                },
            ).lookup()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                client?.dispatcher?.cancelAll()
                client?.connectionPool?.evictAll()
                client?.dispatcher?.executorService?.shutdown()
                try {
                    runCatching { core.stopLoop() }
                    core.callbackHandler = null
                } finally {
                    bridges.close()
                }
            }
        }
    }

    private suspend fun fetchIp(client: OkHttpClient, url: String): String? =
        suspendCancellableCoroutine { continuation ->
            val call = client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val ip = try {
                        response.use {
                            if (it.code != 200) null else {
                                val source = it.body.source()
                                if (source.request(129)) null
                                else IpQualityAddress.normalize(source.readUtf8())
                            }
                        }
                    } catch (_: Exception) { null }
                    if (continuation.isActive) continuation.resume(ip)
                }
            })
        }

    companion object {
        private val slots = Semaphore(2)
    }
}
