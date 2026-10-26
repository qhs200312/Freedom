package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil

class UrlSchemeActivity : BaseComponentActivity() {

    override fun onStorageReady(savedInstanceState: Bundle?) {
        super.onStorageReady(savedInstanceState)
        try {
            intent.apply {
                if (action == Intent.ACTION_SEND) {
                    if ("text/plain" == type) {
                        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                            parseUri(it, null)
                        }
                    }
                } else if (action == Intent.ACTION_VIEW) {
                    when (data?.host) {
                        "install-config" -> {
                            val uri: Uri? = intent.data
                            val shareUrl = uri?.getQueryParameters("url")?.firstOrNull().orEmpty()
                            parseUri(shareUrl, uri?.fragment)
                        }

                        "install-sub" -> {
                            val uri: Uri? = intent.data
                            val shareUrl = uri?.getQueryParameters("url")?.firstOrNull().orEmpty()
                            parseUri(shareUrl, uri?.fragment)
                        }

                        else -> {
                            toastError(R.string.toast_failure)
                        }
                    }
                }
            }

            finish()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing URL scheme", e)
            finish()
        }
    }

    @Composable
    override fun ScreenContent() {
    }

    private fun parseUri(uriString: String?, fragment: String?) {
        if (uriString.isNullOrEmpty()) {
            return
        }
        // Query values are already decoded; preserve escapes inside complete proxy URLs.
        val decodedUrl = if (uriString.contains("://")) uriString else Uri.decode(uriString)
        val uri = Uri.parse(decodedUrl)
        val finalUrl = if (uri.fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
            "$decodedUrl#$fragment"
        } else decodedUrl
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_IMPORT_TEXT, finalUrl)
        )
    }
}
