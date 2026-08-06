package com.v2ray.ang.ui.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.v2ray.ang.ui.compose.LocalDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.round

private const val TARGET_ANIMATION_FRAME_RATE = 120f
private const val VIEWPORT_LONGITUDE_DEGREES = 108f
private const val MAP_CENTER_Y_FRACTION = 0.59f
private const val GRID_STEP_DEGREES = 20

private data class MapCoordinate(
    val longitude: Float,
    val latitude: Float,
)

private data class MapRing(
    val coordinates: List<MapCoordinate>,
    val centerLongitude: Float,
    val minLongitude: Float,
    val maxLongitude: Float,
    val minLatitude: Float,
    val maxLatitude: Float,
)

private data class WorldMapGeometry(
    val detailRings: List<MapRing>,
    val animationRings: List<MapRing>,
) {
    companion object {
        val Empty = WorldMapGeometry(emptyList(), emptyList())
    }
}

@Composable
fun TranslucentLocationMap(
    latitude: Double?,
    longitude: Double?,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val isDarkTheme = LocalDarkTheme.current
    val geometry by produceState(initialValue = WorldMapGeometry.Empty, context) {
        value = NaturalEarthMapData.load(context)
    }
    val centerLatitude = remember { Animatable(24f) }
    val centerLongitude = remember { Animatable(105f) }
    var previousTarget by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var isAnimating by remember { mutableStateOf(false) }

    LaunchedEffect(latitude, longitude, view) {
        val targetLatitude = latitude?.toFloat()?.takeIf { it in -90f..90f }
            ?: return@LaunchedEffect
        val targetLongitude = longitude?.toFloat()?.takeIf { it in -180f..180f }
            ?: return@LaunchedEffect
        val oldTarget = previousTarget
        previousTarget = targetLatitude to targetLongitude

        if (oldTarget == null) {
            centerLatitude.snapTo(targetLatitude)
            centerLongitude.snapTo(targetLongitude)
            return@LaunchedEffect
        }

        requestHighFrameRate(view, enabled = true)
        isAnimating = true
        try {
            val currentLongitude = centerLongitude.value
            val longitudeDelta = normalizeLongitude(targetLongitude - currentLongitude)
            coroutineScope {
                listOf(
                    async {
                        centerLatitude.animateTo(
                            targetLatitude,
                            animationSpec = tween(1_150, easing = FastOutSlowInEasing),
                        )
                    },
                    async {
                        centerLongitude.animateTo(
                            currentLongitude + longitudeDelta,
                            animationSpec = tween(1_350, easing = FastOutSlowInEasing),
                        )
                        centerLongitude.snapTo(targetLongitude)
                    },
                ).awaitAll()
            }
        } finally {
            isAnimating = false
            requestHighFrameRate(view, enabled = false)
        }
    }

    val landColor = when {
        isDarkTheme && isConnected -> Color(0xA06CD2A4)
        isDarkTheme -> Color(0x9067AFCB)
        isConnected -> Color(0x80519B7F)
        else -> Color(0x70618FA0)
    }
    val borderColor = when {
        isDarkTheme && isConnected -> Color(0xB596E5C4)
        isDarkTheme -> Color(0xA17CC6E0)
        isConnected -> Color(0x99507868)
        else -> Color(0x9952707B)
    }
    val gridColor = if (isDarkTheme) {
        Color.White.copy(alpha = 0.08f)
    } else {
        Color(0xFF23434A).copy(alpha = 0.10f)
    }
    val markerColor = if (isConnected) Color(0xFF56D89A) else Color(0xFF62B7DC)
    val markerCenterColor = if (isDarkTheme) Color.White else Color(0xFFF8FFFF)

    Canvas(modifier = modifier) {
        val scale = size.width / VIEWPORT_LONGITUDE_DEGREES
        val mapCenter = Offset(size.width / 2f, size.height * MAP_CENTER_Y_FRACTION)
        val currentLatitude = centerLatitude.value
        val currentLongitude = centerLongitude.value

        drawMapGrid(
            centerLatitude = currentLatitude,
            centerLongitude = currentLongitude,
            mapCenter = mapCenter,
            scale = scale,
            color = gridColor,
        )

        val landPath = Path()
        val rings = if (isAnimating) geometry.animationRings else geometry.detailRings
        rings.forEach { ring ->
            appendMapRing(
                path = landPath,
                ring = ring,
                centerLatitude = currentLatitude,
                centerLongitude = currentLongitude,
                mapCenter = mapCenter,
                scale = scale,
                viewportWidth = size.width,
                viewportHeight = size.height,
            )
        }
        drawPath(landPath, landColor)
        if (!isAnimating) {
            drawPath(landPath, borderColor, style = Stroke(width = 0.7f))
        }

        if (latitude != null && longitude != null) {
            val markerLongitudeDelta = normalizeLongitude(longitude.toFloat() - currentLongitude)
            val marker = Offset(
                x = mapCenter.x + markerLongitudeDelta * scale,
                y = mapCenter.y - (latitude.toFloat() - currentLatitude) * scale,
            )
            if (marker.x in -24f..size.width + 24f && marker.y in -24f..size.height + 24f) {
                drawCircle(markerColor.copy(alpha = 0.13f), 25f, marker)
                drawCircle(markerColor.copy(alpha = 0.28f), 14f, marker)
                drawCircle(markerCenterColor, 5.5f, marker)
                drawCircle(markerColor, 3.2f, marker)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMapGrid(
    centerLatitude: Float,
    centerLongitude: Float,
    mapCenter: Offset,
    scale: Float,
    color: Color,
) {
    val firstLatitude = floor(
        (centerLatitude - mapCenter.y / scale) / GRID_STEP_DEGREES
    ).toInt() * GRID_STEP_DEGREES
    var latitude = firstLatitude
    while (latitude * scale <= centerLatitude * scale + (size.height - mapCenter.y)) {
        if (latitude in -80..80) {
            val y = mapCenter.y - (latitude - centerLatitude) * scale
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 0.7f,
            )
        }
        latitude += GRID_STEP_DEGREES
    }

    val firstLongitude = floor(
        (centerLongitude - mapCenter.x / scale) / GRID_STEP_DEGREES
    ).toInt() * GRID_STEP_DEGREES
    var longitude = firstLongitude
    while (longitude * scale <= centerLongitude * scale + (size.width - mapCenter.x)) {
        val x = mapCenter.x + (longitude - centerLongitude) * scale
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 0.7f,
            cap = StrokeCap.Round,
        )
        longitude += GRID_STEP_DEGREES
    }
}

private fun appendMapRing(
    path: Path,
    ring: MapRing,
    centerLatitude: Float,
    centerLongitude: Float,
    mapCenter: Offset,
    scale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
) {
    val nearestWrap = round((centerLongitude - ring.centerLongitude) / 360f) * 360f
    for (wrapOffset in floatArrayOf(nearestWrap - 360f, nearestWrap, nearestWrap + 360f)) {
        val minX = mapCenter.x + (ring.minLongitude + wrapOffset - centerLongitude) * scale
        val maxX = mapCenter.x + (ring.maxLongitude + wrapOffset - centerLongitude) * scale
        val minY = mapCenter.y - (ring.maxLatitude - centerLatitude) * scale
        val maxY = mapCenter.y - (ring.minLatitude - centerLatitude) * scale
        if (maxX < -8f || minX > viewportWidth + 8f || maxY < -8f || minY > viewportHeight + 8f) {
            continue
        }

        ring.coordinates.forEachIndexed { index, coordinate ->
            val point = Offset(
                x = mapCenter.x + (coordinate.longitude + wrapOffset - centerLongitude) * scale,
                y = mapCenter.y - (coordinate.latitude - centerLatitude) * scale,
            )
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()
    }
}

private fun normalizeLongitude(value: Float): Float = ((value + 180f) % 360f + 360f) % 360f - 180f

private fun requestHighFrameRate(view: View, enabled: Boolean) {
    val frameRate = if (enabled) TARGET_ANIMATION_FRAME_RATE else 0f
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        view.setRequestedFrameRate(frameRate)
        return
    }

    val activity = view.context.findActivity() ?: return
    activity.window.attributes = activity.window.attributes.apply {
        preferredRefreshRate = frameRate
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private object NaturalEarthMapData {
    @Volatile
    private var cached: WorldMapGeometry? = null

    suspend fun load(context: Context): WorldMapGeometry {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            cached ?: parse(context).also { cached = it }
        }
    }

    private fun parse(context: Context): WorldMapGeometry {
        val json = context.assets.open("natural_earth_countries.geojson")
            .bufferedReader()
            .use { it.readText() }
        val features = JSONObject(json).getJSONArray("features")
        val detailRings = ArrayList<MapRing>()
        val animationRings = ArrayList<MapRing>()

        for (index in 0 until features.length()) {
            val geometry = features.getJSONObject(index).optJSONObject("geometry") ?: continue
            val coordinates = geometry.optJSONArray("coordinates") ?: continue
            when (geometry.optString("type")) {
                "Polygon" -> addPolygon(coordinates, detailRings, animationRings)
                "MultiPolygon" -> {
                    for (polygonIndex in 0 until coordinates.length()) {
                        addPolygon(
                            polygon = coordinates.getJSONArray(polygonIndex),
                            detailRings = detailRings,
                            animationRings = animationRings,
                        )
                    }
                }
            }
        }
        return WorldMapGeometry(detailRings, animationRings)
    }

    private fun addPolygon(
        polygon: JSONArray,
        detailRings: MutableList<MapRing>,
        animationRings: MutableList<MapRing>,
    ) {
        if (polygon.length() == 0) return
        val outerRing = polygon.getJSONArray(0)
        val coordinates = ArrayList<MapCoordinate>(outerRing.length())
        var previousRawLongitude: Float? = null
        var unwrappedLongitude = 0f

        for (index in 0 until outerRing.length()) {
            val point = outerRing.getJSONArray(index)
            if (point.length() < 2) continue
            val rawLongitude = point.getDouble(0).toFloat()
            unwrappedLongitude = if (previousRawLongitude == null) {
                rawLongitude
            } else {
                unwrappedLongitude + normalizeLongitude(rawLongitude - previousRawLongitude)
            }
            previousRawLongitude = rawLongitude
            coordinates += MapCoordinate(
                longitude = unwrappedLongitude,
                latitude = point.getDouble(1).toFloat(),
            )
        }

        if (coordinates.size < 3) return
        val detailRing = createRing(coordinates)
        detailRings += detailRing
        animationRings += createRing(simplifyForAnimation(coordinates))
    }

    private fun simplifyForAnimation(coordinates: List<MapCoordinate>): List<MapCoordinate> {
        val stride = when {
            coordinates.size > 80 -> 8
            coordinates.size > 32 -> 5
            coordinates.size > 16 -> 3
            coordinates.size > 8 -> 2
            else -> return coordinates
        }
        return buildList {
            for (index in coordinates.indices step stride) add(coordinates[index])
        }
    }

    private fun createRing(coordinates: List<MapCoordinate>): MapRing {
        val longitudes = coordinates.map(MapCoordinate::longitude)
        val latitudes = coordinates.map(MapCoordinate::latitude)
        return MapRing(
            coordinates = coordinates,
            centerLongitude = (longitudes.min() + longitudes.max()) / 2f,
            minLongitude = longitudes.min(),
            maxLongitude = longitudes.max(),
            minLatitude = latitudes.min(),
            maxLatitude = latitudes.max(),
        )
    }
}
