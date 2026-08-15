package com.v2ray.ang.handler

import android.content.Context
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.TrafficSnapshot
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Single owner of Xray's reset-on-read traffic counters. */
object TrafficStatsManager {
    private const val QUERY_INTERVAL_MS = 3_000L
    private const val PERSIST_INTERVAL_MS = 30_000L
    private const val KEY_TOTAL_UPLINK = "home_total_uplink"
    private const val KEY_TOTAL_DOWNLINK = "home_total_downlink"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshot = MutableStateFlow(TrafficSnapshot())
    private var pollingJob: Job? = null
    private var loaded = false
    private var sessionUplink = 0L
    private var sessionDownlink = 0L
    private var totalUplink = 0L
    private var totalDownlink = 0L
    private var lastPersistTime = 0L
    private var broadcastContext: Context? = null

    val snapshot: StateFlow<TrafficSnapshot>
        get() {
            ensureLoaded()
            return _snapshot.asStateFlow()
        }

    @Synchronized
    fun start(context: Context) {
        ensureLoaded()
        if (pollingJob?.isActive == true) return
        broadcastContext = context.applicationContext

        sessionUplink = 0L
        sessionDownlink = 0L
        lastPersistTime = SystemClock.elapsedRealtime()
        _snapshot.update {
            it.copy(
                proxyUplinkSpeed = 0L,
                proxyDownlinkSpeed = 0L,
                directUplinkSpeed = 0L,
                directDownlinkSpeed = 0L,
                sessionUplink = 0L,
                sessionDownlink = 0L,
                totalUplink = totalUplink,
                totalDownlink = totalDownlink,
            )
        }
        broadcastSnapshot()

        pollingJob = scope.launch {
            var previousQueryTime = SystemClock.elapsedRealtime()
            delay(QUERY_INTERVAL_MS)
            while (isActive && CoreServiceManager.isRunning()) {
                val queryTime = SystemClock.elapsedRealtime()
                val elapsedSeconds = ((queryTime - previousQueryTime).coerceAtLeast(1L)) / 1000.0
                previousQueryTime = queryTime

                try {
                    publish(CoreServiceManager.queryAllOutboundTrafficStats(), elapsedSeconds)
                    broadcastSnapshot()
                } catch (error: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to query traffic stats", error)
                }

                if (queryTime - lastPersistTime >= PERSIST_INTERVAL_MS) {
                    persistTotals()
                    lastPersistTime = queryTime
                }
                delay(QUERY_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        persistTotals()
        _snapshot.update {
            it.copy(
                proxyUplinkSpeed = 0L,
                proxyDownlinkSpeed = 0L,
                directUplinkSpeed = 0L,
                directDownlinkSpeed = 0L,
            )
        }
        broadcastSnapshot()
    }

    internal fun publish(stats: List<OutboundTrafficStat>, elapsedSeconds: Double) {
        var proxyUp = 0L
        var proxyDown = 0L
        var directUp = 0L
        var directDown = 0L

        stats.forEach { stat ->
            when {
                stat.tag == AppConfig.TAG_DIRECT && stat.direction == AppConfig.UPLINK ->
                    directUp += stat.value
                stat.tag == AppConfig.TAG_DIRECT && stat.direction == AppConfig.DOWNLINK ->
                    directDown += stat.value
                stat.tag.startsWith(AppConfig.TAG_PROXY) && stat.direction == AppConfig.UPLINK ->
                    proxyUp += stat.value
                stat.tag.startsWith(AppConfig.TAG_PROXY) && stat.direction == AppConfig.DOWNLINK ->
                    proxyDown += stat.value
            }
        }

        val uplink = proxyUp + directUp
        val downlink = proxyDown + directDown
        sessionUplink += uplink
        sessionDownlink += downlink
        totalUplink += uplink
        totalDownlink += downlink

        _snapshot.value = TrafficSnapshot(
            proxyUplinkSpeed = (proxyUp / elapsedSeconds).toLong(),
            proxyDownlinkSpeed = (proxyDown / elapsedSeconds).toLong(),
            directUplinkSpeed = (directUp / elapsedSeconds).toLong(),
            directDownlinkSpeed = (directDown / elapsedSeconds).toLong(),
            sessionUplink = sessionUplink,
            sessionDownlink = sessionDownlink,
            totalUplink = totalUplink,
            totalDownlink = totalDownlink,
        )
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        totalUplink = MmkvManager.decodeSettingsLong(KEY_TOTAL_UPLINK, 0L)
        totalDownlink = MmkvManager.decodeSettingsLong(KEY_TOTAL_DOWNLINK, 0L)
        _snapshot.value = TrafficSnapshot(
            totalUplink = totalUplink,
            totalDownlink = totalDownlink,
        )
        loaded = true
    }

    private fun persistTotals() {
        if (!loaded) return
        MmkvManager.encodeSettings(KEY_TOTAL_UPLINK, totalUplink)
        MmkvManager.encodeSettings(KEY_TOTAL_DOWNLINK, totalDownlink)
    }

    private fun broadcastSnapshot() {
        broadcastContext?.let {
            MessageHelper.sendMsg2UI(it, AppConfig.MSG_TRAFFIC_UPDATE, _snapshot.value)
        }
    }
}
