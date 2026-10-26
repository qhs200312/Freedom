package com.v2ray.ang.ui.routing

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig.BUILTIN_OUTBOUND_TAGS
import com.v2ray.ang.AppConfig.TAG_PROXY
import com.v2ray.ang.R
import com.v2ray.ang.core.RoutingTargets
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.apppicker.AppPickerActivity
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.verticalScrollbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val ROUTING_NETWORK_OPTIONS = listOf("tcp", "udp", "tcp,udp")

internal enum class RoutingMatchMode { DOMAINS, APPS, ADVANCED }

internal fun routingMatchMode(rule: RulesetItem?): RoutingMatchMode {
    if (rule == null) return RoutingMatchMode.DOMAINS
    val hasAdvanced = !rule.ip.isNullOrEmpty() || !rule.protocol.isNullOrEmpty() ||
        !rule.network.isNullOrBlank() || !rule.port.isNullOrBlank() ||
        (!rule.process.isNullOrEmpty() && !rule.domain.isNullOrEmpty())
    return when {
        hasAdvanced -> RoutingMatchMode.ADVANCED
        !rule.process.isNullOrEmpty() -> RoutingMatchMode.APPS
        else -> RoutingMatchMode.DOMAINS
    }
}

class RoutingEditActivity : BaseComponentActivity() {
    private val position by lazy { intent.getIntExtra("position", -1) }

    private var initial: RulesetItem? = null
    private lateinit var nodeOptions: List<RoutingNodeOption>
    private var canUseProcess: Boolean = false

    override fun onStorageReady(savedInstanceState: Bundle?) {
        super.onStorageReady(savedInstanceState)
        initial = SettingsManager.getRoutingRuleset(position)
        nodeOptions = loadRoutingNodeOptions(this)
        canUseProcess = SettingsManager.canUseProcessRouting()
    }

    @Composable
    override fun ScreenContent() {
        RoutingEditScreen(
            position = position,
            initial = initial,
            nodeOptions = nodeOptions,
            canUseProcess = canUseProcess,
            onBackClick = { finish() },
            onSave = { saveServer(it) },
            onDelete = { deleteServer() },
            initialAppRule = intent.getBooleanExtra("app_rule", false),
        )
    }

    private fun saveServer(rulesetItem: RulesetItem): Boolean {
        if (rulesetItem.remarks.isNullOrEmpty()) {
            toast(R.string.sub_setting_remarks)
            return false
        }
        if (position < 0 && rulesetItem.id.isEmpty()) {
            rulesetItem.id = UUID.randomUUID().toString()
        }
        SettingsManager.saveRoutingRuleset(position, rulesetItem)
        toastSuccess(R.string.toast_success)
        finish()
        return true
    }

    private fun deleteServer(): Boolean {
        if (position >= 0) {
            lifecycleScope.launch(Dispatchers.IO) {
                SettingsManager.removeRoutingRuleset(position)
                withContext(Dispatchers.Main) { finish() }
            }
        }
        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingEditScreen(
    position: Int,
    initial: RulesetItem?,
    nodeOptions: List<RoutingNodeOption>,
    canUseProcess: Boolean,
    onBackClick: () -> Unit,
    onSave: (RulesetItem) -> Boolean,
    onDelete: () -> Unit,
    initialAppRule: Boolean = false,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var remarks by rememberSaveable { mutableStateOf(initial?.remarks ?: "") }
    var locked by rememberSaveable { mutableStateOf(initial?.locked == true) }
    var domain by rememberSaveable { mutableStateOf(initial?.domain?.joinToString(",") ?: "") }
    var ip by rememberSaveable { mutableStateOf(initial?.ip?.joinToString(",") ?: "") }
    var processText by rememberSaveable { mutableStateOf(initial?.process?.joinToString(",") ?: "") }
    var protocol by rememberSaveable { mutableStateOf(initial?.protocol?.joinToString(",") ?: "") }
    var network by rememberSaveable { mutableStateOf(initial?.network ?: "") }
    var port by rememberSaveable { mutableStateOf(initial?.port ?: "") }
    var outboundTag by rememberSaveable {
        mutableStateOf(initial?.outboundTag ?: BUILTIN_OUTBOUND_TAGS.first())
    }
    var outboundProfileId by rememberSaveable { mutableStateOf(initial?.outboundProfileId) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var matchMode by rememberSaveable {
        mutableStateOf(if (initialAppRule) RoutingMatchMode.APPS else routingMatchMode(initial))
    }
    val selectedNetwork = network.ifBlank { "tcp,udp" }

    val processPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedPackages = AppPickerActivity.getSelectedPackages(result.data)
            processText = selectedPackages.joinToString(",")
            if (remarks.isBlank() && selectedPackages.isNotEmpty()) {
                remarks = routingApplicationNames(context, selectedPackages)
            }
        }
    }

    fun buildRuleset(): RulesetItem {
        val rulesetItem = SettingsManager.getRoutingRuleset(position) ?: RulesetItem()
        rulesetItem.apply {
            this.remarks = remarks
            this.locked = locked
            this.domain = if (matchMode == RoutingMatchMode.APPS) null else RoutingTargets.parseDomains(domain)
            this.ip = ip.takeIf { matchMode == RoutingMatchMode.ADVANCED }.nullIfBlank()
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
            this.process = processText.takeIf { matchMode != RoutingMatchMode.DOMAINS }.nullIfBlank()
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
            this.protocol = protocol.takeIf { matchMode == RoutingMatchMode.ADVANCED }.nullIfBlank()
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
            this.port = port.takeIf { matchMode == RoutingMatchMode.ADVANCED }.nullIfBlank()
            this.network = network.takeIf { matchMode == RoutingMatchMode.ADVANCED }.nullIfBlank()
            this.outboundTag = outboundTag.trim().ifEmpty { TAG_PROXY }
            this.outboundProfileId = outboundProfileId
        }
        return rulesetItem
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.routing_settings_rule_title),
                onBackClick = onBackClick,
                actions = {
                    if (position >= 0) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                painterResource(R.drawable.ic_delete_24dp),
                                contentDescription = stringResource(R.string.menu_item_del_config)
                            )
                        }
                    }
                    IconButton(onClick = {
                        val rule = buildRuleset()
                        if (matchMode == RoutingMatchMode.APPS && rule.process.isNullOrEmpty()) {
                            context.toast(R.string.routing_apps_required)
                        } else onSave(rule)
                    }) {
                        Icon(
                            painterResource(R.drawable.ic_fab_check),
                            contentDescription = stringResource(R.string.menu_item_save_config)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(scrollState)
                .verticalScrollbar(scrollState)
                .padding(vertical = 8.dp)
        ) {
            val modes = RoutingMatchMode.entries
            val modeTitles = listOf(R.string.routing_match_domains, R.string.routing_match_apps, R.string.routing_match_advanced)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = matchMode == mode,
                        onClick = { matchMode = mode },
                        enabled = mode != RoutingMatchMode.APPS || canUseProcess || !initial?.process.isNullOrEmpty(),
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) { Text(stringResource(modeTitles[index])) }
                }
            }
            FormTextField(
                label = stringResource(R.string.sub_setting_remarks),
                value = remarks,
                onValueChange = { remarks = it }
            )
            SettingsSwitchItem(
                title = stringResource(R.string.routing_settings_locked),
                checked = locked,
                onCheckedChange = { locked = it }
            )
            RoutingOutboundField(
                tag = outboundTag,
                profileId = outboundProfileId,
                nodes = nodeOptions,
                onSelected = { tag, id ->
                    outboundTag = tag
                    outboundProfileId = id
                },
            )
            if (matchMode != RoutingMatchMode.APPS) FormTextField(
                label = stringResource(R.string.routing_settings_domain),
                placeholder = stringResource(R.string.routing_domain_example),
                value = domain,
                onValueChange = { domain = it }
            )
            if (matchMode == RoutingMatchMode.ADVANCED) FormTextField(
                label = stringResource(R.string.routing_settings_ip),
                placeholder = stringResource(R.string.routing_settings_tips),
                value = ip,
                onValueChange = { ip = it }
            )
            if (matchMode == RoutingMatchMode.ADVANCED) FormTextField(
                label = stringResource(R.string.routing_settings_process),
                placeholder = stringResource(R.string.routing_settings_tips),
                value = processText,
                onValueChange = { processText = it },
                enabled = canUseProcess
            )
            if (matchMode != RoutingMatchMode.DOMAINS && canUseProcess) {
                val packages = processText.split(',').map(String::trim).filter(String::isNotEmpty)
                val applicationNames = remember(context, processText) { routingApplicationNames(context, packages) }
                SettingsMenuItem(
                    icon = painterResource(R.drawable.ic_per_apps_24dp),
                    title = stringResource(R.string.routing_settings_process_select),
                    subtitle = applicationNames.ifBlank { context.getString(R.string.routing_apps_empty) },
                    onClick = {
                        val current = processText
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        processPickerLauncher.launch(
                            AppPickerActivity.createIntent(
                                context = context,
                                selectedPackages = current,
                                title = context.getString(R.string.routing_settings_process_select)
                            )
                        )
                    },
                )
            } else if (matchMode != RoutingMatchMode.DOMAINS) {
                Text(
                    stringResource(R.string.routing_apps_unavailable),
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (matchMode == RoutingMatchMode.ADVANCED) FormTextField(
                label = stringResource(R.string.routing_settings_port),
                value = port,
                onValueChange = { port = it }
            )
            if (matchMode == RoutingMatchMode.ADVANCED) FormTextField(
                label = stringResource(R.string.routing_settings_protocol),
                placeholder = stringResource(R.string.routing_settings_protocol_tip),
                value = protocol,
                onValueChange = { protocol = it }
            )
            if (matchMode == RoutingMatchMode.ADVANCED) FormDropdownField(
                label = stringResource(R.string.routing_settings_network),
                value = selectedNetwork,
                options = ROUTING_NETWORK_OPTIONS,
                onValueChange = { network = it }
            )
            Spacer(modifier = Modifier.height(36.dp))
        }

        if (showDeleteConfirm) {
            DeleteConfirmDialog(
                message = stringResource(R.string.confirm_delete_routing_rule),
                onConfirm = onDelete,
                onDismiss = { showDeleteConfirm = false }
            )
        }
    }
}
