package com.v2ray.ang.root

import java.util.concurrent.TimeUnit

/** Drain root output without unbounded allocation, and always terminate failed command processes. */
internal object RootProcessRunner {
    data class Result(val code: Int, val output: String)

    fun await(process: Process, timeout: Long, unit: TimeUnit): Result {
        val output = StringBuffer()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val chunk = CharArray(2048)
                    while (true) {
                        val size = input.read(chunk)
                        if (size < 0) break
                        synchronized(output) {
                            val remaining = 65_536 - output.length
                            if (remaining > 0) output.append(chunk, 0, minOf(size, remaining))
                        }
                    }
                }
            }
        }.apply { isDaemon = true; name = "root-shell-output"; start() }
        return try {
            if (!process.waitFor(timeout, unit)) {
                process.destroyForcibly()
                reader.join(1_000)
                Result(-1, output.toString())
            } else {
                reader.join(1_000)
                Result(process.exitValue(), output.toString())
            }
        } catch (interrupted: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (error: Exception) {
            process.destroyForcibly()
            throw error
        }
    }
}
