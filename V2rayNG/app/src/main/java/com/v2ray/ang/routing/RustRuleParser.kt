package com.v2ray.ang.routing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object RustRuleParser {
    private val available = runCatching {
        System.getProperty("freedom.rules.library")?.let(System::load) ?: System.loadLibrary("freedom_rules")
    }.isSuccess
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "routing-rust-parser").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    @JvmStatic private external fun nativeCreate(): Long
    @JvmStatic private external fun nativeCancel(id: Long)
    @JvmStatic private external fun nativeRelease(id: Long)
    @JvmStatic private external fun nativeParseFile(path: String, id: Long): String?
    @JvmStatic private external fun nativeParsePacket(path: String, id: Long): ByteArray?
    @JvmStatic private external fun nativeSubscription(path: String, id: Long, encoded: Boolean): ByteArray?
    @JvmStatic private external fun nativeParseText(path: String, id: Long): Array<Any>?

    fun prepareSubscription(file: File, encoded: Boolean): NativePacket {
        val id = create()
        return try { packet(nativeSubscription(file.absolutePath, id, encoded)).expect(2) }
        finally { nativeRelease(id) }
    }

    private fun packet(bytes: ByteArray?) = NativePacket(
        bytes ?: throw YamlRuleImportException(YamlImportFailure.READ),
    )

    private fun create(): Long {
        if (!available) throw YamlRuleImportException(YamlImportFailure.READ, detail = "native parser unavailable")
        return nativeCreate().takeIf { it != 0L } ?: throw YamlRuleImportException(YamlImportFailure.BUSY)
    }

    fun parseBlocking(file: File, validateRegex: (String) -> Boolean = { true }): ParsedYamlRules {
        val id = create()
        return try { decode(nativeParseText(file.absolutePath, id), validateRegex) {} }
        finally { nativeRelease(id) }
    }

    suspend fun parseFile(file: File, validateRegex: (String) -> Boolean): ParsedYamlRules =
        suspendCancellableCoroutine { continuation ->
            val id = create()
            continuation.invokeOnCancellation { nativeCancel(id) }
            try {
                executor.execute {
                    try {
                        val parsed = decode(nativeParseText(file.absolutePath, id), validateRegex) {
                            if (!continuation.isActive) throw CancellationException("YAML import cancelled")
                        }
                        if (continuation.isActive) continuation.resume(parsed)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    } finally { nativeRelease(id) }
                }
            } catch (e: Exception) {
                nativeRelease(id)
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }

    private fun decode(parts: Array<Any>?, validateRegex: (String) -> Boolean, checkCancelled: () -> Unit): ParsedYamlRules {
        val input = NativeTextPacket(parts)
        val groups = Array(4) { input.strings(50_000, checkCancelled) }
        val regexCount = input.count(128)
        val started = System.nanoTime()
        repeat(regexCount) {
            val index = input.number()
            val sourceLine = input.number()
            if (index !in groups[0].indices) throw YamlRuleImportException(YamlImportFailure.READ)
            checkCancelled()
            if (System.nanoTime() - started > 3_000_000_000L) {
                throw YamlRuleImportException(YamlImportFailure.TIMEOUT, sourceLine)
            }
            if (!validateRegex(groups[0][index].removePrefix("regexp:"))) {
                throw YamlRuleImportException(YamlImportFailure.VALUE, sourceLine, "DOMAIN-REGEX")
            }
        }
        input.finish()
        return ParsedYamlRules(groups[0], groups[1], groups[2], groups[3])
    }
}
