package com.v2ray.ang.ui.main

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.enums.ProxyMode
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.util.AppMemoryReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

internal val DashboardBackground = Color(0xFF10181B)
internal val DashboardLightBackground = Color(0xFFEAF4F5)
private val ConnectedGreen = Color(0xFF43CE8A)
private val DisconnectedBlue = Color(0xFF4C9DBF)
private val UploadBlue = Color(0xFF3D91C7)
private val DownloadGreen = Color(0xFF25A472)
internal val SpeedChartAxisWidth = 64.dp

@Composable
fun HomeDashboard(
    uiState: MainUiState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
    mapContentTopPadding: Dp = 16.dp,
    contentBottomPadding: Dp = 20.dp,
) {
    val listState = rememberLazyListState()

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().clipToBounds(),
            state = listState,
        ) {
            item {
                MapStatusPanel(
                    uiState = uiState,
                    onAction = onAction,
                    contentTopPadding = mapContentTopPadding,
                )
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(bottom = contentBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TrafficDataCard(uiState = uiState)
                    TrafficInformationCard(uiState = uiState)
                    DeviceStatusCard(uiState = uiState)
                    ExitIpCard(uiState = uiState, onAction = onAction)
                }
            }
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
            // Keep the next item's background clear of the floating button's shadow.
            .padding(bottom = 12.dp)
            .height(contentTopPadding + 296.dp)
            .clickable(
                enabled = !uiState.isLocating,
                interactionSource = mapInteractionSource,
                indication = null,
            ) {
                onAction(MainAction.RefreshExitLocation)
            },
    ) {
        Box(
            Modifier.fillMaxWidth().height(contentTopPadding + 264.dp)
                .background(mapBackground).clipToBounds(),
        ) {
            TranslucentLocationMap(
                latitude = location?.latitude,
                longitude = location?.longitude,
                isConnected = uiState.isRunning,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = contentTopPadding + 4.dp, start = 20.dp, end = 20.dp),
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
                            when {
                                uiState.isStarting -> R.string.connection_connecting
                                uiState.isRunning -> R.string.home_proxy_exit_status
                                else -> R.string.home_not_connected
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
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
                        location != null && location.hasLocationDetails -> location.placeLabel
                        location != null -> stringResource(R.string.home_location_unavailable)
                        else -> stringResource(R.string.home_location_unavailable)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (uiState.exitIpFailed) MaterialTheme.colorScheme.error else mapContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val ipv4 = location?.effectiveIpv4.orEmpty()
            val ipv6 = location?.effectiveIpv6.orEmpty()
            if (ipv4.isNotBlank()) {
                Text(
                    text = stringResource(R.string.home_ipv4_value, ipv4),
                    style = MaterialTheme.typography.bodySmall,
                    color = mapSecondaryContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (ipv6.isNotBlank()) {
                Text(
                    text = stringResource(R.string.home_ipv6_value, ipv6),
                    style = MaterialTheme.typography.bodySmall,
                    color = mapSecondaryContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (ipv4.isNotBlank() || ipv6.isNotBlank()) {
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
            enabled = !uiState.isStarting,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(64.dp),
            shape = CircleShape,
            color = DisconnectedBlue,
            contentColor = Color.White,
            shadowElevation = 6.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (uiState.isStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.5.dp,
                        color = Color.White,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_power_24dp),
                        contentDescription = stringResource(
                            if (uiState.isRunning) R.string.home_disconnect
                            else R.string.home_connect,
                        ),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrafficDataCard(uiState: MainUiState) {
    val upload = uiState.traffic.uplinkSpeed
    val download = uiState.traffic.downlinkSpeed

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
        Spacer(Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.home_live_speed),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChartLegend(stringResource(R.string.home_download), DownloadGreen)
                ChartLegend(stringResource(R.string.home_upload), UploadBlue)
            }
        }
        Spacer(Modifier.height(8.dp))
        SpeedHistoryChart(
            uploadSpeed = upload,
            downloadSpeed = download,
        )
    }
}

@Composable
private fun TrafficInformationCard(uiState: MainUiState) {
    DashboardCard {
        DashboardCardTitle(stringResource(R.string.home_traffic_information))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columnWidth = (maxWidth - 25.dp) / 2f
            val stacked = columnWidth < 116.dp * LocalDensity.current.fontScale
            DashboardColumns(
                leftLabel = stringResource(R.string.home_session_traffic),
                rightLabel = stringResource(R.string.home_cumulative_traffic),
                leftContent = {
                    TrafficTotal(uiState.traffic.sessionUplink, uiState.traffic.sessionDownlink, stacked)
                },
                rightContent = {
                    TrafficTotal(uiState.traffic.totalUplink, uiState.traffic.totalDownlink, stacked)
                },
            )
        }
    }
}

@Composable
private fun HeadlineSpeed(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(8.dp), color = dashboardPanelColor()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 22.sp,
                color = color,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun dashboardPanelColor(): Color =
    if (LocalDarkTheme.current) Color(0xFF202C33) else Color(0xFFF2F7FA)

@Composable
private fun ChartLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SpeedHistoryChart(
    uploadSpeed: Long,
    downloadSpeed: Long,
    modifier: Modifier = Modifier,
    motion: SpeedChartMotion = remember { SpeedChartMotion(initialUpload = uploadSpeed, initialDownload = downloadSpeed) },
) {
    val axisWidth = SpeedChartAxisWidth * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val resolver = LocalContext.current.contentResolver
    fun systemMotionEnabled() =
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var motionEnabled by remember(resolver) { mutableStateOf(systemMotionEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motionEnabled = systemMotionEnabled() }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        motionEnabled = systemMotionEnabled()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestUpload by rememberUpdatedState(uploadSpeed)
    val latestDownload by rememberUpdatedState(downloadSpeed)
    LaunchedEffect(motion, lifecycleOwner, motionEnabled) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val animate = motionEnabled && coroutineContext[MotionDurationScale]?.scaleFactor != 0f
                withFrameNanos { time -> motion.advance(time, latestUpload, latestDownload, animate) }
                if (!animate || !motion.needsContinuousFrames) delay(SpeedChartSampleIntervalMs)
            }
        }
    }
    val samples = motion.samples
    val upload = remember(samples) { samples.map { it.upload } }
    val download = remember(samples) { samples.map { it.download } }
    val uploadTangents = remember(upload) { speedChartTangents(upload) }
    val downloadTangents = remember(download) { speedChartTangents(download) }
    val uploadPath = remember { Path() }
    val downloadPath = remember { Path() }
    val maximumLabel by remember(motion) { derivedStateOf { motion.maximum.toLong().toSpeedString() } }
    val middleLabel by remember(motion) {
        derivedStateOf { if (motion.maximum < 2f) "" else (motion.maximum / 2f).toLong().toSpeedString() }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .width(axisWidth)
                    .height(88.dp)
                    .padding(end = 7.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(maximumLabel, fontSize = 9.sp, color = labelColor, maxLines = 1)
                Text(middleLabel, fontSize = 9.sp, color = labelColor, maxLines = 1)
                Text("0 B/s", fontSize = 9.sp, color = labelColor, maxLines = 1)
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(88.dp)
                    .clipToBounds(),
            ) {
                repeat(3) { index ->
                    val y = size.height * index / 2f
                    drawLine(
                        gridColor,
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(size.width, y),
                    )
                }

                val graphMax = motion.maximum.coerceAtLeast(1f)
                fun linePath(path: Path, values: List<Float>, tangents: List<Float>): Path {
                    path.reset()
                    if (values.isEmpty()) return path
                    fun height(value: Float) = size.height -
                        value.coerceAtLeast(0f) / graphMax * size.height * 0.88f
                    fun x(index: Int) = size.width * motion.horizontalFraction(samples[index].timeNanos)
                    fun tangent(index: Int): Float {
                        if (index != values.lastIndex - 1 || index < 1) return tangents[index]
                        // Blend the former endpoint's new handle without disturbing older segments.
                        val previous = values[index] - values[index - 1]
                        return previous + (tangents[index] - previous) * motion.incomingTangentFraction
                    }
                    path.moveTo(x(0), height(values[0]))
                    for (index in 0 until values.lastIndex) {
                        val startX = x(index)
                        val endX = x(index + 1)
                        val spacing = endX - startX
                        val low = minOf(values[index], values[index + 1])
                        val high = maxOf(values[index], values[index + 1])
                        path.cubicTo(
                            startX + spacing / 3f,
                            height((values[index] + tangent(index) / 3f).coerceIn(low, high)),
                            endX - spacing / 3f,
                            height((values[index + 1] - tangent(index + 1) / 3f).coerceIn(low, high)),
                            endX,
                            height(values[index + 1]),
                        )
                    }
                    return path
                }

                val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(linePath(downloadPath, download, downloadTangents), color = DownloadGreen, style = stroke)
                drawPath(linePath(uploadPath, upload, uploadTangents), color = UploadBlue, style = stroke)
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = axisWidth, top = 4.dp),
        ) {
            val textMeasurer = rememberTextMeasurer()
            val labelStyle = TextStyle(fontSize = 9.sp)
            val full = listOf(
                stringResource(R.string.home_chart_time_ago, SpeedChartWindowSeconds / 60, SpeedChartWindowSeconds % 60),
                stringResource(R.string.home_chart_time_ago, SpeedChartWindowSeconds / 120, (SpeedChartWindowSeconds / 2) % 60),
                stringResource(R.string.home_chart_now),
            )
            val compact = listOf(
                stringResource(R.string.home_chart_time_short, SpeedChartWindowSeconds / 60, SpeedChartWindowSeconds % 60),
                stringResource(R.string.home_chart_time_short, SpeedChartWindowSeconds / 120, (SpeedChartWindowSeconds / 2) % 60),
                full.last(),
            )
            val cellWidth = constraints.maxWidth / 3
            val labels = if (full.all { textMeasurer.measure(it, labelStyle).size.width <= cellWidth }) full else compact
            val showMiddle = labels.all { textMeasurer.measure(it, labelStyle).size.width <= cellWidth }
            Row(Modifier.fillMaxWidth()) {
                Text(labels.first(), Modifier.weight(1f), style = labelStyle, color = labelColor,
                    maxLines = 1, textAlign = TextAlign.Start)
                if (showMiddle) {
                    Text(labels[1], Modifier.weight(1f), style = labelStyle, color = labelColor,
                        maxLines = 1, textAlign = TextAlign.Center)
                }
                Text(labels.last(), Modifier.weight(1f), style = labelStyle, color = labelColor,
                    maxLines = 1, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun TrafficTotal(
    upload: Long,
    download: Long,
    stacked: Boolean,
) {
    Column {
        TrafficTotalLine(stringResource(R.string.home_download), download, stacked)
        Spacer(Modifier.height(4.dp))
        TrafficTotalLine(stringResource(R.string.home_upload), upload, stacked)
    }
}

@Composable
private fun TrafficTotalLine(label: String, value: Long, stacked: Boolean) {
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.toTrafficString(), Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                text = value.toTrafficString(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeviceStatusCard(uiState: MainUiState) {
    val context = LocalContext.current.applicationContext
    val memorySamples = remember(context) {
        flow {
            while (true) {
                emit(AppMemoryReader.readPssKb(context))
                delay(5_000L)
            }
        }.flowOn(Dispatchers.IO)
    }
    val usedMemoryKb by memorySamples.collectAsStateWithLifecycle(initialValue = null)
    DashboardCard {
        DashboardCardTitle(stringResource(R.string.home_runtime_status))
        DashboardColumns(
            leftLabel = stringResource(R.string.home_memory),
            rightLabel = stringResource(R.string.home_active_mode),
            leftContent = { DashboardValue(usedMemoryKb?.let { "${it / 1024L} MB" } ?: "-") },
            rightContent = {
                DashboardValue(
                    stringResource(
                        when (uiState.proxyMode) {
                            ProxyMode.STANDARD -> R.string.home_mode_standard
                            ProxyMode.ROOT_TUN -> R.string.home_mode_root_tun
                            ProxyMode.TPROXY -> R.string.home_mode_tproxy
                        },
                    ),
                )
            },
        )
    }
}

@Composable
private fun DashboardColumns(
    leftLabel: String,
    rightLabel: String,
    leftContent: @Composable () -> Unit,
    rightContent: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val columnWidth = ((maxWidth - 25.dp) / 2f).coerceAtLeast(1.dp)
        val textMeasurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.bodySmall
        val labelConstraints = Constraints(maxWidth = with(density) { columnWidth.roundToPx() })
        val labelHeight = with(density) {
            maxOf(
                textMeasurer.measure(leftLabel, labelStyle, constraints = labelConstraints).size.height,
                textMeasurer.measure(rightLabel, labelStyle, constraints = labelConstraints).size.height,
            ).toDp()
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    leftLabel,
                    modifier = Modifier.height(labelHeight),
                    style = labelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                leftContent()
            }
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    rightLabel,
                    modifier = Modifier.height(labelHeight),
                    style = labelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rightContent()
            }
        }
    }
}

@Composable
private fun DashboardValue(value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        value,
        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 16.sp),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        fontWeight = FontWeight.Medium,
        color = color,
    )
}

@Composable
private fun DashboardCardTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp),
    )
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
        .ifBlank {
            when {
                uiState.exitIpFailed -> connectionFailed
                uiState.isLocating && location != null && !location.hasLocationDetails ->
                    stringResource(R.string.home_location_resolving)
                else -> unavailable
            }
        }

    DashboardCard {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.home_exit_ip),
                    modifier = Modifier.padding(end = 48.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (uiState.exitIpFailed) connectionFailed else buildList {
                        location?.effectiveIpv4?.takeIf(String::isNotBlank)?.let {
                            add(stringResource(R.string.home_ipv4_value, it))
                        }
                        location?.effectiveIpv6?.takeIf(String::isNotBlank)?.let {
                            add(stringResource(R.string.home_ipv6_value, it))
                        }
                    }.joinToString("\n").ifBlank {
                        if (uiState.isLocating) stringResource(R.string.home_locating) else "-"
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (uiState.exitIpFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = { onAction(MainAction.RefreshExitLocation) },
                enabled = !uiState.isLocating,
                modifier = Modifier.align(Alignment.TopEnd).offset(y = (-16).dp),
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
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
        DashboardColumns(
            leftLabel = stringResource(R.string.home_country_region),
            rightLabel = stringResource(R.string.home_exit_latency),
            leftContent = {
                Text(
                    text = region,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            rightContent = {
                DashboardValue(
                    value = when {
                        uiState.isLocating -> stringResource(R.string.home_locating)
                        uiState.exitIpLatencyMs != null -> stringResource(
                            R.string.home_exit_latency_value,
                            uiState.exitIpLatencyMs,
                        )
                        else -> "-"
                    },
                    color = if (uiState.exitIpFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            },
        )
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
            .padding(horizontal = 16.dp)
            .then(modifier),
        shape = RoundedCornerShape(14.dp),
        color = dashboardPanelColor(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}
