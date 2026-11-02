package com.v2ray.ang.ui.routing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.routing.YamlImportFailure
import com.v2ray.ang.routing.YamlRuleImportException

@Composable
internal fun RoutingYamlImportDialog(
    state: RoutingYamlImportState,
    nodes: List<RoutingNodeOption>,
    onInputChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onPaste: () -> Unit,
    onPickFile: () -> Unit,
    onPreview: () -> Unit,
    onImport: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var outbound by rememberSaveable { mutableStateOf(AppConfig.TAG_PROXY) }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    AlertDialog(
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(stringResource(R.string.routing_yaml_import)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.routing_yaml_description), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    label = { Text(stringResource(R.string.routing_yaml_input)) },
                    placeholder = { Text("payload:\n  - DOMAIN-SUFFIX,example.com") },
                    enabled = !state.loading,
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPaste, enabled = !state.loading) {
                        Text(stringResource(R.string.routing_yaml_paste))
                    }
                    TextButton(onClick = onPickFile, enabled = !state.loading) {
                        Text(stringResource(R.string.routing_yaml_file))
                    }
                }
                state.sourceFile?.let {
                    Text(stringResource(R.string.routing_yaml_selected_file, it), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = state.name, onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.sub_setting_remarks)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RoutingOutboundField(
                    tag = outbound, profileId = profileId, nodes = nodes,
                    onSelected = { tag, id -> outbound = tag; profileId = id },
                )
                if (state.loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.routing_yaml_loading))
                }
                state.error?.let {
                    Text(yamlImportError(it), color = MaterialTheme.colorScheme.error)
                }
                state.parsed?.let { parsed ->
                    Text(
                        stringResource(
                            R.string.routing_yaml_preview,
                            parsed.count, parsed.domains.size, parsed.ips.size, parsed.ports.size, parsed.networks.size,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.routing_yaml_priority), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    keyboard?.hide()
                    if (state.parsed == null) onPreview() else onImport(outbound, profileId)
                },
                enabled = !state.loading && (state.input.isNotBlank() || state.parsed != null),
            ) {
                Text(stringResource(if (state.parsed == null) R.string.routing_yaml_check else R.string.routing_yaml_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.saving) { Text(stringResource(android.R.string.cancel)) }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun yamlImportError(error: YamlRuleImportException): String = when (error.failure) {
    YamlImportFailure.FORMAT -> stringResource(R.string.routing_yaml_error_format, error.line)
    YamlImportFailure.UNSUPPORTED -> stringResource(R.string.routing_yaml_error_unsupported, error.line, error.detail)
    YamlImportFailure.VALUE -> stringResource(R.string.routing_yaml_error_value, error.line, error.detail)
    YamlImportFailure.EMPTY -> stringResource(R.string.routing_yaml_error_empty)
    YamlImportFailure.TOO_LARGE -> stringResource(R.string.routing_yaml_error_size)
    YamlImportFailure.URL -> stringResource(R.string.routing_yaml_error_url)
    YamlImportFailure.HTTP -> stringResource(R.string.routing_yaml_error_http, error.detail)
    YamlImportFailure.READ -> stringResource(R.string.routing_yaml_error_read)
    YamlImportFailure.TIMEOUT -> stringResource(R.string.routing_yaml_error_timeout)
    YamlImportFailure.CANCELLED -> stringResource(R.string.routing_yaml_error_cancelled)
    YamlImportFailure.BUSY -> stringResource(R.string.routing_yaml_error_busy)
    YamlImportFailure.PASTE_TOO_LARGE -> stringResource(R.string.routing_yaml_error_paste)
}
