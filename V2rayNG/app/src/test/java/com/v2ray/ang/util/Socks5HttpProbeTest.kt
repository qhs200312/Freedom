package com.v2ray.ang.util

import com.v2ray.ang.dto.UrlContentRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Socks5HttpProbeTest {
    @Test
    fun handshakeSuccessWithoutHttpResponseIsNotReady() = withServer({ socket ->
        handshake(socket)
    }) { port ->
        assertFalse(HttpUtil.isUrlReachable(request(port)))
    }

    @Test
    fun remoteHostnameAndHttpResponseAreRequired() = withServer({ socket ->
        assertEquals("probe.invalid", handshake(socket))
        respondHttp(socket)
    }) { port ->
        assertTrue(HttpUtil.isUrlReachable(request(port)))
    }

    @Test
    fun socksAuthenticationIsScopedToThisClient() = withServer({ socket ->
        assertEquals("probe.invalid", handshake(socket, true))
        respondHttp(socket)
    }) { port ->
        assertTrue(HttpUtil.isUrlReachable(request(port).copy(proxyUsername = "user", proxyPassword = "secret")))
    }

    @Test
    fun truncatedSocksReplyFails() = withServer({ socket ->
        val input = DataInputStream(socket.getInputStream())
        input.readUnsignedByte()
        repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
        socket.getOutputStream().write(byteArrayOf(5))
    }) { port ->
        assertFalse(HttpUtil.isUrlReachable(request(port)))
    }

    private fun request(port: Int) = UrlContentRequest(
        url = "http://probe.invalid/", socksPort = port, timeout = 1_000,
    )

    private fun handshake(socket: Socket, authenticated: Boolean = false): String {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        assertEquals(5, input.readUnsignedByte())
        repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
        output.write(byteArrayOf(5, if (authenticated) 2 else 0))
        output.flush()
        if (authenticated) {
            assertEquals(1, input.readUnsignedByte())
            val user = ByteArray(input.readUnsignedByte()).also(input::readFully)
            val pass = ByteArray(input.readUnsignedByte()).also(input::readFully)
            assertEquals("user", user.toString(Charsets.UTF_8))
            assertEquals("secret", pass.toString(Charsets.UTF_8))
            output.write(byteArrayOf(1, 0))
            output.flush()
        }
        assertEquals(5, input.readUnsignedByte())
        assertEquals(1, input.readUnsignedByte())
        assertEquals(0, input.readUnsignedByte())
        assertEquals(3, input.readUnsignedByte())
        val host = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
        assertEquals(80, input.readUnsignedShort())
        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
        output.flush()
        return host
    }

    private fun respondHttp(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        assertTrue(reader.readLine().startsWith("GET / HTTP/1.1"))
        while (!reader.readLine().isNullOrEmpty()) { }
        socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        socket.getOutputStream().flush()
    }

    private fun withServer(handle: (Socket) -> Unit, check: (Int) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            val task = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    handle(socket)
                }
            }
            try {
                check(server.localPort)
                task.get(3, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
