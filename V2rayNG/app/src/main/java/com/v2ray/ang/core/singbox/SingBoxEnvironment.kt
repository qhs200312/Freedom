package com.v2ray.ang.core.singbox

import android.content.Context
import go.Seq
import libbox.Libbox
import libbox.SetupOptions
import java.io.File
import java.util.UUID

internal object SingBoxEnvironment {
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        Seq.setContext(context.applicationContext)
        val base = context.applicationContext.filesDir
        val working = File(base, "sing-box").apply { mkdirs() }
        Libbox.setup(SetupOptions().apply {
            basePath = base.absolutePath
            workingPath = working.absolutePath
            tempPath = File(working, "tmp").absolutePath
            fixAndroidStack = true
            logMaxLines = 2000
            commandServerSecret = UUID.randomUUID().toString()
        })
        initialized = true
    }
}
