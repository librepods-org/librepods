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

package me.kavishdevar.librepods.composables

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

@Composable
fun VerticalVolumeSlider(
    displayFraction: Float,
    maxVolume: Int,
    onVolumeChange: (Int) -> Unit,
    initialFraction: Float,
    onDragStateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    baseSliderHeight: Dp = 400.dp,
    baseSliderWidth: Dp = 145.dp,
    baseCornerRadius: Dp = 45.dp,
    maxStretchFactor: Float = 1.15f,
    minCompressionFactor: Float = 0.875f,
    stretchSensitivity: Float = 1.0f,
    compressionSensitivity: Float = 1.0f,
    cornerRadiusChangeFactor: Float = 0.2f,
    directionalStretchRatio: Float = 0.75f
) {
    val trackColor = Color(0x593C3C3E)
    val progressColor = Color.White

    var dragFraction by remember { mutableFloatStateOf(initialFraction) }
    var isDragging by remember { mutableStateOf(false) }

    var rawDragPosition by remember { mutableFloatStateOf(initialFraction) }
    var overscrollAmount by remember { mutableFloatStateOf(0f) }

    val baseHeightPx = with(LocalDensity.current) { baseSliderHeight.toPx() }

    val animatedProgress by animateFloatAsState(
        targetValue = dragFraction.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "ProgressAnimation"
    )

    val animatedOverscroll by animateFloatAsState(
        targetValue = overscrollAmount,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "OverscrollAnimation"
    )

    val maxOverscrollEffect = (maxStretchFactor - 1f).coerceAtLeast(0f)

    val stretchMultiplier = stretchSensitivity
    val compressionMultiplier = compressionSensitivity

    val overscrollDirection = sign(animatedOverscroll)

    val totalStretchAmount = (min(maxOverscrollEffect, abs(animatedOverscroll) * stretchMultiplier) * baseSliderHeight.value).dp

    val offsetY = if (abs(animatedOverscroll) > 0.001f) {
        val asymmetricOffset = totalStretchAmount * (directionalStretchRatio - 0.5f)
        (-overscrollDirection * asymmetricOffset.value).dp
    } else {
        0.dp
    }

    val heightStretch = baseSliderHeight + totalStretchAmount

    val widthCompression = baseSliderWidth * max(
        minCompressionFactor,
        1f - min(1f - minCompressionFactor, abs(animatedOverscroll) * compressionMultiplier)
    )

    val dynamicCornerRadius = baseCornerRadius * (1f - min(cornerRadiusChangeFactor, abs(animatedOverscroll) * cornerRadiusChangeFactor * 2f))

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .height(heightStretch)
                .width(widthCompression)
                .offset(y = offsetY)
                .clip(RoundedCornerShape(dynamicCornerRadius))
                .background(trackColor)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val newFraction = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        dragFraction = newFraction
                        rawDragPosition = newFraction
                        overscrollAmount = 0f

                        val newVolume = (newFraction * maxVolume).roundToInt()
                        onVolumeChange(newVolume)
                    }
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        rawDragPosition -= (delta / baseHeightPx)

                        dragFraction = rawDragPosition.coerceIn(0f, 1f)

                        overscrollAmount = when {
                            rawDragPosition > 1f -> min(1.0f, (rawDragPosition - 1f) * 2.0f)
                            rawDragPosition < 0f -> max(-1.0f, rawDragPosition * 2.0f)
                            else -> 0f
                        }

                        val newVolume = (dragFraction * maxVolume).roundToInt()
                        onVolumeChange(newVolume)
                    },
                    onDragStarted = {
                        isDragging = true
                        dragFraction = displayFraction
                        rawDragPosition = displayFraction
                        overscrollAmount = 0f
                        onDragStateChange(true)
                    },
                    onDragStopped = {
                        isDragging = false
                        overscrollAmount = 0f
                        rawDragPosition = dragFraction
                        onDragStateChange(false)
                    }
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animatedProgress)
                    .background(progressColor)
            )
        }
    }
}
