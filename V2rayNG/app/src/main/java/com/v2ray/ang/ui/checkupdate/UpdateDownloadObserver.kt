package com.v2ray.ang.ui.checkupdate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.handler.VerifiedUpdateManager

@Composable
fun UpdateDownloadObserver() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, context) {
        // Only a resumed screen may open the installer; background completion uses its notification.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            VerifiedUpdateManager.completed.collect { UpdateInstallActivity.start(context, it) }
        }
    }
}
