// Logic reference: FlClash (GPL-3.0), independently implemented from the supplied behavior specification.
package com.v2ray.ang.ipquality

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

fun interface IpQualityTransport {
    suspend fun lookup(source: IpQualitySource, ip: String): IpQualityOutcome
}

class IpQualityScheduler(
    private val transport: IpQualityTransport,
    private val timeoutMillis: Long = 8_000,
    private val waveDelayMillis: Long = 1_000,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun query(ip: String): IpQuality = coroutineScope queryScope@ {
        val failures = linkedMapOf<IpQualitySource, IpQualityFailure>()
        var fallback: IpQuality? = null
        val started = nanoTime()
        for ((index, wave) in waves.withIndex()) {
            // Quota gating: later waves start only after the preceding wave has no explicit type.
            val remaining = index * waveDelayMillis - (nanoTime() - started) / 1_000_000
            if (remaining > 0) delay(remaining)
            val winner = coroutineScope waveScope@ {
                val results = Channel<Pair<IpQualitySource, IpQualityOutcome>>(Channel.UNLIMITED)
                val jobs = wave.map { source ->
                    launch {
                        val outcome = try {
                            withTimeoutOrNull(timeoutMillis) { transport.lookup(source, ip) }
                                ?: IpQualityOutcome.Failure(IpQualityFailure.timeout)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            IpQualityOutcome.Failure(IpQualityFailure.failed)
                        }
                        results.send(source to outcome)
                    }
                }
                try {
                    repeat(wave.size) {
                        val (source, outcome) = results.receive()
                        when (outcome) {
                            is IpQualityOutcome.Failure -> failures[source] = outcome.status
                            is IpQualityOutcome.Success -> {
                                if (!outcome.quality.inferred) return@waveScope outcome.quality
                                if (fallback == null) fallback = outcome.quality
                            }
                        }
                    }
                    null
                } finally {
                    jobs.forEach { it.cancel() }
                    results.cancel()
                }
            }
            if (winner != null) return@queryScope winner
        }
        fallback ?: throw IpQualityException(failures)
    }

    companion object {
        val waves = listOf(
            listOf(IpQualitySource.identMe, IpQualitySource.ipApiCom, IpQualitySource.ipQuery),
            listOf(IpQualitySource.ipLocate),
            listOf(IpQualitySource.ipApiIs),
            listOf(IpQualitySource.proxyCheck),
        )
    }
}
