/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/


// this is absolutely unnecessary, why did I make this. a simple toggle would've sufficed

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.screens

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.StyledButton
import me.kavishdevar.librepods.composables.StyledIconButton
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.composables.StyledToggle
import me.kavishdevar.librepods.services.ServiceManager
import me.kavishdevar.librepods.utils.HeadTracking
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@ExperimentalHazeMaterialsApi
@RequiresApi(Build.VERSION_CODES.Q)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun HeadTrackingScreen() {
    DisposableEffect(Unit) {
        ServiceManager.getService()?.startHeadTracking()
        onDispose {
            ServiceManager.getService()?.stopHeadTracking()
        }
    }
    val isDarkTheme = isSystemInDarkTheme()
    if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isDarkTheme) Color.White else Color.Black

    val scrollState = rememberScrollState()
    val backdrop = rememberLayerBackdrop()
    StyledScaffold(
        title = stringResource(R.string.head_tracking),
        actionButtons = listOf(
            { scaffoldBackdrop ->
               var isActive by remember { mutableStateOf(ServiceManager.getService()?.isHeadTrackingActive == true) }
                StyledIconButton(
                    onClick = {
                        if (ServiceManager.getService()?.isHeadTrackingActive == false) {
                            ServiceManager.getService()?.startHeadTracking()
                            Log.d("HeadTrackingScreen", "Head tracking started")
                        } else {
                            ServiceManager.getService()?.stopHeadTracking()
                            Log.d("HeadTrackingScreen", "Head tracking stopped")
                        }
                    },
                    icon = if (isActive) "􀊅" else "􀊃",
                    darkMode = isDarkTheme,
                    backdrop = scaffoldBackdrop
                )
            }
        ),
    ) { spacerHeight, hazeState ->
        val sharedPreferences = LocalContext.current.getSharedPreferences("settings", Context.MODE_PRIVATE)

        var gestureText by remember { mutableStateOf("") }
        val coroutineScope = rememberCoroutineScope()

        var lastClickTime by remember { mutableLongStateOf(0L) }
        var shouldExplode by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column (
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeSource(state = hazeState)
                    .layerBackdrop(backdrop)
                    .padding(top = 8.dp)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(scrollState)
            ) {
                Spacer(modifier = Modifier.height(spacerHeight))
                StyledToggle(
                    label = "Head Gestures",
                    sharedPreferences = sharedPreferences,
                    sharedPreferenceKey = "head_gestures",
                )

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    stringResource(R.string.head_gestures_details),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = textColor.copy(0.6f)
                    ),
                    modifier = Modifier.padding(start = 4.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Head Orientation",
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = textColor
                    ),
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 8.dp)
                )
                HeadVisualization()

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Velocity",
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = textColor
                    ),
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 8.dp)
                )
                AccelerationPlot()

                Spacer(modifier = Modifier.height(16.dp))

                LaunchedEffect(gestureText) {
                    if (gestureText.isNotEmpty()) {
                        lastClickTime = System.currentTimeMillis()
                        delay(3000)
                        if (System.currentTimeMillis() - lastClickTime >= 3000) {
                            shouldExplode = true
                        }
                    }
                }
            }
            val gestureTextValue = stringResource(R.string.shake_your_head_or_nod)
            StyledButton(
                onClick = {
                    gestureText = gestureTextValue
                    coroutineScope.launch {
                        val accepted = ServiceManager.getService()?.testHeadGestures() ?: false
                        gestureText = if (accepted) "\"Yes\" gesture detected." else "\"No\" gesture detected."
                    }
                },
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(0.75f),
                maxScale = 0.05f
            ) {
                Text(
                    "Test Head Gestures",
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = textColor
                    ),
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)
            ) {
                AnimatedContent(
                    targetState = gestureText,
                    transitionSpec = {
                        (fadeIn(
                            animationSpec = tween(300)
                        ) + slideInVertically(
                            initialOffsetY = { 40 },
                            animationSpec = tween(300)
                        )).togetherWith(fadeOut(animationSpec = tween(150)))
                    }
                ) { text ->
                    if (shouldExplode) {
                        LaunchedEffect(Unit) {
                            CoroutineScope(coroutineScope.coroutineContext).launch {
                                delay(750)
                                gestureText = ""
                            }
                        }
                        ParticleText(
                            text = text,
                            style = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor,
                                textAlign = TextAlign.Center
                            ),
                            onAnimationComplete = {
                                shouldExplode = false
                            },
                        )
                    } else {
                        Text(
                            text = text,
                            style = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor,
                                textAlign = TextAlign.Center
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
private data class Particle(
    val initialPosition: Offset,
    val velocity: Offset,
    var alpha: Float = 1f
)

@Composable
private fun ParticleText(
    text: String,
    style: TextStyle,
    onAnimationComplete: () -> Unit,
) {
    val particles = remember { mutableStateListOf<Particle>() }
    val textMeasurer = rememberTextMeasurer()
    var isAnimating by remember { mutableStateOf(true) }
    var textVisible by remember { mutableStateOf(true) }

    Canvas(modifier = Modifier.fillMaxWidth()) {
        val textLayoutResult = textMeasurer.measure(text, style)
        val textBounds = textLayoutResult.size
        val centerX = (size.width - textBounds.width) / 2
        val centerY = size.height / 2

        if (textVisible && particles.isEmpty()) {
            drawText(
                textMeasurer = textMeasurer,
                text = text,
                style = style,
                topLeft = Offset(centerX, centerY - textBounds.height / 2)
            )
        }

        if (particles.isEmpty()) {
            val random = Random(System.currentTimeMillis())
            for (@Suppress("Unused")i in 0..100) {
                val x = centerX + random.nextFloat() * textBounds.width
                val y = centerY - textBounds.height / 2 + random.nextFloat() * textBounds.height
                val vx = (random.nextFloat() - 0.5f) * 20
                val vy = (random.nextFloat() - 0.5f) * 20
                particles.add(Particle(Offset(x, y), Offset(vx, vy)))
            }
        }

        particles.forEach { particle ->
            drawCircle(
                color = style.color.copy(alpha = particle.alpha),
                radius = 0.5.dp.toPx(),
                center = particle.initialPosition
            )
        }
    }

    LaunchedEffect(text) {
        while (isAnimating) {
            delay(16)
            particles.forEachIndexed { index, particle ->
                particles[index] = particle.copy(
                    initialPosition = particle.initialPosition + particle.velocity,
                    alpha = (particle.alpha - 0.02f).coerceAtLeast(0f)
                )
            }

            if (particles.all { it.alpha <= 0f }) {
                isAnimating = false
                onAnimationComplete()
            }
        }
    }
}

@Composable
private fun HeadVisualization() {
    val orientation by HeadTracking.orientation.collectAsState()
    val darkTheme = isSystemInDarkTheme()
    val backgroundColor = if (darkTheme) Color(0xFF1C1C1E) else Color.White
    val strokeColor = if (darkTheme) Color.White else Color.Black

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                val width = size.width
                val height = size.height
                val center = Offset(width / 2, height / 2)
                val faceRadius = height * 0.35f

                val pitch = Math.toRadians(orientation.pitch.toDouble())
                val yaw = Math.toRadians(orientation.yaw.toDouble())

                val cosY = cos(yaw).toFloat()
                val sinY = sin(yaw).toFloat()
                val cosP = cos(pitch).toFloat()
                val sinP = sin(pitch).toFloat()

                fun rotate3D(point: Triple<Float, Float, Float>): Triple<Float, Float, Float> {
                    val (x, y, z) = point
                    val x1 = x * cosY - z * sinY
                    val z1 = x * sinY + z * cosY

                    val y2 = y * cosP - z1 * sinP
                    val z2 = y * sinP + z1 * cosP

                    return Triple(x1, y2, z2)
                }

                fun project(point: Triple<Float, Float, Float>): Pair<Float, Float> {
                    val (x, y, z) = point
                    val scale = 1f + (z / width)
                    return Pair(center.x + x * scale, center.y + y * scale)
                }

                val earWidth = height * 0.08f
                val earHeight = height * 0.2f
                val earOffsetX = height * 0.4f
                val earOffsetY = 0f
                val earZ = 0f

                for (xSign in listOf(-1f, 1f)) {
                    val rotated = rotate3D(Triple(earOffsetX * xSign, earOffsetY, earZ))
                    val (earX, earY) = project(rotated)
                    drawRoundRect(
                        color = strokeColor,
                        topLeft = Offset(earX - earWidth/2, earY - earHeight/2),
                        size = Size(earWidth, earHeight),
                        cornerRadius = CornerRadius(earWidth/2),
                        style = Stroke(width = 4.dp.toPx())
                    )
                }

                val spherePath = Path()
                val firstPoint = project(rotate3D(Triple(faceRadius, 0f, 0f)))
                spherePath.moveTo(firstPoint.first, firstPoint.second)

                for (i in 1..32) {
                    val angle = (i * 2 * Math.PI / 32).toFloat()
                    val point = project(rotate3D(Triple(
                        cos(angle) * faceRadius,
                        sin(angle) * faceRadius,
                        0f
                    )))
                    spherePath.lineTo(point.first, point.second)
                }
                spherePath.close()

                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        style = android.graphics.Paint.Style.FILL
                        shader = android.graphics.RadialGradient(
                            center.x + sinY * faceRadius * 0.3f,
                            center.y - sinP * faceRadius * 0.3f,
                            faceRadius * 1.4f,
                            intArrayOf(
                                backgroundColor.copy(alpha = 1f).toArgb(),
                                backgroundColor.copy(alpha = 0.95f).toArgb(),
                                backgroundColor.copy(alpha = 0.9f).toArgb(),
                                backgroundColor.copy(alpha = 0.8f).toArgb(),
                                backgroundColor.copy(alpha = 0.7f).toArgb()
                            ),
                            floatArrayOf(0.3f, 0.5f, 0.7f, 0.8f, 1f),
                            android.graphics.Shader.TileMode.CLAMP
                        )
                    }
                    drawPath(spherePath.asAndroidPath(), paint)

                    val highlightPaint = android.graphics.Paint().apply {
                        style = android.graphics.Paint.Style.FILL
                        shader = android.graphics.RadialGradient(
                            center.x - faceRadius * 0.4f - sinY * faceRadius * 0.5f,
                            center.y - faceRadius * 0.4f - sinP * faceRadius * 0.5f,
                            faceRadius * 0.9f,
                            intArrayOf(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.argb(100, 255, 255, 255),
                                android.graphics.Color.TRANSPARENT
                            ),
                            floatArrayOf(0f, 0.3f, 1f),
                            android.graphics.Shader.TileMode.CLAMP
                        )
                        alpha = if (darkTheme) 30 else 60
                    }
                    drawPath(spherePath.asAndroidPath(), highlightPaint)

                    val secondaryHighlightPaint = android.graphics.Paint().apply {
                        style = android.graphics.Paint.Style.FILL
                        shader = android.graphics.RadialGradient(
                            center.x + faceRadius * 0.3f + sinY * faceRadius * 0.3f,
                            center.y + faceRadius * 0.3f - sinP * faceRadius * 0.3f,
                            faceRadius * 0.7f,
                            intArrayOf(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT
                            ),
                            floatArrayOf(0f, 1f),
                            android.graphics.Shader.TileMode.CLAMP
                        )
                        alpha = if (darkTheme) 15 else 30
                    }
                    drawPath(spherePath.asAndroidPath(), secondaryHighlightPaint)

                    val shadowPaint = android.graphics.Paint().apply {
                        style = android.graphics.Paint.Style.FILL
                        shader = android.graphics.RadialGradient(
                            center.x + sinY * faceRadius * 0.5f,
                            center.y - sinP * faceRadius * 0.5f,
                            faceRadius * 1.1f,
                            intArrayOf(
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.BLACK
                            ),
                            floatArrayOf(0.7f, 1f),
                            android.graphics.Shader.TileMode.CLAMP
                        )
                        alpha = if (darkTheme) 40 else 20
                    }
                    drawPath(spherePath.asAndroidPath(), shadowPaint)
                }

                drawPath(
                    path = spherePath,
                    color = strokeColor,
                    style = Stroke(width = 4.dp.toPx())
                )

                val smileRadius = faceRadius * 0.5f
                val smileStartAngle = -340f
                val smileSweepAngle = 140f
                val smileOffsetY = faceRadius * 0.1f

                val smilePath = Path()
                for (i in 0..32) {
                    val angle = Math.toRadians(smileStartAngle + (smileSweepAngle * i / 32.0))
                    val x = cos(angle.toFloat()) * smileRadius
                    val y = sin(angle.toFloat()) * smileRadius + smileOffsetY

                    val rotated = rotate3D(Triple(x, y, 0f))
                    val projected = project(rotated)

                    if (i == 0) {
                        smilePath.moveTo(projected.first, projected.second)
                    } else {
                        smilePath.lineTo(projected.first, projected.second)
                    }
                }

                drawPath(
                    path = smilePath,
                    color = strokeColor,
                    style = Stroke(
                        width = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )

                val eyeOffsetX = height * 0.15f
                val eyeOffsetY = height * 0.1f
                val eyeLength = height * 0.08f

                for (xSign in listOf(-1f, 1f)) {
                    val rotated = rotate3D(Triple(eyeOffsetX * xSign, -eyeOffsetY, 0f))
                    val (eyeX, eyeY) = project(rotated)
                    drawLine(
                        color = strokeColor,
                        start = Offset(eyeX, eyeY - eyeLength/2),
                        end = Offset(eyeX, eyeY + eyeLength/2),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        textSize = 12.sp.toPx()
                        textAlign = android.graphics.Paint.Align.RIGHT
                        typeface = android.graphics.Typeface.create(
                            "SF Pro",
                            android.graphics.Typeface.NORMAL
                        )
                    }

                    val pitch = orientation.pitch.toInt()
                    val yaw = orientation.yaw.toInt()
                    val text = "Pitch: ${pitch}° Yaw: ${yaw}°"

                    drawText(
                        text,
                        width - 8.dp.toPx(),
                        height - 8.dp.toPx(),
                        paint
                    )
                }
            }
        }
    }
}

@Composable
private fun AccelerationPlot() {
    val acceleration by HeadTracking.acceleration.collectAsState()
    val maxPoints = 100
    val points = remember { mutableStateListOf<Pair<Float, Float>>() }
    val darkTheme = isSystemInDarkTheme()

    var maxAbs by remember { mutableFloatStateOf(1000f) }

    LaunchedEffect(acceleration) {
        points.add(Pair(acceleration.horizontal, acceleration.vertical))
        if (points.size > maxPoints) {
            points.removeAt(0)
        }

        val currentMax = points.maxOf { maxOf(abs(it.first), abs(it.second)) }
        maxAbs = maxOf(currentMax * 1.2f, 1000f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (darkTheme) Color(0xFF1C1C1E) else Color.White
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val width = size.width
                val height = size.height
                val xScale = width / maxPoints
                val yScale = (height - 40.dp.toPx()) / (maxAbs * 2)
                val zeroY = height / 2

                val gridColor = if (darkTheme) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.1f)

                for (i in 0..maxPoints step 10) {
                    val x = i * xScale
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f),
                        end = Offset(x, height),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val gridStep = maxAbs / 4
                for (value in (-maxAbs.toInt()..maxAbs.toInt()) step gridStep.toInt()) {
                    val y = zeroY - value * yScale
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                drawLine(
                    color = if (darkTheme) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.3f),
                    start = Offset(0f, zeroY),
                    end = Offset(width, zeroY),
                    strokeWidth = 1.5f.dp.toPx()
                )

                if (points.size > 1) {
                    for (i in 0 until points.size - 1) {
                        val x1 = i * xScale
                        val x2 = (i + 1) * xScale

                        drawLine(
                            color = Color(0xFF007AFF),
                            start = Offset(x1, zeroY - points[i].first * yScale),
                            end = Offset(x2, zeroY - points[i + 1].first * yScale),
                            strokeWidth = 2.dp.toPx()
                        )

                        drawLine(
                            color = Color(0xFFFF3B30),
                            start = Offset(x1, zeroY - points[i].second * yScale),
                            end = Offset(x2, zeroY - points[i + 1].second * yScale),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }

                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        textSize = 12.sp.toPx()
                        textAlign = android.graphics.Paint.Align.RIGHT
                    }

                    drawText("${maxAbs.toInt()}", 30.dp.toPx(), 20.dp.toPx(), paint)
                    drawText("0", 30.dp.toPx(), height/2, paint)
                    drawText("-${maxAbs.toInt()}", 30.dp.toPx(), height - 10.dp.toPx(), paint)
                }

                val legendY = 15.dp.toPx()
                val textOffsetY = legendY + 5.dp.toPx() / 2

                drawCircle(Color(0xFF007AFF), 5.dp.toPx(), Offset(width - 150.dp.toPx(), legendY))
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        textSize = 12.sp.toPx()
                        textAlign = android.graphics.Paint.Align.LEFT
                    }
                    drawText("Horizontal", width - 140.dp.toPx(), textOffsetY, paint)
                }

                drawCircle(Color(0xFFFF3B30), 5.dp.toPx(), Offset(width - 70.dp.toPx(), legendY))
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        textSize = 12.sp.toPx()
                        textAlign = android.graphics.Paint.Align.LEFT
                    }
                    drawText("Vertical", width - 60.dp.toPx(), textOffsetY, paint)
                }
            }
        }
    }
}

@ExperimentalHazeMaterialsApi
@RequiresApi(Build.VERSION_CODES.Q)
@Preview
@Composable
fun HeadTrackingScreenPreview() {
    HeadTrackingScreen()
}
