package com.v2ray.ang.subscription

import com.v2ray.ang.dto.VmessQRCode
import com.v2ray.ang.routing.RustRuleParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.OutputStreamWriter

internal object SubscriptionBatchParser {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_NODES = 20_000

    fun writeBounded(reader: Reader, output: OutputStream) {
        var total = 0
        val limited = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > MAX_BYTES - total) throw IOException("Subscription exceeds 8 MiB")
                output.write(bytes, offset, length)
                total += length
            }
            override fun flush() = output.flush()
        }
        OutputStreamWriter(limited, Charsets.UTF_8).use { writer ->
            val buffer = CharArray(4096)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val count = reader.read(buffer)
                if (count < 0) break
                writer.write(buffer, 0, count)
            }
        }
    }

    fun copyBounded(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > MAX_BYTES - total) throw IOException("Subscription exceeds 8 MiB")
            output.write(buffer, 0, count)
            total += count
        }
    }

    fun <T> withInput(content: String, action: (File) -> T): T {
        require(content.length <= MAX_BYTES) { "Subscription exceeds 8 MiB" }
        val file = File.createTempFile("subscription-", ".txt")
        return try {
            file.outputStream().use { output -> content.reader().use { writeBounded(it, output) } }
            action(file)
        } finally { file.delete() }
    }

    fun forEach(file: File, encoded: Boolean, consume: (String, VmessQRCode?) -> Unit) {
        val packet = RustRuleParser.prepareSubscription(file, encoded)
        repeat(packet.count(MAX_NODES)) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val line = packet.string()
            val fields = packet.strings(18)
            check(fields.isEmpty() || fields.size == 18) { "Invalid native subscription record" }
            val vmess = fields.takeIf { it.isNotEmpty() }?.let {
                VmessQRCode(
                    ps = it[0], add = it[1], port = it[2], id = it[3], scy = it[4], net = it[5],
                    type = it[6], host = it[7], path = it[8], tls = it[9], sni = it[10], fp = it[11],
                    alpn = it[12], insecure = it[13], vcn = it[14], pcs = it[15], v = it[16], aid = it[17],
                )
            }
            consume(line, vmess)
        }
        packet.finish()
    }
}
