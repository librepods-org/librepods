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

package me.kavishdevar.librepods.presentation.screens.apple

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.utils.HeadTracking
import me.kavishdevar.librepods.core.utils.PostureTracker
import me.kavishdevar.librepods.data.app.ColorStyle
import me.kavishdevar.librepods.data.app.DesignSystem
import me.kavishdevar.librepods.presentation.components.primitives.MaterialButtonStyle
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledIconButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.design.LocalDesign
import me.kavishdevar.librepods.presentation.design.sectionHeader
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.icons.MaterialIcons
import me.kavishdevar.librepods.presentation.utils.LocalDebugMode
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Composable
fun HeadTrackingScreen(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?,
    navigateToPurchase: () -> Unit
) {
    val design = LocalDesign.current

    val uiState by viewModel.uiState.collectAsState()

    val state = uiState.state
    val settings = uiState.settings

    DisposableEffect(Unit) {
        viewModel.startHeadTracking()
        onDispose {
            viewModel.stopHeadTracking()
        }
    }

    val backdrop = rememberLayerBackdrop()

    var gestureText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    var lastClickTime by remember { mutableLongStateOf(0L) }

    val scrollState = rememberScrollState()

    StyledScaffold(
        title = stringResource(R.string.head_gestures),
        navigateBack = navigateBack,
        actionButtons = listOf(
            { hazeInput ->
                if (design.system == DesignSystem.Material) {
                    FilledTonalIconToggleButton(
                        checked = state.headTrackingState == BuddyState.ACTIVE,
                        onCheckedChange = { if (it) viewModel.startHeadTracking() else viewModel.stopHeadTracking() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Uniform)),
                        shape = IconButtonDefaults.mediumRoundShape,
                        enabled = state.headTrackingState != BuddyState.WAITING
                    ) {
                        Icon(
                            imageVector = if (state.headTrackingState == BuddyState.ACTIVE) MaterialIcons.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                        )
                    }
                } else {
                    StyledIconButton(
                        onClick = if (state.headTrackingState != BuddyState.ACTIVE) viewModel::startHeadTracking else viewModel::stopHeadTracking,
                        hazeInput = hazeInput,
                        enabled = state.headTrackingState != BuddyState.WAITING
                    ) {
                        Icon(
                            imageVector = if (state.headTrackingState == BuddyState.ACTIVE) LocalIcons.current.Pause else LocalIcons.current.Play,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        )
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            Column (
                modifier = Modifier
                    .fillMaxWidth()
                    .layerBackdrop(backdrop)
                    .padding(top = 8.dp)
            ) {

                if (!uiState.isPremium) {
                    StyledButton(
                        onClick = navigateToPurchase,
                        backdrop = rememberLayerBackdrop(),
                        modifier = Modifier.fillMaxWidth(),
                        maxScale = 0.05f,
                        surfaceColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            stringResource(R.string.unlock_advanced_features),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                StyledToggle(
                    label = "Posture alert",
                    checked = settings.postureAlertEnabled,
                    onCheckedChange = { viewModel.setPostureAlertEnabled(it) },
                    enabled = uiState.isPremium || settings.postureAlertEnabled,
                    header = true
                )

                Spacer(modifier = Modifier.height(16.dp))
                StyledToggle(
                    label = stringResource(R.string.head_gestures),
                    checked = settings.headGesturesEnabled,
                    onCheckedChange = { viewModel.setHeadGesturesEnabled(it) },
                    enabled = uiState.isPremium || settings.headGesturesEnabled,
                    description = stringResource(R.string.head_gestures_details),
                    header = true
                )

                Spacer(modifier = Modifier.height(16.dp))
                PitchYawRatePlot()

                Spacer(modifier = Modifier.height(16.dp))

                LaunchedEffect(gestureText) {
                    if (gestureText.isNotEmpty()) {
                        lastClickTime = System.currentTimeMillis()
                        delay(3.seconds)
                        if (System.currentTimeMillis() - lastClickTime >= 3000) {
                            gestureText = ""
                        }
                    }
                }
            }
            val gestureTextValue = stringResource(R.string.shake_your_head_or_nod)
            StyledButton(
                onClick = {
                    gestureText = gestureTextValue
                    coroutineScope.launch {
                        viewModel.detectHeadGestures { gestureText = if (it) "\"Yes\" gesture detected." else "\"No\" gesture detected." }
                    }
                },
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth(),
                maxScale = 0.05f,
                materialButtonStyle = MaterialButtonStyle.Outlined
            ) {
                Text(
                    stringResource(R.string.test_head_gestures),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
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
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (LocalDebugMode.current) {
                val neckPosture = PostureTracker.neckPosture.collectAsState()

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = neckPosture.value.toDebugString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                StyledButton(
                    onClick = {
                        HeadTracking.motionReport.value?.let{
                            PostureTracker.calibrateUpright(it)
                        }
                    },
                    materialButtonStyle = MaterialButtonStyle.Outlined,
                ) {
                    Text(
                        text = "recal upright posture",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                QuaternionVisualizer()

                Spacer(modifier = Modifier.height(16.dp))

                val sliderValue = remember {
                    mutableFloatStateOf(settings.headTrackingInterval.inWholeMilliseconds.toFloat())
                }

                LaunchedEffect(sliderValue) {
                    snapshotFlow { sliderValue.floatValue }
                        .debounce(100.milliseconds)
                        .collect { value ->
                            viewModel.setHeadTrackingInterval(value.toInt().milliseconds)
                        }
                }

                StyledSlider(
                    label = "[debug] head tracking interval",
                    value = sliderValue.floatValue,
                    onValueChange = { sliderValue.floatValue = it },
                    valueRange = 10f..200f,
                    snapPoints = listOf(40f),
                    independent = true,
                    description = "how often airpods report sensor information",
                    enabled = uiState.isPremium
                )
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}



@Composable
private fun PitchYawRatePlot() {
    val report by HeadTracking.motionReport.collectAsState()
    val maxPoints = 100
    val points = remember { mutableStateListOf<Pair<Float, Float>>() }
    var maxAbs by remember { mutableFloatStateOf(3.5f) }

    LaunchedEffect(report) {
        val r = report ?: return@LaunchedEffect
        val q = r.quaternion
        if (q.size == 4 && q[0] == 0f && q[1] == 0f && q[2] == 0f && q[3] == 1f) return@LaunchedEffect

        val (rx, ry, rz) = r.rotationRate
        val pitchRate = rz
        val yawRate = (rx * 0.78f) + (ry * 0.62f)

        points.add(Pair(yawRate, pitchRate))
        if (points.size > maxPoints) {
            points.removeAt(0)
        }

        val currentMax = points.maxOfOrNull { maxOf(abs(it.first), abs(it.second)) } ?: 3.5f
        maxAbs = maxOf(currentMax * 1.2f, 2.5f)
    }

    val yawColor = MaterialTheme.colorScheme.primary
    val pitchColor = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface

    val design = LocalDesign.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .background(if (design.colorStyle == ColorStyle.Custom) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp)
                .padding(
                    top = 4.dp,
                    bottom = if (design.system == DesignSystem.Material) 12.dp else 4.dp
                )
        ) {
            Text(
                text = stringResource(R.string.angular_velocity),
                color = if (design.system == DesignSystem.Material) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.sectionHeader,
                style = MaterialTheme.typography.labelMediumEmphasized
            )
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(if (design.system == DesignSystem.Material) 16.dp else 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                val latest = points.lastOrNull() ?: Pair(0f, 0f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LegendDot(
                            color = pitchColor,
                            label = String.format(Locale.US, "Pitch %+.2f rad/s", latest.second)
                        )
                        LegendDot(
                            color = yawColor,
                            label = String.format(Locale.US, "Yaw %+.2f rad/s", latest.first)
                        )
                    }
                    Text(
                        text = "±${String.format(Locale.US, "%.1f", maxAbs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurface.copy(alpha = 0.5f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val zeroY = height / 2f
                    val yScale = (height * 0.45f) / maxAbs
                    val xScale = width / (maxPoints - 1).coerceAtLeast(1)

                    val gridColor = onSurface.copy(alpha = 0.08f)
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                    for (step in listOf(-1f, -0.5f, 0.5f, 1f)) {
                        val y = zeroY - (step * maxAbs * yScale)
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(width, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    val thresholdYOffset = 0.85f * yScale
                    drawLine(
                        color = onSurface.copy(alpha = 0.2f),
                        start = Offset(0f, zeroY - thresholdYOffset),
                        end = Offset(width, zeroY - thresholdYOffset),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashEffect
                    )
                    drawLine(
                        color = onSurface.copy(alpha = 0.2f),
                        start = Offset(0f, zeroY + thresholdYOffset),
                        end = Offset(width, zeroY + thresholdYOffset),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashEffect
                    )

                    drawLine(
                        color = onSurface.copy(alpha = 0.25f),
                        start = Offset(0f, zeroY),
                        end = Offset(width, zeroY),
                        strokeWidth = 1.5.dp.toPx()
                    )

                    if (points.size > 1) {
                        val yawPath = Path()
                        val pitchPath = Path()

                        points.forEachIndexed { index, (yaw, pitch) ->
                            val x = index * xScale
                            val yYaw = zeroY - (yaw * yScale)
                            val yPitch = zeroY - (pitch * yScale)

                            if (index == 0) {
                                yawPath.moveTo(x, yYaw)
                                pitchPath.moveTo(x, yPitch)
                            } else {
                                yawPath.lineTo(x, yYaw)
                                pitchPath.lineTo(x, yPitch)
                            }
                        }

                        val stroke = Stroke(
                            width = 2.2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                        drawPath(path = yawPath, color = yawColor, style = stroke)
                        drawPath(path = pitchPath, color = pitchColor, style = stroke)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuaternionVisualizer() {
    val report by HeadTracking.motionReport.collectAsState()
    val headQ by HeadTracking.headQuaternion.collectAsState()

    val colorX = MaterialTheme.colorScheme.primary
    val colorY = MaterialTheme.colorScheme.secondary
    val colorZ = MaterialTheme.colorScheme.secondaryContainer
    val colorW = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface

    val design = LocalDesign.current

    val rawQ = report?.quaternion ?: listOf(0f, 0f, 0f, 1f)
    val isIdentity = rawQ.size == 4 && rawQ[0] == 0f && rawQ[1] == 0f && rawQ[2] == 0f && rawQ[3] == 1f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(if (design.system == DesignSystem.Material) 16.dp else 28.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(170.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = size.minDimension * 0.42f
                    val q = headQ.toList()

                    drawCircle(
                        color = onSurface.copy(alpha = 0.08f),
                        radius = radius,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.5.dp.toPx())
                    )

                    fun project(x: Float, y: Float, z: Float): Offset {
                        val perspective = 2.8f / (2.8f - z * 0.6f)
                        return Offset(
                            cx + (x * radius * 0.85f * perspective),
                            cy - (y * radius * 0.85f * perspective)
                        )
                    }

                    fun rotate(vx: Float, vy: Float, vz: Float): FloatArray {
                        val (qx, qy, qz, qw) = q
                        val tx = 2f * (qy * vz - qz * vy)
                        val ty = 2f * (qz * vx - qx * vz)
                        val tz = 2f * (qx * vy - qy * vx)
                        return floatArrayOf(
                            vx + qw * tx + (qy * tz - qz * ty),
                            vy + qw * ty + (qz * tx - qx * tz),
                            vz + qw * tz + (qx * ty - qy * tx)
                        )
                    }

                    for (plane in 0..2) {
                        val path = Path()
                        val ringColor = when (plane) {
                            0 -> colorX.copy(alpha = 0.35f)
                            1 -> colorY.copy(alpha = 0.35f)
                            else -> colorZ.copy(alpha = 0.35f)
                        }
                        for (i in 0..36) {
                            val angle = (i / 36f) * (2f * Math.PI.toFloat())
                            val c = cos(angle)
                            val s = sin(angle)
                            val (vx, vy, vz) = when (plane) {
                                0 -> Triple(0f, c, s)
                                1 -> Triple(c, 0f, s)
                                else -> Triple(c, s, 0f)
                            }
                            val rot = rotate(vx, vy, vz)
                            val pt = project(rot[0], rot[1], rot[2])
                            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                        }
                        drawPath(path = path, color = ringColor, style = Stroke(width = 1.2.dp.toPx()))
                    }

                    val axes = listOf(
                        Triple(rotate(1f, 0f, 0f), colorX, "X"),
                        Triple(rotate(0f, 1f, 0f), colorY, "Y"),
                        Triple(rotate(0f, 0f, 1f), colorZ, "Z")
                    ).sortedBy { it.first[2] }

                    axes.forEach { (vec, axisColor, _) ->
                        val proj = project(vec[0], vec[1], vec[2])
                        val depthAlpha = ((vec[2] + 1.5f) / 2.5f).coerceIn(0.35f, 1f)

                        drawLine(
                            color = axisColor.copy(alpha = depthAlpha),
                            start = Offset(cx, cy),
                            end = proj,
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawCircle(
                            color = axisColor.copy(alpha = depthAlpha),
                            radius = if (vec[2] >= 0f) 5.dp.toPx() else 3.5.dp.toPx(),
                            center = proj
                        )
                    }

                    drawCircle(color = onSurface, radius = 3.dp.toPx(), center = Offset(cx, cy))
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                QuaternionBar(label = "X", value = headQ[0], color = colorX)
                QuaternionBar(label = "Y", value = headQ[1], color = colorY)
                QuaternionBar(label = "Z", value = headQ[2], color = colorZ)
                QuaternionBar(label = "W", value = headQ[3], color = colorW)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recenter",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { if (!isIdentity) HeadTracking.recenter() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuaternionBar(label: String, value: Float, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            modifier = Modifier.width(14.dp)
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.1f))
        ) {
            val midX = size.width / 2f
            val barExtent = (value.coerceIn(-1f, 1f) * midX)
            val startX = minOf(midX, midX + barExtent)
            val endX = maxOf(midX, midX + barExtent)

            drawLine(
                color = color,
                start = Offset(startX, size.height / 2f),
                end = Offset(endX, size.height / 2f),
                strokeWidth = size.height,
                cap = StrokeCap.Round
            )
        }
        Text(
            text = String.format(Locale.US, "%+.2f", value),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = color)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
