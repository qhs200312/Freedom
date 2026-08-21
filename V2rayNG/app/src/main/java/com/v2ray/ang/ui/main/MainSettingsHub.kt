package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.enums.ProxyMode

private data class SettingsHubItem(
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    val route: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSettingsHub(
    uiState: MainUiState,
    onAction: (MainAction) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentBottomPadding: Dp = 24.dp,
) {
    LazyColumn(
        modifier = modifier.statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 22.dp,
            end = 16.dp,
            bottom = contentBottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.main_nav_settings),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        item {
            ProxyModeCard(
                uiState = uiState,
                onModeSelected = { onAction(MainAction.SetProxyMode(it)) },
            )
        }
        item {
            SettingsHubSection(
                titleRes = R.string.settings_section_network,
                accent = MaterialTheme.colorScheme.secondary,
                items = listOf(
                    SettingsHubItem(R.drawable.ic_subscriptions_24dp, R.string.title_sub_setting, "sub_setting"),
                    SettingsHubItem(R.drawable.ic_routing_24dp, R.string.routing_settings_title, "routing_setting"),
                    SettingsHubItem(R.drawable.ic_per_apps_24dp, R.string.per_app_proxy_settings, "per_app_proxy"),
                ),
                onNavigate = onNavigate,
            )
        }
        item {
            SettingsHubSection(
                titleRes = R.string.settings_section_application,
                accent = MaterialTheme.colorScheme.tertiary,
                items = listOf(
                    SettingsHubItem(R.drawable.ic_settings_24dp, R.string.title_settings, "settings"),
                    SettingsHubItem(R.drawable.ic_file_24dp, R.string.title_user_asset_setting, "user_asset"),
                    SettingsHubItem(
                        R.drawable.ic_restore_24dp,
                        R.string.title_configuration_backup_restore,
                        "backup_restore",
                    ),
                ),
                onNavigate = onNavigate,
            )
        }
        item {
            SettingsHubSection(
                titleRes = R.string.settings_section_support,
                accent = MaterialTheme.colorScheme.primary,
                items = listOf(
                    SettingsHubItem(R.drawable.ic_logcat_24dp, R.string.title_logcat, "logcat"),
                    SettingsHubItem(R.drawable.ic_check_update_24dp, R.string.update_check_for_update, "check_update"),
                ),
                onNavigate = onNavigate,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyModeCard(
    uiState: MainUiState,
    onModeSelected: (ProxyMode) -> Unit,
) {
    val modes = ProxyMode.entries
    val selectorEnabled = !uiState.isRunning && !uiState.isCheckingRoot

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_proxy_mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (uiState.isCheckingRoot) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = uiState.proxyMode == mode,
                        onClick = { onModeSelected(mode) },
                        enabled = selectorEnabled,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(
                                when (mode) {
                                    ProxyMode.STANDARD -> R.string.home_mode_standard
                                    ProxyMode.ROOT_TUN -> R.string.home_mode_root_tun
                                    ProxyMode.TPROXY -> R.string.home_mode_tproxy
                                },
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (!selectorEnabled) {
                Text(
                    text = stringResource(
                        if (uiState.isCheckingRoot) R.string.home_root_checking
                        else R.string.home_mode_disconnect_to_change,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsHubSection(
    @StringRes titleRes: Int,
    accent: Color,
    items: List<SettingsHubItem>,
    onNavigate: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    SettingsHubRow(
                        item = item,
                        accent = accent,
                        onClick = { onNavigate(item.route) },
                    )
                    if (index != items.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 66.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHubRow(
    item: SettingsHubItem,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(accent.copy(alpha = 0.13f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(item.iconRes),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = stringResource(item.labelRes),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right_24dp),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
