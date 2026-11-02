package com.v2ray.ang.routinghealth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal const val MSG_ROUTING_HEALTH_REQUEST = 90
internal const val MSG_ROUTING_HEALTH_RESULT = 91

/** Owned by one service runtime; credentials never leave the service process. */
internal class RoutingHealthMonitor(
    private val context: Context,
    private val endpoints: List<RoutingHealthEndpoint>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = UUID.randomUUID().toString()
    private var statuses = endpoints.map { it.status }
    private var job: Job? = null
    private var lastStarted: Long? = null
    private var closed = false
    private val check = RoutingHealthCheck(::probe)

    @Synchronized
    fun request(force: Boolean = false) {
        if (closed) return
        publish()
        if (job?.isActive == true || endpoints.isEmpty()) return
        val elapsed = SystemClock.elapsedRealtime()
        if (!force && lastStarted?.let { elapsed - it < 60_000 } == true) return
        lastStarted = elapsed
        if (!hasNetwork()) {
            statuses = endpoints.map { it.status }
            publish()
            return
        }
        val previous = statuses.associateBy { it.tag }
        statuses = endpoints.map {
            if (it.port > 0) it.status.copy(state =
                if (previous[it.status.tag]?.isUnavailable == true) RoutingHealthState.retrying
                else RoutingHealthState.checking)
            else it.status
        }
        publish()
        job = scope.launch {
            val limit = Semaphore(2)
            coroutineScope {
                endpoints.filter { it.port > 0 }.map { endpoint ->
                    launch {
                        limit.withPermit {
                            val result = check.check(endpoint)
                            synchronized(this@RoutingHealthMonitor) {
                                if (!closed) {
                                    val status = if (hasNetwork()) result else endpoint.status
                                    statuses = statuses.map { if (it.tag == status.tag) status else it }
                                    publish()
                                }
                            }
                        }
                    }
                }.joinAll()
            }
        }
    }

    @Synchronized
    fun close() {
        closed = true
        scope.cancel()
        statuses = emptyList()
        publish()
    }

    private fun publish() = MessageHelper.sendMsg2UI(
        context, MSG_ROUTING_HEALTH_RESULT, JsonUtil.toJson(RoutingHealthSnapshot(session, statuses)),
    )

    private fun hasNetwork(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.allNetworks.any { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    private suspend fun probe(endpoint: RoutingHealthEndpoint, url: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val client = HttpUtil.buildOkHttpClient(
                timeout = 4_000, httpPort = 0, socksPort = endpoint.port,
                proxyUsername = endpoint.username, proxyPassword = endpoint.secret,
                followRedirects = false, requireHttps = true,
            ).newBuilder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            val call = client.newCall(Request.Builder().url(url).header("Connection", "close").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                    if (continuation.isActive) continuation.resume(false)
                }
                override fun onResponse(call: Call, response: Response) {
                    val success = response.use { it.code in 200..299 }
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                    if (continuation.isActive) continuation.resume(success)
                }
            })
        }
}
