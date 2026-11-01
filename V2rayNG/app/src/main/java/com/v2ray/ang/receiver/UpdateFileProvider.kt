package com.v2ray.ang.receiver

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

class UpdateFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only update")
        return super.openFile(uri, mode) ?: throw FileNotFoundException("Update unavailable")
    }
}
