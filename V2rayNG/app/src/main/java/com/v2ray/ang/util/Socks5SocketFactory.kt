package com.v2ray.ang.util

import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** Per-client SOCKS credentials, without changing the process-wide Java Authenticator. */
internal class Socks5SocketFactory(
    private val proxy: InetSocketAddress,
    private val username: String?,
    private val password: String?,
    private val handshakeTimeout: Int,
) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val target = endpoint as? InetSocketAddress ?: throw IOException("Invalid SOCKS destination")
            super.connect(proxy, timeout)
            val previousTimeout = soTimeout
            try {
                soTimeout = handshakeTimeout
                Socks5Handshake.connect(this, target.hostString, target.port, username, password)
            } catch (error: Exception) {
                close()
                throw error
            } finally {
                if (!isClosed) soTimeout = previousTimeout
            }
        }

        override fun connect(endpoint: SocketAddress?) = connect(endpoint, handshakeTimeout)
    }

    override fun createSocket(host: String, port: Int): Socket = createSocket().apply {
        connect(InetSocketAddress.createUnresolved(host, port), handshakeTimeout)
    }

    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostAddress!!, port)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress.createUnresolved(host, port), handshakeTimeout)
        }

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host.hostAddress!!, port, localHost, localPort)
}

internal object Socks5Handshake {
    fun connect(socket: Socket, host: String, port: Int, username: String?, password: String?) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val authenticated = username != null && password != null
        output.write(if (authenticated) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        output.flush()
        val greeting = input.readExact(2)
        if (greeting[0].toInt() != 5) throw IOException("Invalid SOCKS version")
        when (greeting[1].toInt()) {
            0 -> Unit
            2 -> {
                if (!authenticated) throw IOException("SOCKS credentials required")
                val user = username!!.toByteArray(Charsets.UTF_8)
                val pass = password!!.toByteArray(Charsets.UTF_8)
                if (user.size !in 1..255 || pass.size !in 1..255) throw IOException("Invalid SOCKS credentials")
                output.write(byteArrayOf(1, user.size.toByte()))
                output.write(user)
                output.write(pass.size)
                output.write(pass)
                output.flush()
                val auth = input.readExact(2)
                if (auth[0].toInt() != 1 || auth[1].toInt() != 0) throw IOException("SOCKS authentication failed")
            }
            else -> throw IOException("SOCKS authentication method rejected")
        }
        val domain = host.toByteArray(Charsets.UTF_8)
        if (domain.size !in 1..255 || port !in 1..65535) throw IOException("Invalid SOCKS destination")
        output.write(byteArrayOf(5, 1, 0, 3, domain.size.toByte()))
        output.write(domain)
        output.write(byteArrayOf((port ushr 8).toByte(), port.toByte()))
        output.flush()
        val reply = input.readExact(4)
        if (reply[0].toInt() != 5 || reply[1].toInt() != 0 || reply[2].toInt() != 0) {
            throw IOException("SOCKS connect rejected: ${reply[1].toInt() and 0xff}")
        }
        when (reply[3].toInt()) {
            1 -> input.readExact(4)
            3 -> input.readExact(input.read().also { if (it < 0) throw EOFException("Truncated SOCKS address") })
            4 -> input.readExact(16)
            else -> throw IOException("Invalid SOCKS address type")
        }
        input.readExact(2)
    }

    private fun java.io.InputStream.readExact(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read < 0) throw EOFException("Truncated SOCKS reply")
            offset += read
        }
        return bytes
    }
}
