package com.v2ray.ang.core.nowhere

import android.content.Context
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.NowhereFmt
import com.v2ray.ang.util.Utils
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One session owns all its Vector subprocesses, including per-rule nodes. */
internal class NowhereBridges(
    private val context: Context,
    private val onUnexpectedExit: () -> Unit = {},
) : Closeable {
    private val bridges = linkedMapOf<ProfileItem, Pair<NowhereProcess, ProfileItem>>()
    val isAlive: Boolean get() = bridges.values.all { it.first.isAlive }

    fun prepare(profile: ProfileItem): ProfileItem {
        if (profile.configType != EConfigType.NOWHERE) return profile
        return bridges.getOrPut(profile.duplicateIdentity()) {
            val endpoint = NowhereFmt.endpoint(profile)
            val port = Utils.findRandomFreePort()
            val username = UUID.randomUUID().toString()
            val password = UUID.randomUUID().toString()
            val process = NowhereProcess(context, onUnexpectedExit)
            try {
                process.start(endpoint.vectorUrl(port, username, password), port)
                process to ProfileItem(
                    configType = EConfigType.SOCKS,
                    remarks = profile.remarks,
                    server = AppConfig.LOOPBACK,
                    serverPort = port.toString(),
                    username = username,
                    password = password,
                )
            } catch (error: Exception) {
                process.close()
                throw error
            }
        }.second
    }

    override fun close() {
        bridges.values.forEach { it.first.close() }
        bridges.clear()
    }
}

internal class NowhereProcess(
    private val context: Context,
    private val onUnexpectedExit: () -> Unit = {},
) : Closeable {
    @Volatile private var process: Process? = null
    val isAlive: Boolean get() = process?.let { isProcessAlive(it) } == true

    fun start(vectorUrl: String, localPort: Int) {
        check(process == null)
        val builder = command(context, vectorUrl).redirectErrorStream(true)
        val started = builder.start()
        process = started
        // Drain redacted upstream diagnostics without retaining credentials or an unbounded log.
        Thread({
            runCatching { started.inputStream.bufferedReader().use { reader ->
                while (reader.readLine() != null) { }
            } }
            runCatching { started.waitFor() }
            if (process === started) onUnexpectedExit()
        }, "nowhere-output").apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            check(isProcessAlive(started)) { "Nowhere exited before its local listener became ready" }
            if (runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", localPort), 100) }
            }.isSuccess) return
            Thread.sleep(25)
        }
        error("Nowhere local listener timed out")
    }

    override fun close() {
        val previous = process ?: return
        process = null
        previous.destroy()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (isProcessAlive(previous) && System.nanoTime() < deadline) Thread.sleep(25)
        if (isProcessAlive(previous) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            previous.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
        }
    }

    companion object {
        private fun isProcessAlive(process: Process): Boolean = runCatching { process.exitValue() }.isFailure

        internal fun command(context: Context, vararg arguments: String): ProcessBuilder {
            val binary = File(context.applicationInfo.nativeLibraryDir, "libnowhere.so")
            check(binary.isFile && binary.canExecute()) { "Nowhere is unavailable for this CPU architecture" }
            return ProcessBuilder(listOf(binary.absolutePath) + arguments).apply {
                directory(context.filesDir)
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                environment()["NOW_TRANSPORT_MEMORY_PROFILE"] = "memory"
                environment()["SSL_CERT_DIR"] = listOf(
                    "/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts",
                ).filter { File(it).isDirectory }.joinToString(":")
            }
        }
    }
}
