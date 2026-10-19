package com.v2ray.ang.handler

import java.util.concurrent.TimeUnit
import kotlin.math.ceil

internal object IcmpPing {
    fun measure(host: String): Long {
        val address = host.trim().removeSurrounding("[", "]")
        if (address.isBlank() || address.startsWith("-")) return -1L
        val process = runCatching {
            ProcessBuilder("/system/bin/ping", "-n", "-c", "1", "-W", "2", "-w", "3", address)
                .redirectErrorStream(true).start()
        }.getOrNull() ?: return -1L
        return try {
            if (!process.waitFor(4, TimeUnit.SECONDS)) return -1L
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.exitValue() == 0) parseReply(output) else -1L
        } finally {
            process.destroy()
            process.inputStream.close()
        }
    }

    fun parseReply(output: String): Long {
        val milliseconds = Regex("""time[=<]\s*(\d+(?:\.\d+)?)\s*ms""")
            .find(output)?.groupValues?.get(1)?.toDoubleOrNull() ?: return -1L
        return ceil(milliseconds).toLong().coerceAtLeast(1L)
    }
}
