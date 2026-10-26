package com.v2ray.ang.handler

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom

object TaskerAuthorization {
    const val EXTRA_TOKEN = "tasker_authorization_v1"
    private val lock = Any()

    fun issue(context: Context, existing: String?): String = synchronized(lock) {
        withRegistry(context) { file, hashes ->
            if (existing != null && matches(existing, hashes)) return@withRegistry existing
            val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val updated = hashes + digest(token)
            val output = file.startWrite()
            try {
                output.write(updated.joinToString("\n").toByteArray(Charsets.US_ASCII))
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
            token
        }
    }

    fun isAuthorized(context: Context, token: String?): Boolean = synchronized(lock) {
        if (token == null || !token.matches(Regex("[0-9a-f]{64}"))) return false
        withRegistry(context) { _, hashes -> matches(token, hashes) }
    }

    private fun matches(token: String, hashes: Set<String>): Boolean {
        if (!token.matches(Regex("[0-9a-f]{64}"))) return false
        val candidate = digest(token).toByteArray(Charsets.US_ASCII)
        return hashes.any { MessageDigest.isEqual(candidate, it.toByteArray(Charsets.US_ASCII)) }
    }

    private fun digest(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.US_ASCII))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun <T> withRegistry(context: Context, block: (AtomicFile, Set<String>) -> T): T {
        val directory = File(context.noBackupFilesDir, "tasker-authorization").apply { mkdirs() }
        return RandomAccessFile(File(directory, "lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val file = AtomicFile(File(directory, "tokens"))
                val hashes = if (file.baseFile.exists() || File(directory, "tokens.bak").exists()) {
                    file.readFully().toString(Charsets.US_ASCII).lineSequence()
                        .filter { it.matches(Regex("[0-9a-f]{64}")) }.toSet()
                } else emptySet()
                block(file, hashes)
            }
        }
    }
}
