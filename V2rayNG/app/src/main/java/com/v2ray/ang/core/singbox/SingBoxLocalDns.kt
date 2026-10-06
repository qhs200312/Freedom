package com.v2ray.ang.core.singbox

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import libbox.ExchangeContext
import libbox.LocalDNSTransport
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Bootstrap/system DNS must use the physical upstream, never the VPN's own resolver. */
internal class SingBoxLocalDns(private val network: () -> Network?) : LocalDNSTransport {
    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val upstream = network() ?: error("No physical network for local DNS")
        val completed = CountDownLatch(1)
        val cancellation = CancellationSignal()
        val response = AtomicReference<ByteArray?>()
        val failure = AtomicReference<Exception?>()
        var rcode = 0
        ctx.onCancel {
            failure.compareAndSet(null, CancellationException("DNS exchange cancelled"))
            cancellation.cancel()
            completed.countDown()
        }
        try {
            DnsResolver.getInstance().rawQuery(
                upstream, message, DnsResolver.FLAG_NO_RETRY, Executor { it.run() }, cancellation,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, code: Int) {
                        response.set(answer)
                        rcode = code
                        completed.countDown()
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        failure.compareAndSet(null, error)
                        completed.countDown()
                    }
                },
            )
            check(completed.await(10, TimeUnit.SECONDS)) { "Physical network DNS timed out" }
            failure.get()?.let { throw it }
            if (rcode == 0) ctx.rawSuccess(response.get() ?: error("Missing DNS response"))
            else ctx.errorCode(rcode)
        } finally {
            cancellation.cancel()
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val upstream = this.network() ?: error("No physical network for local DNS")
        val addresses = upstream.getAllByName(domain).filter {
            when {
                network.endsWith("4") -> it is Inet4Address
                network.endsWith("6") -> it is Inet6Address
                else -> true
            }
        }
        ctx.success(addresses.mapNotNull { it.hostAddress }.joinToString("\n"))
    }
}
