package com.v2ray.ang.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small app-owned diagnostics, independent of OEM logcat settings. Never pass node credentials. */
object RuntimeDiagnostics {
    private val channels = listOf("core-runtime", "exit-ip")

    fun core(context: Context, message: String) = record(context, "core-runtime", message)
    fun exitIp(context: Context, message: String) = record(context, "exit-ip", message)

    @Synchronized
    private fun record(context: Context, channel: String, message: String) {
        runCatching {
            val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
            val file = File(directory, "$channel.log")
            if (file.length() > 128 * 1024) {
                file.copyTo(File(directory, "$channel.previous.log"), overwrite = true)
                file.writeText("")
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
            file.appendText("$timestamp $channel ${message.replace('\n', ' ')}\n")
        }
    }

    fun read(context: Context): List<String> = channels.flatMap { channel ->
        listOf("$channel.previous.log", "$channel.log").flatMap { name ->
            runCatching { File(context.filesDir, "diagnostics/$name").readLines() }.getOrDefault(emptyList())
        }
    }.sortedDescending()

    @Synchronized
    fun clear(context: Context) {
        channels.forEach { channel ->
            listOf("$channel.log", "$channel.previous.log").forEach { name ->
                runCatching { File(context.filesDir, "diagnostics/$name").delete() }
            }
        }
    }
}
