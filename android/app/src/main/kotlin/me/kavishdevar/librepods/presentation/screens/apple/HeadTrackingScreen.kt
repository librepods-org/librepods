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

import android.graphics.Paint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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
import me.kavishdevar.librepods.data.app.DesignSystem
import me.kavishdevar.librepods.presentation.components.primitives.MaterialButtonStyle
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledIconButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.design.LocalDesign
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.icons.MaterialIcons
import me.kavishdevar.librepods.presentation.utils.LocalDebugMode
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import kotlin.math.abs
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
                            contentDescription = stringResource(R.string.play_pause),
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
                            contentDescription = stringResource(R.string.play_pause),
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
                    label = stringResource(R.string.head_gestures),
                    checked = settings.headGesturesEnabled,
                    onCheckedChange = { viewModel.setHeadGesturesEnabled(it) },
                    enabled = uiState.isPremium || settings.headGesturesEnabled,
                    description = stringResource(R.string.head_gestures_details),
                    header = true
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.velocity),
                    style = MaterialTheme.typography.labelSmallEmphasized,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Plot()

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
            val yesDetectedText = stringResource(R.string.head_gesture_yes_detected)
            val noDetectedText = stringResource(R.string.head_gesture_no_detected)
            StyledButton(
                onClick = {
                    gestureText = gestureTextValue
                    coroutineScope.launch {
                        viewModel.detectHeadGestures { gestureText = if (it) yesDetectedText else noDetectedText }
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
                Spacer(modifier = Modifier.height(16.dp))
                StyledToggle(
                    label = "[debug] alternate horizontal byte offset",
                    checked = settings.headGesturesHorizontalOffset == 26,
                    onCheckedChange = { viewModel.setHeadGesturesHorizontalOffset(if (it) 26 else 28) }
                )
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
private fun Plot() {
    val acceleration by HeadTracking.acceleration.collectAsState()
    val maxPoints = 100
    val points = remember { mutableStateListOf<Pair<Float, Float>>() }

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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(28.dp)
    ) {
        val horizontalColor = MaterialTheme.colorScheme.primary
        val verticalColor = MaterialTheme.colorScheme.secondaryContainer // random

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            val onBackground = MaterialTheme.colorScheme.onBackground
            // the Canvas draw scope is not composable, so resolve the legend labels here
            val horizontalLabel = stringResource(R.string.horizontal)
            val verticalLabel = stringResource(R.string.vertical)
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val width = size.width
                val height = size.height
                val xScale = width / maxPoints
                val yScale = (height - 40.dp.toPx()) / (maxAbs * 2)
                val zeroY = height / 2

                val gridColor = onBackground.copy(alpha = 0.1f)

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
                    color = onBackground.copy(alpha = 0.3f),
                    start = Offset(0f, zeroY),
                    end = Offset(width, zeroY),
                    strokeWidth = 1.5f.dp.toPx()
                )

                if (points.size > 1) {
                    for (i in 0 until points.size - 1) {
                        val x1 = i * xScale
                        val x2 = (i + 1) * xScale

                        drawLine(
                            color = horizontalColor,
                            start = Offset(x1, zeroY - points[i].first * yScale),
                            end = Offset(x2, zeroY - points[i + 1].first * yScale),
                            strokeWidth = 2.dp.toPx()
                        )

                        drawLine(
                            color = verticalColor,
                            start = Offset(x1, zeroY - points[i].second * yScale),
                            end = Offset(x2, zeroY - points[i + 1].second * yScale),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }

                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        color = onBackground.toArgb()
                        textSize = 12.sp.toPx()
                        textAlign = Paint.Align.RIGHT
                    }

                    drawText("${maxAbs.toInt()}", 30.dp.toPx(), 20.dp.toPx(), paint)
                    drawText("0", 30.dp.toPx(), height/2, paint)
                    drawText("-${maxAbs.toInt()}", 30.dp.toPx(), height - 10.dp.toPx(), paint)
                }

                val legendY = 15.dp.toPx()
                val textOffsetY = legendY + 5.dp.toPx() / 2

                drawCircle(horizontalColor, 5.dp.toPx(), Offset(width - 150.dp.toPx(), legendY))
                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        color = onBackground.toArgb()
                        textSize = 12.sp.toPx()
                        textAlign = Paint.Align.LEFT
                    }
                    drawText(horizontalLabel, width - 140.dp.toPx(), textOffsetY, paint)
                }

                drawCircle(verticalColor, 5.dp.toPx(), Offset(width - 70.dp.toPx(), legendY))
                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        color = onBackground.toArgb()
                        textSize = 12.sp.toPx()
                        textAlign = Paint.Align.LEFT
                    }
                    drawText(verticalLabel, width - 60.dp.toPx(), textOffsetY, paint)
                }
            }
        }
    }
}
