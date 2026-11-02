package com.v2ray.ang.routing

/** Bulk UTF-16 avoids a charset decoder and four byte reads for every returned string. */
internal class NativeTextPacket(parts: Array<Any>?) {
    private val numbers: IntArray
    private val text: CharArray
    private var index = 0
    private var offset = 0

    init {
        requirePacket(parts?.size == 2)
        numbers = parts!![0] as? IntArray ?: throw YamlRuleImportException(YamlImportFailure.READ)
        text = parts[1] as? CharArray ?: throw YamlRuleImportException(YamlImportFailure.READ)
        requirePacket(numbers.size <= 100_000 && text.size <= 2 * 1024 * 1024)
        requirePacket(number() == 0x46525431)
        when (number()) {
            0 -> {
                val failure = runCatching { YamlImportFailure.valueOf(string()) }
                    .getOrElse { throw YamlRuleImportException(YamlImportFailure.READ) }
                val line = number()
                val detail = string()
                finish()
                throw YamlRuleImportException(failure, line, detail)
            }
            1 -> Unit
            else -> throw YamlRuleImportException(YamlImportFailure.READ)
        }
    }

    fun number(): Int {
        requirePacket(index < numbers.size)
        return numbers[index++].also { requirePacket(it >= 0) }
    }

    fun count(limit: Int): Int = number().also { requirePacket(it <= limit && it <= numbers.size - index) }

    private fun string(): String {
        val length = number()
        requirePacket(length <= text.size - offset)
        if (length == 0) return ""
        return String(text, offset, length).also { offset += length }
    }

    fun strings(limit: Int, checkCancelled: () -> Unit): List<String> {
        val count = count(limit)
        return ArrayList<String>(count).apply {
            repeat(count) { checkCancelled(); add(string()) }
        }
    }

    fun finish() = requirePacket(index == numbers.size && offset == text.size)

    private fun requirePacket(ok: Boolean) {
        if (!ok) throw YamlRuleImportException(YamlImportFailure.READ, detail = "invalid native text packet")
    }
}
