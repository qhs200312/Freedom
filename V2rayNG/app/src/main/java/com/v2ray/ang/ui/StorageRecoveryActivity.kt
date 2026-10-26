package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.R
import com.v2ray.ang.handler.SecureStorage
import com.v2ray.ang.handler.StorageRecovery
import com.v2ray.ang.ui.compose.AppTheme
import com.v2ray.ang.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StorageRecoveryActivity : ComponentActivity() {
    companion object {
        const val EXTRA_RESUME_INTENT = "storage_recovery_resume_intent"
    }

    private var busy by mutableStateOf(false)
    private var status by mutableStateOf(0)
    private var confirmExport by mutableStateOf(false)
    private var confirmReset by mutableStateOf(false)
    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri, "wt")
                        ?: error("Cannot open recovery destination")
                    output.use { StorageRecovery.exportRaw(this@StorageRecoveryActivity, it) }
                }
                status = R.string.storage_recovery_exported
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                status = R.string.storage_recovery_operation_failed
            } finally {
                busy = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SecureStorage.isUnavailable) {
            resumeApp()
            return
        }
        setContent {
            // Supplying the mode avoids ThemeManager and its unavailable MMKV preferences.
            AppTheme(darkTheme = isSystemInDarkTheme()) {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding)
                            .verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.storage_recovery_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(stringResource(R.string.storage_recovery_message))
                        if (status != 0) Text(stringResource(status))
                        Button(enabled = !busy, onClick = { retry() }) {
                            Text(stringResource(R.string.storage_recovery_retry))
                        }
                        OutlinedButton(enabled = !busy, onClick = { confirmExport = true }) {
                            Text(stringResource(R.string.storage_recovery_export))
                        }
                        TextButton(enabled = !busy, onClick = { confirmReset = true }) {
                            Text(stringResource(R.string.storage_recovery_reset))
                        }
                    }
                }
                if (confirmExport) {
                    AlertDialog(
                        onDismissRequest = { confirmExport = false },
                        title = { Text(stringResource(R.string.storage_recovery_export)) },
                        text = { Text(stringResource(R.string.storage_recovery_export_warning)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmExport = false
                                createDocument.launch("Freedom-storage-recovery-${System.currentTimeMillis()}.zip")
                            }) { Text(stringResource(android.R.string.ok)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmExport = false }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        },
                    )
                }
                if (confirmReset) {
                    AlertDialog(
                        onDismissRequest = { confirmReset = false },
                        title = { Text(stringResource(R.string.storage_recovery_reset)) },
                        text = { Text(stringResource(R.string.storage_recovery_reset_warning)) },
                        confirmButton = {
                            TextButton(onClick = { confirmReset = false; reset() }) {
                                Text(stringResource(android.R.string.ok))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmReset = false }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        },
                    )
                }
            }
        }
    }

    private fun retry() {
        lifecycleScope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { SecureStorage.initialize(this@StorageRecoveryActivity) }
                if (SecureStorage.isUnavailable) status = R.string.storage_recovery_retry_failed
                else resumeApp()
            } finally {
                busy = false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resumeApp() {
        if (!(application as AngApplication).initializeUiStorage()) return
        val original = intent.getParcelableExtra<Intent>(EXTRA_RESUME_INTENT)
        val resume = original?.takeIf { it.component?.packageName == packageName }
            ?: Intent(this, MainActivity::class.java)
        startActivity(resume.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun reset() {
        lifecycleScope.launch {
            busy = true
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    StorageRecovery.archiveAndReset(this@StorageRecoveryActivity)
                }
                finishAffinity()
                // A cold process discards cached MMKV handles belonging to the archived generation.
                Process.killProcess(Process.myPid())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                status = R.string.storage_recovery_operation_failed
            } finally {
                busy = false
            }
        }
    }
}
