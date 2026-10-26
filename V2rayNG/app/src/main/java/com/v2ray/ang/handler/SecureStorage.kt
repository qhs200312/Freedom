package com.v2ray.ang.handler

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class StorageUnavailableException(cause: Exception? = null) :
    IllegalStateException("Configuration storage is unavailable", cause)

object SecureStorage {
    internal val storeIds = listOf(
        "MAIN", "PROFILE_FULL_CONFIG", "SERVER_RAW", "SERVER_AFF",
        "SUB", "ASSET", "SETTING", "NODE_TRAFFIC",
    )
    private const val KEY_ALIAS = "freedom.storage.v1"
    private data class State(val base: File, val root: File, val key: String)
    @Volatile private var state: State? = null
    @Volatile private var initializationFailure: Exception? = null
    @Volatile internal var restartRequired = false
        private set
    private var nativeInitialized = false
    private val processLock = Any()

    val isUnavailable: Boolean
        get() = initializationFailure != null || restartRequired || state?.let {
            File(File(it.base.parentFile, "${it.base.name}.control"), "reset").exists()
        } == true

    fun ensureAvailable(context: Context): Boolean {
        // A different process may have repaired the files since this process failed to open them.
        if (state == null && !restartRequired) initialize(context.applicationContext)
        return !isUnavailable
    }

    @Synchronized
    fun initialize(context: Context) {
        if (state != null) return
        try {
            check(!restartRequired) { "Restart is required after storage reset" }
            val base = File(context.noBackupFilesDir, "secure-storage")
            withLock(base) {
                check(!File(controlDirectory(base), "reset").exists()) { "Storage reset needs recovery" }
                check(base.isDirectory || base.mkdirs()) { "Cannot create storage directory" }
                MMKV.initialize(context)
                nativeInitialized = true
                val root = File(base, "stores-v1")
                val key = loadKey(base, root.exists())
                if (!root.exists()) migrate(File(MMKV.getRootDir()), base, root, key)
                check(File(root, "activated").isFile) { "Encrypted storage is incomplete" }
                recoverRestore(base, root)
                storeIds.forEach { id ->
                    if (File(MMKV.getRootDir(), id).exists()) MMKV.removeStorage(id, MMKV.getRootDir())
                }
                state = State(base, root, key)
                initializationFailure = null
            }
        } catch (error: Exception) {
            state = null
            initializationFailure = error
            Log.e(AppConfig.TAG, "Configuration storage unavailable (${error.javaClass.simpleName})")
        }
    }

    internal fun closeForReset() {
        check(isUnavailable) { "Healthy storage must not be reset" }
        if (nativeInitialized) {
            MMKV.onExit()
            nativeInitialized = false
        }
        state = null
        restartRequired = true
    }

    @JvmStatic
    fun open(id: String): MMKV {
        require(id in storeIds)
        val current = requireState()
        return openAt(id, current.root, current.key)
    }

    fun backupToDirectory(destination: File): Boolean {
        val current = requireState()
        return withLock(current.base) {
            withStoresLocked {
                destination.mkdirs()
                storeIds.all { id ->
                    if (!MMKV.backupOneToDirectory(id, destination.path, current.root.path)) {
                        false
                    } else {
                        val copy = openAt(id, destination, current.key)
                        try {
                            check(copy.reKey(null)) { "Cannot make portable backup" }
                            copy.sync()
                        } finally {
                            copy.close()
                        }
                        MMKV.isFileValid(id, destination.path)
                    }
                }
            }
        }
    }

    fun restoreFromDirectory(source: File): Boolean {
        val current = requireState()
        val incoming = storeIds.filter { File(source, it).isFile }
        if (incoming.isEmpty() || source.listFiles().orEmpty().any {
                it.name !in storeIds && it.name.removeSuffix(".crc") !in storeIds
            }
        ) return false
        if (incoming.any { !MMKV.isFileValid(it, source.path) }) return false

        return withLock(current.base) {
            withStoresLocked {
                // Encrypt disposable copies, never live data or the user's original archive.
                incoming.forEach { id ->
                    encryptCopy(id, source, current.key)
                    check(MMKV.isFileValid(id, source.path))
                }

                val journal = File(current.base, "restore")
                check(!journal.exists()) { "Previous restore needs recovery" }
                val rollback = File(journal, "rollback").apply { mkdirs() }
                try {
                    storeIds.forEach {
                        check(MMKV.backupOneToDirectory(it, rollback.path, current.root.path))
                    }
                    writeAtomically(File(journal, "prepared"), byteArrayOf(1))
                    syncDirectory(journal)
                    incoming.forEach {
                        check(MMKV.restoreOneMMKVFromDirectory(it, source.path, current.root.path))
                        open(it).sync()
                    }
                    writeAtomically(File(journal, "committed"), byteArrayOf(1))
                    syncDirectory(journal)
                    true
                } finally {
                    recoverRestore(current.base, current.root)
                }
            }
        }
    }

    private fun migrate(legacy: File, base: File, root: File, key: String) {
        val staging = File(base, "migration")
        if (staging.exists()) check(staging.deleteRecursively())
        check(staging.mkdirs())
        try {
            storeIds.forEach { id ->
                val exists = File(legacy, id).isFile
                if (exists) {
                    check(MMKV.isFileValid(id, legacy.path)) { "Legacy storage is damaged" }
                    check(MMKV.backupOneToDirectory(id, staging.path, legacy.path))
                }
                val copy = openAt(id, staging, null)
                val keys = copy.allKeys().orEmpty().toSet()
                copy.close()
                encryptCopy(id, staging, key)
                check(MMKV.isFileValid(id, staging.path))
                val verified = openAt(id, staging, key)
                try {
                    check(verified.allKeys().orEmpty().toSet() == keys) { "Migration lost keys" }
                } finally {
                    verified.close()
                }
            }
            writeAtomically(File(staging, "activated"), byteArrayOf(1))
            syncDirectory(staging)
            check(staging.renameTo(root)) { "Cannot activate encrypted storage" }
            syncDirectory(base)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun recoverRestore(base: File, root: File) {
        val journal = File(base, "restore")
        if (!journal.exists()) return
        if (File(journal, "prepared").isFile && !File(journal, "committed").isFile) {
            val rollback = File(journal, "rollback")
            check(storeIds.all {
                File(rollback, it).isFile && File(rollback, "$it.crc").isFile &&
                    MMKV.isFileValid(it, rollback.path)
            }) { "Restore rollback snapshot is incomplete or damaged" }
            storeIds.forEach {
                check(MMKV.restoreOneMMKVFromDirectory(it, rollback.path, root.path)) {
                    "Restore rollback needs recovery"
                }
            }
        }
        check(journal.deleteRecursively()) { "Cannot clean restore journal" }
    }

    private fun openAt(id: String, root: File, key: String?): MMKV =
        checkNotNull(MMKV.backedUpMMKVWithID(id, MMKV.MULTI_PROCESS_MODE, key, root.path)) {
            "Cannot open storage"
        }

    private fun encryptCopy(id: String, root: File, key: String) {
        val copy = openAt(id, root, null)
        val occupied: Long
        try {
            check(copy.reKey(key)) { "Cannot encrypt storage copy" }
            copy.sync()
            occupied = copy.actualSize() + 4L
        } finally {
            copy.close()
        }
        // MMKV 1.x reKey leaves old append history beyond its four-byte header and live payload.
        RandomAccessFile(File(root, id), "rw").use { file ->
            require(occupied in 4L..file.length())
            file.seek(occupied)
            val zeros = ByteArray(DEFAULT_BUFFER_SIZE)
            while (file.filePointer < file.length()) {
                file.write(zeros, 0, minOf(zeros.size.toLong(), file.length() - file.filePointer).toInt())
            }
            file.fd.sync()
        }
    }

    private fun <T> withStoresLocked(block: () -> T): T {
        val stores = storeIds.map(::open)
        val locked = mutableListOf<MMKV>()
        try {
            stores.forEach { it.lock(); locked.add(it) }
            return block()
        } finally {
            locked.asReversed().forEach { it.unlock() }
        }
    }

    private fun loadKey(base: File, alreadyEncrypted: Boolean): String {
        val wrapped = AtomicFile(File(base, "key"))
        val aliasFile = File(base, "key-alias")
        val alias = if (aliasFile.isFile) aliasFile.readText(Charsets.US_ASCII) else KEY_ALIAS
        require(alias == KEY_ALIAS || alias.matches(Regex("freedom\\.storage\\.v1\\.[0-9a-f-]{36}")))
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val exists = keyStore.containsAlias(alias)
        check(exists || (!wrapped.baseFile.exists() && !alreadyEncrypted)) {
            "The storage wrapping key is unavailable; encrypted data was preserved"
        }
        val wrappingKey = if (exists) {
            keyStore.getKey(alias, null) as SecretKey
        } else {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
            }.generateKey()
        }
        if (wrapped.baseFile.exists() || File(base, "key.bak").exists()) {
            val bytes = wrapped.readFully()
            require(bytes.size > 12 + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.US_ASCII)
                .also { require(it.length == 16) }
        }
        check(!alreadyEncrypted) { "The encrypted storage key is missing; data was preserved" }
        // MMKV 1.x accepts at most 16 UTF-8 bytes. Nonzero ASCII avoids truncation and JNI NULs.
        val random = SecureRandom()
        val secret = CharArray(16) { (1 + random.nextInt(127)).toChar() }.concatToString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        writeAtomically(wrapped.baseFile, cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.US_ASCII)))
        return secret
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try {
            android.system.Os.fsync(descriptor)
        } finally {
            android.system.Os.close(descriptor)
        }
    }

    private fun requireState(): State = state ?: throw StorageUnavailableException(initializationFailure)

    internal fun controlDirectory(base: File): File =
        File(base.parentFile, "${base.name}.control").apply {
            check(isDirectory || mkdirs()) { "Cannot create storage control directory" }
        }

    internal fun <T> withLock(base: File, block: () -> T): T = synchronized(processLock) {
        RandomAccessFile(File(controlDirectory(base), "lock"), "rw").use { file ->
            file.channel.lock().use { block() }
        }
    }
}
