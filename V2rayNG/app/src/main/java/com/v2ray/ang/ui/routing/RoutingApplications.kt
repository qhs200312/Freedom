package com.v2ray.ang.ui.routing

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R

internal fun routingApplicationNames(context: Context, packages: List<String>): String {
    val names = packages.distinct().take(3).map { packageName ->
        if (packageName == AppConfig.UNIDENTIFIED_PACKAGE) {
            context.getString(R.string.app_picker_unknown_app)
        } else {
            runCatching {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                info.loadLabel(context.packageManager).toString()
            }.getOrDefault(packageName)
        }
    }
    val remaining = packages.distinct().size - names.size
    return names.joinToString(", ") + if (remaining > 0) " (+$remaining)" else ""
}
