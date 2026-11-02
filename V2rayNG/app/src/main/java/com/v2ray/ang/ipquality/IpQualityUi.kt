package com.v2ray.ang.ipquality

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import kotlinx.coroutines.CancellationException

@Composable
internal fun IpQualityPrivacySetting() {
    val hidden by IpQualityPlatform.hidden.collectAsStateWithLifecycle()
    SettingsSwitchItem(
        title = stringResource(R.string.ip_quality_hide),
        summary = stringResource(R.string.ip_quality_hide_summary),
        checked = hidden,
        onCheckedChange = IpQualityPlatform::setHidden,
    )
}

@Composable
internal fun IpQualityPanel(
    exitIp: String,
    useProxy: Boolean,
    repositoryOverride: IpQualityRepository? = null,
    modifier: Modifier = Modifier,
    addressContent: (@Composable () -> Unit)? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hidden by IpQualityPlatform.hidden.collectAsStateWithLifecycle()
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var target by remember(exitIp, useProxy) { mutableStateOf(exitIp) }
    var draft by remember(exitIp, useProxy) { mutableStateOf(exitIp) }
    var refresh by remember { mutableStateOf(0) }
    var consumedRefresh by remember { mutableStateOf(0) }
    var invalid by remember { mutableStateOf(false) }
    var quality by remember(target, useProxy) { mutableStateOf<IpQuality?>(null) }
    var exitQuality by remember(exitIp, useProxy) { mutableStateOf<IpQuality?>(null) }
    var failure by remember(target, useProxy) { mutableStateOf<IpQualityException?>(null) }
    var loading by remember(target, useProxy) { mutableStateOf(false) }
    val repository = repositoryOverride ?: remember(useProxy) { IpQualityPlatform.repositoryFor(useProxy) }

    LaunchedEffect(target, useProxy, refresh, lifecycle) {
        val queryIp = IpQualityAddress.normalize(target) ?: return@LaunchedEffect
        val queryingExit = queryIp == IpQualityAddress.normalize(exitIp)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            loading = true
            failure = null
            try {
                quality = repository.get(queryIp, refresh > consumedRefresh)
                if (queryingExit) exitQuality = quality
                consumedRefresh = refresh
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IpQualityException) {
                consumedRefresh = refresh
                failure = error
            } finally {
                loading = false
            }
            // repeatOnLifecycle suspends after this block; it is not a polling loop.
        }
    }

    val currentExit = IpQualityAddress.normalize(target) == IpQualityAddress.normalize(exitIp)
    val detailsLabel = stringResource(R.string.ip_quality_title)
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (addressContent != null) addressContent() else {
            Text(if (hidden) "*****" else exitIp.ifBlank { "-" }, color = MaterialTheme.colorScheme.onSurface)
        }
        TextButton(
            onClick = { showDetails = true },
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = detailsLabel },
        ) {
            when {
                currentExit && loading -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                currentExit && failure != null -> Text(
                    stringResource(R.string.ip_quality_failed), style = MaterialTheme.typography.bodySmall,
                )
                exitQuality != null -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(exitQuality!!.level.label()),
                        color = gradeColor(exitQuality!!.level),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        typeText(exitQuality!!),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                else -> Text(stringResource(R.string.ip_quality_check), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    fun closeDetails() {
        showDetails = false
        target = exitIp
        draft = exitIp
        invalid = false
    }

    if (showDetails) AlertDialog(
        onDismissRequest = ::closeDetails,
        title = { Text(stringResource(R.string.ip_quality_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.ip_quality_explanation), style = MaterialTheme.typography.bodySmall)
                quality?.let { IpQualityResult(it, hidden) }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; invalid = false },
                    label = { Text(stringResource(R.string.ip_quality_target)) },
                    singleLine = true, isError = invalid,
                    visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (invalid) Text(stringResource(R.string.ip_quality_invalid), color = MaterialTheme.colorScheme.error)
                if (target != exitIp && exitIp.isNotBlank()) {
                    TextButton(onClick = { draft = exitIp; target = exitIp }) {
                        Text(stringResource(R.string.ip_quality_current_exit))
                    }
                }
                IpQualityPrivacySetting()
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                failure?.let { error ->
                    Text(
                        stringResource(if (error.retryAt != null) R.string.ip_quality_cooldown else R.string.ip_quality_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    error.failures.forEach { (source, status) -> Text("${source.name}: ${stringResource(status.label())}") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalized = IpQualityAddress.normalize(draft)
                    if (normalized == null) invalid = true else {
                        target = normalized
                        refresh++
                    }
                },
                enabled = !loading,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.ip_quality_query)) }
        },
        dismissButton = { TextButton(onClick = ::closeDetails) { Text(stringResource(android.R.string.ok)) } },
    )
}

@Composable
private fun IpQualityResult(quality: IpQuality, hidden: Boolean) {
    Text(if (hidden) "*****" else quality.ip, style = MaterialTheme.typography.titleMedium)
    IpQualityClassification(quality)
    if (quality.inferred) Text(stringResource(R.string.ip_quality_inferred), style = MaterialTheme.typography.bodySmall)
    if (quality.typeNeedsConfirmation) Text(
        stringResource(R.string.ip_quality_confirmation_note), style = MaterialTheme.typography.bodySmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            quality.isProxy to R.string.ip_quality_proxy, quality.isVpn to R.string.ip_quality_vpn,
            quality.isTor to R.string.ip_quality_tor, quality.isAbuser to R.string.ip_quality_abuser,
        ).filter { it.first }.forEach { (_, label) ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(stringResource(label), modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
    Text(stringResource(R.string.ip_quality_organization, quality.organization ?: "-"))
    Text(stringResource(R.string.ip_quality_asn, quality.asn ?: "-"))
    Text(stringResource(R.string.ip_quality_source, quality.source.name))
}

@Composable
private fun IpQualityClassification(quality: IpQuality) {
    val level = stringResource(quality.level.label())
    Text(
        stringResource(
            R.string.ip_quality_type,
            typeText(quality),
        ),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        stringResource(
            R.string.ip_quality_grade,
            if (quality.inferred) stringResource(R.string.ip_quality_inferred_value, level) else level,
        ),
        color = gradeColor(quality.level),
    )
}

@Composable
private fun typeText(quality: IpQuality): String {
    val type = stringResource(quality.type.label())
    return when {
        quality.typeNeedsConfirmation -> stringResource(R.string.ip_quality_unconfirmed_value, type)
        quality.inferred -> stringResource(R.string.ip_quality_inferred_value, type)
        else -> type
    }
}

@Composable
private fun gradeColor(level: IpQualityLevel): androidx.compose.ui.graphics.Color {
    val colors = MaterialTheme.colorScheme
    val semantic = when (level) {
        IpQualityLevel.good -> colors.tertiary
        IpQualityLevel.normal -> colors.onSurface
        IpQualityLevel.risky -> colors.error
    }
    // Preserve the theme's semantic hue while making small grade labels readable.
    for (step in 0..10) {
        val candidate = lerp(semantic, colors.onSurface, step / 10f)
        if (ColorUtils.calculateContrast(candidate.toArgb(), colors.surface.toArgb()) >= 4.5 &&
            ColorUtils.calculateContrast(candidate.toArgb(), colors.surfaceContainerHigh.toArgb()) >= 4.5) return candidate
    }
    return colors.onSurface
}

private fun IpQualityLevel.label() = when (this) {
    IpQualityLevel.good -> R.string.ip_quality_good
    IpQualityLevel.normal -> R.string.ip_quality_normal
    IpQualityLevel.risky -> R.string.ip_quality_risky
}
private fun IpType.label() = when (this) {
    IpType.residential -> R.string.ip_quality_residential
    IpType.mobile -> R.string.ip_quality_mobile
    IpType.business -> R.string.ip_quality_business
    IpType.hosting -> R.string.ip_quality_hosting
}
private fun IpQualityFailure.label() = when (this) {
    IpQualityFailure.noType -> R.string.ip_quality_no_type
    IpQualityFailure.timeout -> R.string.ip_quality_timeout
    IpQualityFailure.rateLimited -> R.string.ip_quality_rate_limited
    IpQualityFailure.failed -> R.string.ip_quality_failed
    IpQualityFailure.ipMismatch -> R.string.ip_quality_mismatch
}
