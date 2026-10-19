package com.v2ray.ang.ui.main

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import kotlin.math.abs

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
private val NavigationHorizontalInset = 18.dp
private val GlassScreenEdgeInset = 8.dp
private val IndicatorEdgeInset = 4.dp

@Composable
internal fun MainBottomNavigation(
    selectedDestination: MainDestination,
    onDestinationSelected: (MainDestination) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val destinations = MainDestination.entries
    val selectedIndex = selectedDestination.ordinal
    val darkTheme = LocalDarkTheme.current
    val restingGlassColor = if (darkTheme) Color(0xFF343439) else Color(0xFFE9E9EC)
    val movingGlassColor = MaterialTheme.colorScheme.surface
    val navigationTint = MaterialTheme.colorScheme.surface.copy(
        alpha = if (darkTheme) 0.12f else 0.14f,
    )
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val motionDurationScale = scope.coroutineContext[MotionDurationScale]
    val reduceMotion = motionDurationScale?.scaleFactor == 0f
    val latestOnDestinationSelected by rememberUpdatedState(onDestinationSelected)
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)
    val latestReduceMotion by rememberUpdatedState(reduceMotion)
    val interactions = remember { destinations.map { MutableInteractionSource() } }
    val pressedStates = interactions.map { it.collectIsPressedAsState() }
    val focusedStates = interactions.map { it.collectIsFocusedAsState() }
    val pressedIndex = pressedStates.indexOfFirst { it.value }
    val navigationItemsBackdrop = rememberLayerBackdrop()

    val motion = remember { NavigationLensMotionState(scope, 0f) }
    val isDragging = motion.isDragging
    var dragDistancePx by remember { mutableFloatStateOf(0f) }
    var dragSpeedRatio by remember { mutableFloatStateOf(0f) }
    var lastDragEventTime by remember { mutableLongStateOf(0L) }
    val dragVelocityTracker = remember { VelocityTracker() }
    LaunchedEffect(isDragging) {
        while (isDragging) {
            withFrameNanos { }
            if (SystemClock.uptimeMillis() - lastDragEventTime > 70L) {
                dragSpeedRatio = (dragSpeedRatio * 0.8f).let { if (it < 0.001f) 0f else it }
            }
        }
    }
    val glassBorder = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = if (darkTheme) 0.24f else 0.62f),
            MaterialTheme.colorScheme.outline.copy(alpha = if (darkTheme) 0.22f else 0.14f),
        ),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = NavigationHorizontalInset,
                end = NavigationHorizontalInset,
                bottom = 10.dp,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = NavigationBackgroundHorizontalInset)
                    .fillMaxSize(),
                shape = NavigationShape,
                color = Color.Transparent,
                shadowElevation = 0.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(NavigationShape)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { NavigationShape },
                            effects = {
                                vibrancy()
                                blur(5.dp.toPx())
                                lens(10.dp.toPx(), 16.dp.toPx())
                            },
                            onDrawSurface = { drawRect(navigationTint) },
                        )
                        .border(1.dp, glassBorder, NavigationShape),
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .padding(horizontal = NavigationBackgroundHorizontalInset)
                    .fillMaxSize(),
            ) {
                val trackWidthPx = with(density) { maxWidth.toPx() }
                val trackHeightPx = with(density) { maxHeight.toPx() }
                val itemWidth = maxWidth / destinations.size
                val itemWidthPx = with(density) { itemWidth.toPx() }
                val latestItemWidthPx by rememberUpdatedState(itemWidthPx)
                val indicatorHorizontalInset = IndicatorEdgeInset
                val indicatorWidth = (itemWidth - indicatorHorizontalInset * 2f)
                    .coerceAtLeast(0.dp)
                val indicatorWidthPx = with(density) { indicatorWidth.toPx() }
                val restingOffsetPx = selectedIndex * itemWidthPx
                LaunchedEffect(itemWidthPx) {
                    motion.snapTo(latestSelectedIndex * itemWidthPx)
                }
                LaunchedEffect(restingOffsetPx, reduceMotion) {
                    if (!motion.isDragging) {
                        motion.settleTo(restingOffsetPx, reduceMotion)
                    }
                }
                val indicatorHorizontalInsetPx = with(density) {
                    indicatorHorizontalInset.toPx()
                }
                val stretchVelocityPxPerSecond = with(density) { 2200.dp.toPx() }
                val indicatorPressed = pressedIndex == selectedIndex
                val reflectionProgress by animateFloatAsState(
                    targetValue = if (isDragging || indicatorPressed) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = if (isDragging || indicatorPressed) 280 else 200,
                        easing = NavigationMaterialEasing,
                    ),
                    label = "bottomNavigationReflectionProgress",
                )
                val refractionProgress by animateFloatAsState(
                    targetValue = when {
                        isDragging -> 1f
                        indicatorPressed -> 0.18f
                        else -> 0f
                    },
                    animationSpec = tween(
                        durationMillis = if (isDragging || indicatorPressed) 280 else 200,
                        easing = NavigationMaterialEasing,
                    ),
                    label = "bottomNavigationRefractionProgress",
                )
                val stretch = navigationLensStretch(
                    isDragging && !reduceMotion, indicatorPressed && !reduceMotion, dragSpeedRatio,
                )
                val indicatorScaleX by animateFloatAsState(
                    targetValue = stretch.scaleX,
                    animationSpec = spring(NavigationSpringDamping, NavigationSpringStiffness),
                    label = "bottomNavigationIndicatorScaleX",
                )
                val indicatorScaleY by animateFloatAsState(
                    targetValue = stretch.scaleY,
                    animationSpec = spring(NavigationSpringDamping, NavigationSpringStiffness),
                    label = "bottomNavigationIndicatorScaleY",
                )
                val indicatorVerticalInset by animateDpAsState(
                    targetValue = when {
                        reduceMotion -> IndicatorEdgeInset
                        isDragging -> 0.dp
                        pressedIndex == selectedIndex -> 2.dp
                        else -> IndicatorEdgeInset
                    },
                    animationSpec = spring(NavigationSpringDamping, NavigationSpringStiffness),
                    label = "bottomNavigationIndicatorVerticalInset",
                )
                val glassScaleX = indicatorScaleX.coerceIn(0.94f, 1.46f)
                val glassScaleY = indicatorScaleY.coerceIn(0.94f, 1.3f)
                val glassVerticalInset = indicatorVerticalInset.coerceAtLeast(0.dp)
                val lensGeometry = with(density) {
                    NavigationLensGeometry(
                        trackWidth = maxWidth.toPx(),
                        trackHeight = maxHeight.toPx(),
                        lensWidth = indicatorWidthPx,
                        verticalInset = glassVerticalInset.toPx(),
                        scaleX = glassScaleX,
                        scaleY = glassScaleY,
                        restingInset = indicatorHorizontalInsetPx,
                        edgeOutset = (
                            NavigationBackgroundHorizontalInset +
                                NavigationHorizontalInset - GlassScreenEdgeInset
                            ).toPx(),
                    )
                }
                val latestLensGeometry by rememberUpdatedState(lensGeometry)
                val indicatorOffsetPx = motion.offsetPx
                    .coerceIn(lensGeometry.minOffset, lensGeometry.maxOffset)
                val indicatorLeftPx = indicatorOffsetPx + indicatorHorizontalInsetPx
                val indicatorBounds = lensGeometry.boundsAt(indicatorOffsetPx)
                val indicatorMask = remember(indicatorBounds) {
                    Path().apply {
                        addRoundRect(
                            RoundRect(
                                rect = indicatorBounds,
                                cornerRadius = lensGeometry.cornerRadius,
                            ),
                        )
                    }
                }
                val selectionPosition = if (itemWidthPx > 0f) {
                    (indicatorOffsetPx / itemWidthPx)
                        .coerceIn(0f, destinations.lastIndex.toFloat())
                } else {
                    selectedIndex.toFloat()
                }
                fun dragBy(delta: Float) {
                    lastDragEventTime = SystemClock.uptimeMillis()
                    dragDistancePx += delta
                    dragVelocityTracker.addPosition(lastDragEventTime, Offset(dragDistancePx, 0f))
                    dragSpeedRatio = (
                        abs(dragVelocityTracker.calculateVelocity().x) / stretchVelocityPxPerSecond
                        ).coerceIn(0f, 1f)
                    val geometry = latestLensGeometry
                    motion.dragBy(delta, geometry.minOffset, geometry.maxOffset)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .selectableGroup()
                        .pointerInput(itemWidthPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val geometry = latestLensGeometry
                                val visibleOffset = motion.offsetPx.coerceIn(geometry.minOffset, geometry.maxOffset)
                                val hitBounds = geometry.boundsAt(visibleOffset)
                                if (down.position.x !in hitBounds.left..hitBounds.right) {
                                    return@awaitEachGesture
                                }
                                // Freeze a settling lens at its visible position before tracking a new finger.
                                motion.capture(visibleOffset)
                                var released = false
                                var dragged = false
                                try {
                                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                                        change.consume()
                                    } ?: return@awaitEachGesture
                                    dragged = true
                                    motion.beginDrag()
                                    dragDistancePx = 0f
                                    dragSpeedRatio = 0f
                                    lastDragEventTime = SystemClock.uptimeMillis()
                                    dragVelocityTracker.resetTracking()
                                    dragVelocityTracker.addPosition(lastDragEventTime, Offset.Zero)
                                    dragBy(start.position.x - down.position.x)
                                    released = horizontalDrag(start.id) { change ->
                                        dragBy(change.positionChange().x)
                                        change.consume()
                                    }
                                } finally {
                                    val destinationIndex = if (dragged && released) {
                                        nearestNavigationIndex(motion.offsetPx, itemWidthPx, destinations.size)
                                    } else latestSelectedIndex
                                    dragSpeedRatio = 0f
                                    motion.settleTo(
                                        destinationIndex * latestItemWidthPx, latestReduceMotion,
                                    )
                                    if (dragged && released) {
                                        latestOnDestinationSelected(destinations[destinationIndex])
                                    }
                                }
                            }
                        },
                ) {
                    // Hide originals under the lens, but record the unmasked row on its own canvas.
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                clipPath(indicatorMask, ClipOp.Difference) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                            .layerBackdrop(navigationItemsBackdrop),
                    ) {
                        destinations.forEachIndexed { index, destination ->
                            val committedSelected = selectedIndex == index
                            val selectionInfluence =
                                (1f - abs(index - selectionPosition)).coerceIn(0f, 1f)
                            val label = stringResource(destination.labelRes)
                            val itemPressOffset by animateDpAsState(
                                targetValue = if (pressedIndex == index) 2.dp else 0.dp,
                                animationSpec = spring(NavigationSpringDamping, NavigationSpringStiffness),
                                label = "bottomNavigationItemOffset$index",
                            )
                            val itemColor = lerp(
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                MaterialTheme.colorScheme.secondary,
                                if (isDragging) (selectionInfluence * 2f).coerceAtMost(1f)
                                    else selectionInfluence,
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .selectable(
                                        selected = committedSelected,
                                        interactionSource = interactions[index],
                                        indication = null,
                                        role = Role.Tab,
                                        onClick = { latestOnDestinationSelected(destination) },
                                    )
                                    .then(
                                        if (focusedStates[index].value) Modifier.border(
                                            1.dp,
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                                            IndicatorShape,
                                        ) else Modifier,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                NavigationItemVisual(
                                    destination = destination,
                                    label = label,
                                    selected = selectionInfluence >= 0.5f,
                                    tint = itemColor,
                                    iconSize = 22.dp,
                                    modifier = Modifier.offset(y = itemPressOffset),
                                )
                            }
                        }
                    }

                    val indicatorPlacement = Modifier
                        .width(indicatorWidth)
                        .fillMaxHeight()
                        .offset(x = with(density) { indicatorLeftPx.toDp() })
                        .padding(vertical = glassVerticalInset)
                    val progress = reflectionProgress.coerceIn(0f, 1f)
                    val opticalProgress = refractionProgress.coerceIn(0f, 1f)
                    val glassColor = lerp(restingGlassColor, movingGlassColor, progress * 0.75f)
                    val rimBackdrop = rememberBackdrop(backdrop) { drawBackdrop ->
                        drawRect(glassColor)
                        // Sample around all four track edges, never through the central surface.
                        clipRect(
                            left = -indicatorBounds.left / glassScaleX,
                            top = -indicatorBounds.top / glassScaleY,
                            right = (trackWidthPx - indicatorBounds.left) / glassScaleX,
                            bottom = (trackHeightPx - indicatorBounds.top) / glassScaleY,
                            clipOp = ClipOp.Difference,
                        ) {
                            drawBackdrop()
                        }
                    }
                    val indicatorBackdrop = rememberCombinedBackdrop(rimBackdrop, navigationItemsBackdrop)
                    Box(
                        modifier = indicatorPlacement
                            .drawBackdrop(
                                backdrop = indicatorBackdrop,
                                shape = { IndicatorShape },
                                effects = {
                                    lens(
                                        (4f + 14f * opticalProgress).dp.toPx(),
                                        (28f * opticalProgress).dp.toPx(),
                                        depthEffect = true,
                                    )
                                },
                                // Backdrop's material APIs keep lighting separate from glyph refraction.
                                highlight = {
                                    Highlight(
                                        width = 0.9.dp,
                                        blurRadius = 0.45.dp,
                                        alpha = 0.55f + 0.4f * progress,
                                        style = HighlightStyle.Default(
                                            angle = 45f + 30f * progress *
                                                (selectionPosition - destinations.lastIndex / 2f),
                                            falloff = 1.4f,
                                        ),
                                    )
                                },
                                shadow = null,
                                innerShadow = {
                                    if (progress > 0f) {
                                        InnerShadow(
                                            radius = 4.dp,
                                            offset = DpOffset(0.dp, 2.dp),
                                            color = Color.Black.copy(
                                                alpha = if (darkTheme) 0.24f else 0.12f,
                                            ),
                                            alpha = progress,
                                        )
                                    } else {
                                        null
                                    }
                                },
                                layerBlock = {
                                    scaleX = glassScaleX
                                    scaleY = glassScaleY
                                },
                                onDrawBackdrop = { drawBackdrop ->
                                    scale(1f + 0.04f * opticalProgress) {
                                        drawBackdrop()
                                    }
                                },
                            ),
                    )
                }
            }
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
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
