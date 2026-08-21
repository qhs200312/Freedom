package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.LocalDarkTheme
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class MainDestination(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    Dashboard(R.string.main_nav_dashboard, R.drawable.ic_dashboard_24dp),
    Nodes(R.string.main_nav_nodes, R.drawable.ic_dns_24dp),
    Settings(R.string.main_nav_settings, R.drawable.ic_settings_24dp),
}

internal val MainNavigationContentClearance = 84.dp

private val NavigationShape = RoundedCornerShape(percent = 50)
private val IndicatorShape = RoundedCornerShape(percent = 50)
private val NavigationBackgroundHorizontalInset = 20.dp

@Composable
internal fun MainBottomNavigation(
    selectedDestination: MainDestination,
    onDestinationSelected: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val destinations = MainDestination.entries
    val selectedIndex = selectedDestination.ordinal
    val darkTheme = LocalDarkTheme.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    var isDragging by remember { mutableStateOf(false) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var dragVelocityPxPerSecond by remember { mutableFloatStateOf(0f) }
    var lastDragEventNanos by remember { mutableLongStateOf(0L) }
    var pressedIndex by remember { mutableIntStateOf(-1) }
    var isLongPressed by remember { mutableStateOf(false) }

    val containerScale by animateFloatAsState(
        targetValue = when {
            isLongPressed -> 1.045f
            pressedIndex >= 0 -> 1.025f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.38f, stiffness = 640f),
        label = "bottomNavigationScale",
    )
    val glassBackground = Brush.verticalGradient(
        colors = if (darkTheme) {
            listOf(Color(0xF035373B), Color(0xEA24262A))
        } else {
            listOf(Color(0xF5FFFFFF), Color(0xE8EDF1F2))
        },
    )
    val glassBorder = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = if (darkTheme) 0.34f else 0.96f),
            MaterialTheme.colorScheme.outline.copy(alpha = if (darkTheme) 0.30f else 0.18f),
        ),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .graphicsLayer {
                    scaleX = containerScale
                    scaleY = containerScale
                },
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = NavigationBackgroundHorizontalInset)
                    .fillMaxSize(),
                shape = NavigationShape,
                color = Color.Transparent,
                shadowElevation = 14.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(NavigationShape)
                        .background(glassBackground)
                        .border(1.dp, glassBorder, NavigationShape),
                )
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
            ) {
                val itemWidth = maxWidth / destinations.size
                val itemWidthPx = with(density) { itemWidth.toPx() }
                val indicatorHorizontalInset = NavigationBackgroundHorizontalInset
                val indicatorWidth = (itemWidth - indicatorHorizontalInset * 2f)
                    .coerceAtLeast(72.dp)
                val indicatorWidthPx = with(density) { indicatorWidth.toPx() }
                val navigationWidthPx = itemWidthPx * destinations.size
                val maxDragOffsetPx = itemWidthPx * destinations.lastIndex
                val restingOffsetPx = selectedIndex * itemWidthPx
                val animatedIndicatorOffset = remember(itemWidthPx) {
                    Animatable(restingOffsetPx)
                }
                LaunchedEffect(restingOffsetPx) {
                    if (
                        !isDragging &&
                        animatedIndicatorOffset.targetValue != restingOffsetPx
                    ) {
                        animatedIndicatorOffset.animateTo(
                            targetValue = restingOffsetPx,
                            animationSpec = spring(dampingRatio = 0.62f, stiffness = 500f),
                        )
                    }
                }
                val indicatorOffsetPx = if (isDragging) {
                    dragOffsetPx
                } else {
                    animatedIndicatorOffset.value
                }
                val selectionProgress = if (maxDragOffsetPx > 0f) {
                    (indicatorOffsetPx / maxDragOffsetPx).coerceIn(0f, 1f)
                } else {
                    0.5f
                }
                val selectionPosition =
                    selectionProgress * destinations.lastIndex
                val indicatorHorizontalInsetPx = with(density) {
                    indicatorHorizontalInset.toPx()
                }
                val indicatorLeftPx = indicatorOffsetPx + indicatorHorizontalInsetPx
                val stretchVelocityPxPerSecond = with(density) { 2200.dp.toPx() }
                val dragSpeedProgress = if (isDragging) {
                    (abs(dragVelocityPxPerSecond) / stretchVelocityPxPerSecond)
                        .coerceIn(0f, 1f)
                } else {
                    0f
                }
                val indicatorScale by animateFloatAsState(
                    targetValue = when {
                        isDragging -> 1.10f
                        isLongPressed -> 1.08f
                        pressedIndex >= 0 -> 1.055f
                        else -> 1f
                    },
                    animationSpec = spring(dampingRatio = 0.38f, stiffness = 680f),
                    label = "bottomNavigationIndicatorScale",
                )
                val indicatorStretchX by animateFloatAsState(
                    targetValue = 1f + dragSpeedProgress * 0.18f,
                    animationSpec = spring(dampingRatio = 0.54f, stiffness = 720f),
                    label = "bottomNavigationIndicatorStretchX",
                )
                val indicatorStretchY by animateFloatAsState(
                    targetValue = 1f - dragSpeedProgress * 0.035f,
                    animationSpec = spring(dampingRatio = 0.58f, stiffness = 760f),
                    label = "bottomNavigationIndicatorStretchY",
                )
                val indicatorShadowElevation by animateDpAsState(
                    targetValue = (8f + dragSpeedProgress * 8f).dp,
                    animationSpec = spring(dampingRatio = 0.68f, stiffness = 620f),
                    label = "bottomNavigationIndicatorShadowElevation",
                )
                val indicatorVerticalInset by animateDpAsState(
                    targetValue = if (isDragging || pressedIndex == selectedIndex) 1.dp else 3.dp,
                    animationSpec = spring(dampingRatio = 0.42f, stiffness = 820f),
                    label = "bottomNavigationIndicatorVerticalInset",
                )
                val indicatorBackground = Brush.verticalGradient(
                    colors = if (darkTheme) {
                        listOf(
                            Color.White.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.16f),
                        )
                    } else {
                        listOf(
                            Color.White.copy(alpha = 0.96f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                        )
                    },
                )
                val indicatorBorder = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (darkTheme) 0.38f else 0.98f),
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.24f),
                    ),
                )
                val lensMagnification by animateFloatAsState(
                    targetValue = when {
                        isDragging -> 1.15f
                        pressedIndex == selectedIndex -> 1.10f
                        isLongPressed -> 1.07f
                        else -> 1.035f
                    },
                    animationSpec = spring(dampingRatio = 0.52f, stiffness = 610f),
                    label = "bottomNavigationLensMagnification",
                )
                val dragState = rememberDraggableState { delta ->
                    if (isDragging && maxDragOffsetPx > 0f) {
                        val now = System.nanoTime()
                        val elapsedSeconds =
                            ((now - lastDragEventNanos).coerceAtLeast(4_000_000L) / 1_000_000_000f)
                        val instantaneousVelocity = (delta / elapsedSeconds).coerceIn(
                            -stretchVelocityPxPerSecond * 1.4f,
                            stretchVelocityPxPerSecond * 1.4f,
                        )
                        dragVelocityPxPerSecond =
                            dragVelocityPxPerSecond * 0.68f + instantaneousVelocity * 0.32f
                        lastDragEventNanos = now
                        dragOffsetPx = (dragOffsetPx + delta).coerceIn(0f, maxDragOffsetPx)
                    }
                }
                LaunchedEffect(isDragging) {
                    if (isDragging) {
                        while (true) {
                            withFrameNanos { frameTimeNanos ->
                                if (frameTimeNanos - lastDragEventNanos > 40_000_000L) {
                                    dragVelocityPxPerSecond *= 0.82f
                                    if (abs(dragVelocityPxPerSecond) < 20f) {
                                        dragVelocityPxPerSecond = 0f
                                    }
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Horizontal,
                            onDragStarted = { startPosition ->
                                val hitLeft = indicatorLeftPx
                                val hitRight = indicatorLeftPx + indicatorWidthPx
                                if (startPosition.x in hitLeft..hitRight) {
                                    pressedIndex = -1
                                    isLongPressed = false
                                    isDragging = true
                                    dragOffsetPx = indicatorOffsetPx
                                    dragVelocityPxPerSecond = 0f
                                    lastDragEventNanos = System.nanoTime()
                                }
                            },
                            onDragStopped = { releaseVelocity ->
                                if (isDragging) {
                                    val cappedReleaseVelocity = releaseVelocity.coerceIn(
                                        -stretchVelocityPxPerSecond * 1.8f,
                                        stretchVelocityPxPerSecond * 1.8f,
                                    )
                                    val projectedOffset =
                                        (dragOffsetPx + cappedReleaseVelocity * 0.065f)
                                            .coerceIn(0f, maxDragOffsetPx)
                                    val destinationIndex = if (maxDragOffsetPx > 0f) {
                                        (projectedOffset / maxDragOffsetPx * destinations.lastIndex)
                                            .roundToInt()
                                            .coerceIn(destinations.indices)
                                    } else {
                                        selectedIndex
                                    }
                                    animatedIndicatorOffset.snapTo(dragOffsetPx)
                                    isDragging = false
                                    dragVelocityPxPerSecond = 0f
                                    onDestinationSelected(destinations[destinationIndex])
                                    animatedIndicatorOffset.animateTo(
                                        targetValue = destinationIndex * itemWidthPx,
                                        animationSpec = spring(
                                            dampingRatio = 0.52f,
                                            stiffness = 480f,
                                        ),
                                        initialVelocity = cappedReleaseVelocity,
                                    )
                                }
                            },
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .width(indicatorWidth)
                            .fillMaxHeight()
                            .graphicsLayer {
                                translationX = indicatorLeftPx
                                scaleX = indicatorScale * indicatorStretchX
                                scaleY = indicatorScale * indicatorStretchY
                            }
                            .padding(vertical = indicatorVerticalInset.coerceAtLeast(0.dp))
                            .shadow(
                                elevation = indicatorShadowElevation,
                                shape = IndicatorShape,
                                clip = false,
                                ambientColor = Color(0xFF52DCE5).copy(
                                    alpha = if (darkTheme) 0.22f else 0.18f,
                                ),
                                spotColor = Color(0xFF9C78FF).copy(
                                    alpha = if (darkTheme) 0.28f else 0.20f,
                                ),
                            )
                            .clip(IndicatorShape)
                            .background(indicatorBackground)
                            .border(0.8.dp, indicatorBorder, IndicatorShape),
                    )

                    Row(modifier = Modifier.fillMaxSize()) {
                        destinations.forEachIndexed { index, destination ->
                            val selected = selectedIndex == index
                            val label = stringResource(destination.labelRes)
                            val itemPressOffset by animateDpAsState(
                                targetValue = if (pressedIndex == index) 2.dp else 0.dp,
                                animationSpec = spring(dampingRatio = 0.34f, stiffness = 850f),
                                label = "bottomNavigationItemOffset$index",
                            )
                            val itemIconSize by animateDpAsState(
                                targetValue = if (pressedIndex == index) 19.dp else 22.dp,
                                animationSpec = spring(dampingRatio = 0.36f, stiffness = 900f),
                                label = "bottomNavigationItemIconSize$index",
                            )
                            val itemColor by animateColorAsState(
                                targetValue = if (selected) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                animationSpec = spring(stiffness = 650f),
                                label = "bottomNavigationItemColor$index",
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .semantics {
                                        role = Role.Tab
                                        this.selected = selected
                                        onClick(label = label) {
                                            onDestinationSelected(destination)
                                            true
                                        }
                                    }
                                    .pointerInput(index) {
                                        detectTapGestures(
                                            onPress = {
                                                pressedIndex = index
                                                tryAwaitRelease()
                                                if (pressedIndex == index) pressedIndex = -1
                                                isLongPressed = false
                                            },
                                            onLongPress = {
                                                isLongPressed = true
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onTap = { onDestinationSelected(destination) },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                NavigationItemVisual(
                                    destination = destination,
                                    label = label,
                                    selected = selected,
                                    tint = itemColor,
                                    iconSize = itemIconSize,
                                    modifier = Modifier.offset(y = itemPressOffset),
                                )
                            }
                        }
                    }

                    val verticalInsetPx = with(density) {
                        indicatorVerticalInset.coerceAtLeast(0.dp).toPx()
                    }
                    val navigationHeightPx = with(density) { 58.dp.toPx() }
                    val reflectionOffsetPx =
                        indicatorWidthPx * (-0.42f + selectionProgress * 0.82f)

                    Box(
                        modifier = Modifier
                            .width(indicatorWidth)
                            .fillMaxHeight()
                            .graphicsLayer {
                                translationX = indicatorLeftPx
                                scaleX = indicatorScale * indicatorStretchX
                                scaleY = indicatorScale * indicatorStretchY
                            }
                            .padding(vertical = indicatorVerticalInset.coerceAtLeast(0.dp))
                            .clip(IndicatorShape)
                            .background(
                                Brush.verticalGradient(
                                    if (darkTheme) {
                                        listOf(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                                        )
                                    } else {
                                        listOf(
                                            Color.White.copy(alpha = 0.98f),
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                        )
                                    },
                                ),
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFF52DCE5).copy(
                                            alpha = if (darkTheme) 0.22f else 0.34f,
                                        ),
                                        Color.White.copy(
                                            alpha = if (darkTheme) 0.14f else 0.44f,
                                        ),
                                        Color(0xFF9C78FF).copy(
                                            alpha = if (darkTheme) 0.20f else 0.30f,
                                        ),
                                    ),
                                ),
                                shape = IndicatorShape,
                            )
                            .clearAndSetSemantics { },
                    ) {
                        NavigationLensContent(
                            destinations = destinations,
                            selectedTint = MaterialTheme.colorScheme.secondary,
                            unselectedTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectionPosition = selectionPosition,
                            navigationWidthPx = navigationWidthPx.roundToInt(),
                            navigationHeightPx = navigationHeightPx.roundToInt(),
                            lensContentLeftPx = indicatorLeftPx,
                            lensContentTopPx = verticalInsetPx,
                            magnification = lensMagnification,
                            modifier = Modifier.fillMaxSize(),
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        colorStops = arrayOf(
                                            0.00f to Color.Transparent,
                                            0.34f to Color.Transparent,
                                            0.48f to Color.White.copy(
                                                alpha = if (darkTheme) 0.13f else 0.38f,
                                            ),
                                            0.58f to Color.White.copy(
                                                alpha = if (darkTheme) 0.06f else 0.16f,
                                            ),
                                            0.72f to Color.Transparent,
                                            1.00f to Color.Transparent,
                                        ),
                                        start = androidx.compose.ui.geometry.Offset(
                                            reflectionOffsetPx,
                                            -verticalInsetPx,
                                        ),
                                        end = androidx.compose.ui.geometry.Offset(
                                            reflectionOffsetPx +
                                                indicatorWidthPx * 0.78f,
                                            with(density) { 58.dp.toPx() },
                                        ),
                                    ),
                                ),
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(0.72f)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color(0xFF52DCE5).copy(
                                                alpha = if (darkTheme) 0.12f else 0.24f,
                                            ),
                                            Color.Transparent,
                                            Color(0xFF9C78FF).copy(
                                                alpha = if (darkTheme) 0.10f else 0.20f,
                                            ),
                                        ),
                                    ),
                                ),
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth(0.84f)
                                .height(1.dp)
                                .background(
                                    Color.White.copy(alpha = if (darkTheme) 0.24f else 0.78f),
                                ),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(0.88f)
                            .height(1.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = if (darkTheme) 0.16f else 0.62f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationLensContent(
    destinations: List<MainDestination>,
    selectedTint: Color,
    unselectedTint: Color,
    selectionPosition: Float,
    navigationWidthPx: Int,
    navigationHeightPx: Int,
    lensContentLeftPx: Float,
    lensContentTopPx: Float,
    magnification: Float,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            Row(modifier = Modifier.fillMaxSize()) {
                destinations.forEachIndexed { index, destination ->
                    val selectionInfluence =
                        (1f - abs(index - selectionPosition)).coerceIn(0f, 1f)
                    NavigationItemVisual(
                        destination = destination,
                        label = stringResource(destination.labelRes),
                        selected = true,
                        tint = lerp(unselectedTint, selectedTint, selectionInfluence),
                        iconSize = 22.dp,
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .graphicsLayer {
                                scaleX = magnification
                                scaleY = magnification
                            },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val navigationConstraints = Constraints.fixed(
            width = navigationWidthPx.coerceAtLeast(1),
            height = navigationHeightPx.coerceAtLeast(1),
        )
        val contentPlaceable = measurables.single().measure(navigationConstraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceable.place(
                x = -lensContentLeftPx.roundToInt(),
                y = -lensContentTopPx.roundToInt(),
            )
        }
    }
}

@Composable
private fun NavigationItemVisual(
    destination: MainDestination,
    label: String,
    selected: Boolean,
    tint: Color,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = label,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Icon(
                painter = painterResource(destination.iconRes),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
