package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.enums.ProxyMode
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.ui.compose.LocalDarkTheme
import kotlinx.coroutines.delay
import kotlin.math.max

internal val DashboardBackground = Color(0xFF10181B)
internal val DashboardLightBackground = Color(0xFFEAF4F5)
private val ConnectedGreen = Color(0xFF43CE8A)
private val DisconnectedBlue = Color(0xFF4C9DBF)
private val UploadBlue = Color(0xFF3D91C7)
private val DownloadGreen = Color(0xFF25A472)
private val TrafficOrange = Color(0xFFF17B35)
private val RouteViolet = Color(0xFF8369C9)
private const val HistorySize = 48
private const val HistorySampleIntervalMs = 3_000L
private const val HistoryWindowSeconds = (HistorySize * HistorySampleIntervalMs / 1_000L).toInt()

@Composable
fun HomeDashboard(
    uiState: MainUiState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
    mapContentTopPadding: Dp = 16.dp,
) {
    val listState = rememberLazyListState()
    val isDarkTheme = LocalDarkTheme.current
    val mapBackground = if (isDarkTheme) DashboardBackground else DashboardLightBackground
    val showStatusBarScrim by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                MapStatusPanel(
                    uiState = uiState,
                    onAction = onAction,
                    contentTopPadding = mapContentTopPadding,
                )
            }
            item { Spacer(Modifier.height(26.dp)) }
            item { TrafficDataCard(uiState = uiState) }
            item { DeviceStatusCards(uiState = uiState) }
            item { ExitIpCard(uiState = uiState, onAction = onAction) }
        }
        if (showStatusBarScrim) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((mapContentTopPadding - 16.dp).coerceAtLeast(0.dp))
                    .background(mapBackground),
            )
        }
    }
}

@Composable
private fun MapStatusPanel(
    uiState: MainUiState,
    onAction: (MainAction) -> Unit,
    contentTopPadding: Dp,
) {
    val location = uiState.geoLocation
    val isDarkTheme = LocalDarkTheme.current
    val mapBackground = if (isDarkTheme) DashboardBackground else DashboardLightBackground
    val mapContentColor = if (isDarkTheme) Color.White else Color(0xFF173239)
    val mapSecondaryContentColor = mapContentColor.copy(alpha = if (isDarkTheme) 0.68f else 0.72f)
    val accent = if (uiState.isRunning) ConnectedGreen else DisconnectedBlue
    val mapInteractionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(contentTopPadding + 330.dp)
            .clickable(
                enabled = !uiState.isLocating,
                interactionSource = mapInteractionSource,
                indication = null,
            ) {
                onAction(MainAction.RefreshExitLocation)
            }
            .background(mapBackground),
    ) {
        TranslucentLocationMap(
            latitude = location?.latitude,
            longitude = location?.longitude,
            isConnected = uiState.isRunning,
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = contentTopPadding + 8.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = if (isDarkTheme) {
                    Color.White.copy(alpha = 0.10f)
                } else {
                    Color.White.copy(alpha = 0.72f)
                },
                contentColor = mapContentColor,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                    Text(
                        text = stringResource(
                            if (uiState.isRunning) R.string.home_protected
                            else R.string.home_not_connected,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (uiState.isLocating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.5.dp,
                        color = mapSecondaryContentColor,
                    )
                    Text(
                        stringResource(R.string.home_locating),
                        style = MaterialTheme.typography.bodySmall,
                        color = mapSecondaryContentColor,
                    )
                }
            } else {
                Text(
                    text = when {
                        uiState.exitIpFailed -> stringResource(R.string.home_exit_connection_failed)
                        location != null -> location.placeLabel
                        else -> stringResource(R.string.home_location_unavailable)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (uiState.exitIpFailed) MaterialTheme.colorScheme.error else mapContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val ip = location?.ip.orEmpty()
            if (ip.isNotBlank()) {
                Text(
                    text = stringResource(
                        if (uiState.isRunning) R.string.home_virtual_ip
                        else R.string.home_current_ip,
                        ip,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = mapSecondaryContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                uiState.exitIpLatencyMs?.let { latencyMs ->
                    Text(
                        text = stringResource(R.string.home_map_exit_latency, latencyMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = mapSecondaryContentColor,
                        maxLines = 1,
                    )
                }
            }
        }

        Surface(
            onClick = { onAction(MainAction.ToggleService) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 34.dp)
                .size(72.dp),
            shape = CircleShape,
            color = accent,
            contentColor = Color.White,
            shadowElevation = 10.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_power_24dp),
                    contentDescription = stringResource(
                        if (uiState.isRunning) R.string.home_disconnect
                        else R.string.home_connect,
                    ),
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

@Composable
private fun TrafficDataCard(uiState: MainUiState) {
    val uploadHistory = remember {
        mutableStateListOf<Long>().apply { repeat(HistorySize) { add(0L) } }
    }
    val downloadHistory = remember {
        mutableStateListOf<Long>().apply { repeat(HistorySize) { add(0L) } }
    }
    val upload = uiState.traffic.uplinkSpeed
    val download = uiState.traffic.downlinkSpeed
    val latestUpload by rememberUpdatedState(upload)
    val latestDownload by rememberUpdatedState(download)

    LaunchedEffect(Unit) {
        while (true) {
            delay(HistorySampleIntervalMs)
            uploadHistory.add(latestUpload)
            downloadHistory.add(latestDownload)
            while (uploadHistory.size > HistorySize) uploadHistory.removeAt(0)
            while (downloadHistory.size > HistorySize) downloadHistory.removeAt(0)
        }
    }

    DashboardCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(R.string.home_data),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.home_live_speed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            HeadlineSpeed(
                label = stringResource(R.string.home_download),
                value = download.toSpeedString(),
                color = DownloadGreen,
                modifier = Modifier.weight(1f),
            )
            HeadlineSpeed(
                label = stringResource(R.string.home_upload),
                value = upload.toSpeedString(),
                color = UploadBlue,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        SpeedHistoryChart(
            uploadHistory = uploadHistory,
            downloadHistory = downloadHistory,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SpeedStatistic(
                label = stringResource(R.string.home_peak),
                upload = uploadHistory.maxOrNull() ?: 0L,
                download = downloadHistory.maxOrNull() ?: 0L,
                modifier = Modifier.weight(1f),
            )
            SpeedStatistic(
                label = stringResource(R.string.home_average),
                upload = uploadHistory.averageLong(),
                download = downloadHistory.averageLong(),
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TrafficTotal(
                label = stringResource(R.string.home_session_traffic),
                upload = uiState.traffic.sessionUplink,
                download = uiState.traffic.sessionDownlink,
                modifier = Modifier.weight(1f),
            )
            TrafficTotal(
                label = stringResource(R.string.home_cumulative_traffic),
                upload = uiState.traffic.totalUplink,
                download = uiState.traffic.totalDownlink,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HeadlineSpeed(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SpeedHistoryChart(uploadHistory: List<Long>, downloadHistory: List<Long>) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val graphMax = max(
        uploadHistory.maxOrNull() ?: 0L,
        downloadHistory.maxOrNull() ?: 0L,
    ).coerceAtLeast(1L)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .width(54.dp)
                    .height(112.dp)
                    .padding(end = 7.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(graphMax.toSpeedString(), fontSize = 9.sp, color = labelColor, maxLines = 1)
                Text((graphMax / 2L).toSpeedString(), fontSize = 9.sp, color = labelColor, maxLines = 1)
                Text("0 B/s", fontSize = 9.sp, color = labelColor, maxLines = 1)
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(112.dp),
            ) {
                repeat(3) { index ->
                    val y = size.height * index / 2f
                    drawLine(
                        gridColor,
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(size.width, y),
                    )
                }

                fun linePath(values: List<Long>): Path {
                    val path = Path()
                    if (values.isEmpty()) return path
                    values.forEachIndexed { index, value ->
                        val x = if (values.size == 1) 0f else size.width * index / (values.size - 1f)
                        val y = size.height - (value.toFloat() / graphMax.toFloat()) * size.height * 0.88f
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    return path
                }

                val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(linePath(downloadHistory), color = DownloadGreen, style = stroke)
                drawPath(linePath(uploadHistory), color = UploadBlue, style = stroke)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 54.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.home_chart_time_ago, HistoryWindowSeconds / 60, HistoryWindowSeconds % 60),
                fontSize = 9.sp,
                color = labelColor,
            )
            Text(
                stringResource(
                    R.string.home_chart_time_ago,
                    HistoryWindowSeconds / 120,
                    (HistoryWindowSeconds / 2) % 60,
                ),
                fontSize = 9.sp,
                color = labelColor,
            )
            Text(stringResource(R.string.home_chart_now), fontSize = 9.sp, color = labelColor)
        }
    }
}

@Composable
private fun SpeedStatistic(
    label: String,
    upload: Long,
    download: Long,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        CompactMetric(R.drawable.ic_arrow_downward_24dp, download.toSpeedString(), DownloadGreen)
        Spacer(Modifier.height(4.dp))
        CompactMetric(R.drawable.ic_arrow_upward_24dp, upload.toSpeedString(), UploadBlue)
    }
}

@Composable
private fun TrafficTotal(
    label: String,
    upload: Long,
    download: Long,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        CompactMetric(R.drawable.ic_arrow_downward_24dp, download.toTrafficString(), DownloadGreen)
        Spacer(Modifier.height(4.dp))
        CompactMetric(R.drawable.ic_arrow_upward_24dp, upload.toTrafficString(), UploadBlue)
    }
}

@Composable
private fun CompactMetric(@DrawableRes icon: Int, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeviceStatusCards(uiState: MainUiState) {
    val usedMemoryMb = remember(uiState.traffic) {
        val runtime = Runtime.getRuntime()
        (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SmallStatusCard(
            icon = R.drawable.ic_lock_24dp,
            label = stringResource(R.string.home_memory),
            value = "$usedMemoryMb MB",
            accent = TrafficOrange,
            modifier = Modifier.weight(1f),
        )
        SmallStatusCard(
            icon = R.drawable.ic_dns_24dp,
            label = stringResource(R.string.home_active_mode),
            value = stringResource(
                when (uiState.proxyMode) {
                    ProxyMode.STANDARD -> R.string.home_mode_standard
                    ProxyMode.ROOT_TUN -> R.string.home_mode_root_tun
                    ProxyMode.TPROXY -> R.string.home_mode_tproxy
                },
            ),
            accent = RouteViolet,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SmallStatusCard(
    @DrawableRes icon: Int,
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(92.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(21.dp),
            )
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ExitIpCard(uiState: MainUiState, onAction: (MainAction) -> Unit) {
    val location = uiState.geoLocation
    val unavailable = stringResource(R.string.home_location_unavailable)
    val connectionFailed = stringResource(R.string.home_exit_connection_failed)
    val region = listOf(location?.country.orEmpty(), location?.region.orEmpty(), location?.city.orEmpty())
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" / ")
        .ifBlank { if (uiState.exitIpFailed) connectionFailed else unavailable }

    DashboardCard(
        modifier = Modifier.clickable(enabled = !uiState.isLocating) {
            onAction(MainAction.RefreshExitLocation)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DisconnectedBlue.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_location_24dp),
                    contentDescription = null,
                    tint = DisconnectedBlue,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_exit_ip),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (uiState.exitIpFailed) connectionFailed
                    else location?.ip.orEmpty().ifBlank { "-" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (uiState.exitIpFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Surface(
                onClick = { onAction(MainAction.RefreshExitLocation) },
                enabled = !uiState.isLocating,
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (uiState.isLocating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh_24dp),
                            contentDescription = stringResource(R.string.home_refresh_exit_ip),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
        Row {
            Text(
                text = stringResource(R.string.home_country_region),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = region,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text(
                text = stringResource(R.string.home_exit_latency),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    uiState.isLocating -> stringResource(R.string.home_locating)
                    uiState.exitIpLatencyMs != null -> stringResource(
                        R.string.home_exit_latency_value,
                        uiState.exitIpLatencyMs,
                    )
                    else -> "-"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (uiState.exitIpFailed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DashboardCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .then(modifier),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}

private fun List<Long>.averageLong(): Long =
    if (isEmpty()) 0L else sum().div(size)
