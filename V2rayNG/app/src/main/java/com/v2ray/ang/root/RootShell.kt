package com.v2ray.ang.root

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal root command runner backed by the `su` binary.
 *
 * Scripts are written to the app's private root runtime dir and executed with
 * `su -c sh <file>` so shell quoting stays simple. stderr is merged into stdout to
 * avoid pipe-buffer deadlocks.
 */
object RootShell {

    data class Result(val code: Int, val output: String) {
        val success: Boolean get() = code == 0
    }

    /** Write [script] to `<filesDir>/root/<name>` and run it as root. */
    fun runScript(context: Context, name: String, script: String): Result {
        val dir = File(context.filesDir, AppConfig.ROOT_RUNTIME_DIR).apply { mkdirs() }
        val file = File(dir, name).apply {
            writeText(script)
            setExecutable(true, false)
        }
        val safePath = file.absolutePath.replace("'", "'\\''")
        return exec("sh '$safePath'")
    }

    private fun exec(command: String, timeoutSeconds: Long = 30): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val outputReader = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { output.appendLine(it) }
                }
            }.apply {
                name = "root-shell-output"
                isDaemon = true
                start()
            }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                outputReader.join(1000L)
                LogUtil.e(AppConfig.TAG, "RootShell: timed out: $command")
                return Result(-1, output.toString())
            }
            outputReader.join(1000L)
            val result = Result(process.exitValue(), output.toString())
            if (!result.success) {
                LogUtil.w(AppConfig.TAG, "RootShell: '$command' exited ${result.code}: ${output.trim()}")
            }
            result
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "RootShell: failed to run '$command'", e)
            Result(-1, e.message ?: e.javaClass.simpleName)
        }
    }
}
