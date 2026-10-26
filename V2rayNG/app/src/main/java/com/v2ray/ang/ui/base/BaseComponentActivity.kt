package com.v2ray.ang.ui.base

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SecureStorage
import com.v2ray.ang.ui.StorageRecoveryActivity
import com.v2ray.ang.ui.compose.AppTheme
import com.v2ray.ang.util.MyContextWrapper

abstract class BaseComponentActivity : ComponentActivity() {

    protected val storageUnavailable: Boolean
        get() = SecureStorage.isUnavailable

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(MyContextWrapper.wrap(newBase ?: return, SettingsManager.getLocale()))
    }

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SecureStorage.ensureAvailable(this)) {
            val recovery = Intent(this, StorageRecoveryActivity::class.java)
            recovery.putExtra(
                StorageRecoveryActivity.EXTRA_RESUME_INTENT,
                Intent(intent).setClass(this, javaClass).setPackage(packageName),
            )
            startActivity(recovery)
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            AppTheme {
                ScreenContent()
            }
        }
        onStorageReady(savedInstanceState)
    }

    protected open fun onStorageReady(savedInstanceState: Bundle?) = Unit

    @Composable
    protected abstract fun ScreenContent()
}
