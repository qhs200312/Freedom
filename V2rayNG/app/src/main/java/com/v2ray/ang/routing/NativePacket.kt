package com.v2ray.ang.routing

/** Versioned, length-prefixed UTF-8; no per-string JNI calls or JSON escaping. */
internal class NativePacket(private val bytes: ByteArray) {
    private var offset = 0
    private val kind: Int

    init {
        requirePacket(bytes.size <= MAX_BYTES)
        requirePacket(number() == 0x46525031)
        kind = number()
        if (kind == 0) {
            val failure = runCatching { YamlImportFailure.valueOf(string()) }
                .getOrElse { throw YamlRuleImportException(YamlImportFailure.READ) }
            val line = number()
            val detail = string()
            finish()
            throw YamlRuleImportException(failure, line, detail)
        }
    }

    fun expect(expected: Int): NativePacket = apply { requirePacket(kind == expected) }

    fun number(): Int {
        requirePacket(offset <= bytes.size - 4)
        val value = ((bytes[offset].toInt() and 255) shl 24) or
            ((bytes[offset + 1].toInt() and 255) shl 16) or
            ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
        offset += 4
        requirePacket(value >= 0)
        return value
    }

    fun count(limit: Int): Int = number().also {
        requirePacket(it <= limit && it <= (bytes.size - offset) / 4)
    }

    fun string(): String {
        val length = number()
        requirePacket(length <= bytes.size - offset)
        if (length == 0) return ""
        return String(bytes, offset, length, Charsets.UTF_8).also { offset += length }
    }

    fun strings(limit: Int = 100_000, checkCancelled: () -> Unit = {}): List<String> {
        val count = count(limit)
        return ArrayList<String>(count).apply {
            repeat(count) { checkCancelled(); add(string()) }
        }
    }

    fun finish() = requirePacket(offset == bytes.size)

    private fun requirePacket(ok: Boolean) {
        if (!ok) throw YamlRuleImportException(YamlImportFailure.READ, detail = "invalid native packet")
    }

    companion object { const val MAX_BYTES = 16 * 1024 * 1024 }
}
