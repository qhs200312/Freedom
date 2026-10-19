package com.v2ray.ang.ui.routing

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.compose.SettingsMenuItem

data class RoutingNodeOption(
    val id: String,
    val remarks: String,
    val group: String,
    val address: String = "",
)

internal fun loadRoutingNodeOptions(context: Context): List<RoutingNodeOption> =
    MmkvManager.decodeAllServerList().distinct().mapNotNull { id ->
        val profile = MmkvManager.decodeServerConfig(id) ?: return@mapNotNull null
        if (profile.configType == EConfigType.CUSTOM) return@mapNotNull null
        val local = profile.subscriptionId.isBlank() || profile.subscriptionId == AppConfig.DEFAULT_SUBSCRIPTION_ID
        RoutingNodeOption(
            id = id,
            remarks = profile.remarks,
            group = if (local) context.getString(R.string.subscription_group_local)
                else MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks.orEmpty(),
            address = if (profile.server.isNullOrBlank()) "" else profile.getServerAddressAndPort(),
        )
    }

@Composable
internal fun routingTargetLabel(tag: String, profileId: String?, nodes: List<RoutingNodeOption>): String {
    if (!profileId.isNullOrBlank()) {
        return nodes.firstOrNull { it.id == profileId }?.remarks
            ?: stringResource(R.string.routing_target_missing, tag)
    }
    return when (tag.ifBlank { AppConfig.TAG_PROXY }) {
        AppConfig.TAG_PROXY -> stringResource(R.string.routing_target_current)
        AppConfig.TAG_DIRECT -> stringResource(R.string.routing_target_direct)
        AppConfig.TAG_BLOCKED -> stringResource(R.string.routing_target_block)
        else -> tag
    }
}

@Composable
internal fun RoutingOutboundField(
    tag: String,
    profileId: String?,
    nodes: List<RoutingNodeOption>,
    onSelected: (String, String?) -> Unit,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    SettingsMenuItem(
        title = stringResource(R.string.routing_target_title),
        subtitle = routingTargetLabel(tag, profileId, nodes),
        onClick = { showPicker = true },
    )
    if (showPicker) {
        RoutingOutboundPicker(
            tag = tag,
            profileId = profileId,
            nodes = nodes,
            onSelected = { value, id ->
                onSelected(value, id)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun RoutingOutboundPicker(
    tag: String,
    profileId: String?,
    nodes: List<RoutingNodeOption>,
    onSelected: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val query = search.trim()
    val filtered = nodes.filter {
        query.isBlank() || it.remarks.contains(query, true) ||
            it.group.contains(query, true) || it.address.contains(query, true)
    }
    val selectedId = profileId ?: nodes.firstOrNull {
        tag !in AppConfig.BUILTIN_OUTBOUND_TAGS && it.remarks == tag
    }?.id
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routing_target_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(stringResource(R.string.menu_item_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(AppConfig.BUILTIN_OUTBOUND_TAGS.toList(), key = { "builtin-$it" }) { builtin ->
                        RoutingTargetRow(
                            name = routingTargetLabel(builtin, null, nodes),
                            detail = "",
                            selected = profileId.isNullOrBlank() && tag.ifBlank { AppConfig.TAG_PROXY } == builtin,
                            onClick = { onSelected(builtin, null) },
                        )
                    }
                    items(filtered, key = { it.id }) { node ->
                        RoutingTargetRow(
                            name = node.remarks,
                            detail = listOf(node.group, node.address).filter(String::isNotBlank).joinToString("\n"),
                            selected = selectedId == node.id,
                            onClick = { onSelected(node.remarks, node.id) },
                        )
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.routing_target_no_nodes),
                                modifier = Modifier.padding(vertical = 16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun RoutingTargetRow(name: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RadioButton(selected = selected, onClick = null)
    }
}
