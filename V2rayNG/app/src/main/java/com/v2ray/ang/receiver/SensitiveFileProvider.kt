package com.v2ray.ang.receiver

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.v2ray.ang.handler.SensitiveCache
import java.io.FileNotFoundException

class SensitiveFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only file")
        val descriptor = super.openFile(uri, mode) ?: throw FileNotFoundException("File unavailable")
        val metadata = android.system.Os.fstat(descriptor.fileDescriptor)
        val age = System.currentTimeMillis() - metadata.st_mtime * 1000
        if (age < 0 || age >= SensitiveCache.MAX_AGE_MILLIS) {
            descriptor.close()
            context?.let { SensitiveCache.cleanup(it) }
            throw FileNotFoundException("Shared file has expired")
        }
        return descriptor
    }
}
