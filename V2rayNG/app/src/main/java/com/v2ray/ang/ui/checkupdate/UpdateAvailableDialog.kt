package com.v2ray.ang.ui.checkupdate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.UpdateNotifications
import com.v2ray.ang.handler.VerifiedUpdateManager

@Composable
fun UpdateAvailableDialog(
    result: CheckUpdateResult,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val title = stringResource(R.string.update_new_version_found, result.latestVersion.orEmpty())
    val downloadLabel = stringResource(R.string.update_manual_download_browser)
    val progress by VerifiedUpdateManager.progress.collectAsStateWithLifecycle()
    val activeDownload = progress?.takeIf { VerifiedUpdateManager.sameDownload(it.result, result) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_check_update_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        title = {
            Text(title)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (result.isDownloaded) {
                    Text(
                        text = stringResource(R.string.update_package_downloaded, UpdateNotifications.downloadPath()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                activeDownload?.let { download ->
                    Text(stringResource(
                        if (download.verifying) R.string.update_verifying_package else R.string.update_download_in_progress,
                    ))
                    if (download.percent != null && !download.verifying) {
                        LinearProgressIndicator(
                            progress = { download.percent!! / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                UpdateReleaseNotes(result.releaseNotes.orEmpty())
                Text(
                    text = stringResource(R.string.update_verified_install_notice),
                    style = MaterialTheme.typography.bodySmall,
                )

                if (!result.isDownloaded) Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_cloud_download_24dp),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.update_proxy_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdate,
                enabled = activeDownload == null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (result.isDownloaded) R.drawable.ic_check_update_24dp else R.drawable.ic_cloud_download_24dp,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(when {
                    activeDownload != null -> R.string.update_download_in_progress
                    result.isDownloaded -> R.string.update_install
                    else -> R.string.update_now
                }))
            }
        },
        dismissButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        runCatching {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(
                                title, ReleaseNoteLinks.copyMessage(title, result.releaseNotes.orEmpty(), downloadLabel),
                            ))
                        }.onSuccess { context.toastSuccess(R.string.update_notes_copied) }
                            .onFailure { context.toastError(R.string.update_notes_copy_failed) }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.update_copy_notes)) }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
