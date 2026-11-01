package com.v2ray.ang.ipquality

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class IpQualityTransportTest {
    @Test
    fun cancellingCoroutineActuallyClosesTheHttpRequest(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) {}
                    accepted.countDown()
                    if (input.read() == -1) closed.countDown()
                }
            }
            val client = OkHttpClient()
            try {
                val transport = OkHttpIpQualityTransport(client) { _, _ -> "http://127.0.0.1:${server.localPort}/" }
                val job = launch { transport.lookup(IpQualitySource.ipQuery, "203.0.113.8") }
                assertTrue(withContext(Dispatchers.IO) { accepted.await(3, TimeUnit.SECONDS) })
                job.cancelAndJoin()
                assertTrue("Call.cancel must close the peer socket", withContext(Dispatchers.IO) { closed.await(3, TimeUnit.SECONDS) })
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
                worker.join(3000)
            }
        }
    }

    @Test
    fun transportPropagatesHttp429AndSuccessfulJson(): Unit = runBlocking {
        listOf(
            Triple("429 Too Many Requests", "{}", IpQualityOutcome.Failure(IpQualityFailure.rateLimited)),
            Triple("200 OK", """{"is_mobile":true,"company":{"name":"Carrier"}}""",
                IpQualityOutcome.Success(IpQuality(
                    "203.0.113.8", IpQualitySource.ipApiIs, IpType.mobile, organization = "Carrier",
                ))),
        ).forEach { (status, body, expected) ->
            ServerSocket(0).use { server ->
                val worker = thread(isDaemon = true) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) {}
                        socket.getOutputStream().use {
                            it.write("HTTP/1.1 $status\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        }
                    }
                }
                val client = OkHttpClient()
                try {
                    val transport = OkHttpIpQualityTransport(client) { _, _ -> "http://127.0.0.1:${server.localPort}/" }
                    assertEquals(expected, transport.lookup(IpQualitySource.ipApiIs, "203.0.113.8"))
                } finally {
                    client.dispatcher.executorService.shutdownNow()
                    client.connectionPool.evictAll()
                    worker.join(3000)
                }
            }
        }
    }
}
