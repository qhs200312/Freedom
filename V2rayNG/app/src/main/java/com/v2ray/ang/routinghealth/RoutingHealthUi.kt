package com.v2ray.ang.routinghealth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Only visible/resumed screens request fresh checks; no background polling. */
@Composable
internal fun rememberRoutingHealth(): List<RoutingHealthStatus> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var statuses by remember { mutableStateOf(emptyList<RoutingHealthStatus>()) }
    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.getIntExtra("key", 0)) {
                        MSG_ROUTING_HEALTH_RESULT -> {
                            val snapshot = intent.getStringExtra("content")?.let {
                                JsonUtil.fromJsonSafe(it, RoutingHealthSnapshot::class.java)
                            }
                            if (snapshot != null) statuses = snapshot.statuses
                        }
                        AppConfig.MSG_STATE_STOP_SUCCESS, AppConfig.MSG_STATE_NOT_RUNNING,
                        AppConfig.MSG_STATE_START_FAILURE -> statuses = emptyList()
                        AppConfig.MSG_STATE_START_SUCCESS, AppConfig.MSG_STATE_RUNNING ->
                            MessageHelper.sendMsg2Service(context ?: return, MSG_ROUTING_HEALTH_REQUEST, "")
                    }
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                // Clear stale screen state if the service stopped while this screen was paused.
                statuses = emptyList()
                MessageHelper.sendMsg2Service(context, AppConfig.MSG_REGISTER_CLIENT, "")
                while (isActive) {
                    MessageHelper.sendMsg2Service(context, MSG_ROUTING_HEALTH_REQUEST, "")
                    delay(60_000)
                }
            } finally {
                context.unregisterReceiver(receiver)
            }
        }
    }
    return statuses
}

@Composable
internal fun RoutingHealthWarning(statuses: List<RoutingHealthStatus>) {
    val context = LocalContext.current
    var showDetails by remember { mutableStateOf(false) }
    val failures = statuses.filter { it.isUnavailable }
    if (failures.isNotEmpty()) {
        TextButton(
            onClick = { showDetails = true },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                stringResource(R.string.routing_health_connection_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text(stringResource(R.string.routing_health_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.routing_health_explanation), style = MaterialTheme.typography.bodySmall)
                if (statuses.isEmpty()) Text(stringResource(R.string.routing_health_disconnected))
                statuses.forEach { status ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(status.name, style = MaterialTheme.typography.titleSmall)
                        RoutingHealthLabel(status)
                        Text(stringResource(R.string.routing_health_rules, status.rules.joinToString("、")),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = statuses.isNotEmpty() && statuses.none { it.isChecking },
                onClick = { MessageHelper.sendMsg2Service(context, MSG_ROUTING_HEALTH_REQUEST, "refresh") },
            ) { Text(stringResource(R.string.routing_health_retry)) }
        },
        dismissButton = {
            TextButton(onClick = { showDetails = false }) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

@Composable
internal fun RoutingHealthLabel(status: RoutingHealthStatus?) {
    val state = status?.state ?: RoutingHealthState.unknown
    Text(
        stringResource(when (state) {
            RoutingHealthState.unknown -> R.string.routing_health_unknown
            RoutingHealthState.checking -> R.string.routing_health_checking
            RoutingHealthState.retrying -> R.string.routing_health_retrying
            RoutingHealthState.healthy -> R.string.routing_health_healthy
            RoutingHealthState.unavailable -> R.string.routing_health_unavailable
            RoutingHealthState.missing -> R.string.routing_health_missing
        }),
        color = when (state) {
            RoutingHealthState.unavailable, RoutingHealthState.missing, RoutingHealthState.retrying -> MaterialTheme.colorScheme.error
            RoutingHealthState.healthy -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.labelMedium,
    )
}
