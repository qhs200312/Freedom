package com.v2ray.ang.ipquality

import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal object IpQualityPlatform {
    private const val HIDE_KEY = "pref_ip_quality_hide_ip"
    private val hiddenState by lazy { MutableStateFlow(MmkvManager.decodeSettingsBool(HIDE_KEY, false)) }
    val hidden get() = hiddenState.asStateFlow()
    fun setHidden(hidden: Boolean) {
        MmkvManager.encodeSettings(HIDE_KEY, hidden)
        hiddenState.value = hidden
    }

    private val store = object : IpQualityStore {
        private fun key(ip: String) = "ip_quality_v1_" + MessageDigest.getInstance("SHA-256")
            .digest(ip.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        override fun read(ip: String) = MmkvManager.decodeSettingsString(key(ip))?.let {
            JsonUtil.fromJsonSafe(it, IpQualityCacheEntry::class.java)
        }
        override fun write(ip: String, entry: IpQualityCacheEntry) {
            MmkvManager.encodeSettings(key(ip), JsonUtil.toJson(entry))
        }
    }
    private val direct by lazy { repository(false) }
    private val proxy by lazy { repository(true) }
    fun repositoryFor(useProxy: Boolean) = if (useProxy) proxy else direct
    private fun repository(useProxy: Boolean) = IpQualityRepository(
        query = { ip -> IpQualityScheduler(OkHttpIpQualityTransport.create(useProxy)).query(ip) },
        store = store,
    )
}

internal class OkHttpIpQualityTransport(
    private val client: OkHttpClient,
    private val available: Boolean = true,
    private val endpoint: (IpQualitySource, String) -> String = Companion::url,
) : IpQualityTransport {
    override suspend fun lookup(source: IpQualitySource, ip: String): IpQualityOutcome {
        if (!available) return IpQualityOutcome.Failure(IpQualityFailure.failed)
        val call = client.newCall(Request.Builder().url(endpoint(source, ip)).get().build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(IpQualityOutcome.Failure(
                        if (e is SocketTimeoutException || e is java.io.InterruptedIOException) IpQualityFailure.timeout
                        else IpQualityFailure.failed,
                    ))
                }
                override fun onResponse(call: Call, response: Response) {
                    val outcome = try {
                        response.use {
                            if (it.code != 200) {
                                IpQualityParser.parse(source, ip, it.code, "")
                            } else {
                                val bytes = ByteArrayOutputStream()
                                it.body.byteStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        check(bytes.size() + count <= 1024 * 1024) { "Oversized IP lookup response" }
                                        bytes.write(buffer, 0, count)
                                    }
                                }
                                IpQualityParser.parse(source, ip, it.code, bytes.toString("UTF-8"))
                            }
                        }
                    } catch (error: Exception) {
                        IpQualityOutcome.Failure(
                            if (error is SocketTimeoutException || error is java.io.InterruptedIOException) IpQualityFailure.timeout
                            else IpQualityFailure.failed,
                        )
                    }
                    if (continuation.isActive) continuation.resume(outcome)
                }
            })
        }
    }

    companion object {
        fun create(useProxy: Boolean): OkHttpIpQualityTransport {
            val socksPort = if (useProxy) SettingsManager.getSocksPort() else 0
            val client = HttpUtil.buildOkHttpClient(
                timeout = 8_000, httpPort = 0, socksPort = socksPort,
                proxyUsername = SettingsManager.getSocksUsername(),
                proxyPassword = SettingsManager.getSocksPassword(),
                followRedirects = true, requireHttps = true,
            ).newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
            return OkHttpIpQualityTransport(client, !useProxy || socksPort > 0)
        }

        fun url(source: IpQualitySource, ip: String): String {
            require(IpQualityAddress.normalize(ip) != null)
            return when (source) {
                IpQualitySource.identMe -> "https://ident.me/json"
                IpQualitySource.ipApiCom ->
                    "http://ip-api.com/json/$ip?fields=status,isp,org,as,mobile,proxy,hosting"
                IpQualitySource.ipQuery -> "https://api.ipquery.io/$ip?format=json"
                IpQualitySource.ipLocate -> "https://iplocate.io/api/lookup/$ip"
                IpQualitySource.proxyCheck -> "https://proxycheck.io/v3/$ip"
                IpQualitySource.ipApiIs -> "https://api.ipapi.is/?q=$ip"
            }
        }
    }
}
