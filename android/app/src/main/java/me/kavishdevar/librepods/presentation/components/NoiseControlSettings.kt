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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.presentation.components

import android.annotation.SuppressLint
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.NoiseControlMode
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

@SuppressLint("UnspecifiedRegisterReceiverFlag", "UnusedBoxWithConstraintsScope")
@Composable
fun NoiseControlSettings(
    showOffListeningMode: Boolean,
    noiseControlModeValue: Int,
    onNoiseControlModeChanged: (Int) -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFE3E3E8)
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val textColorSelected = if (isDarkTheme) Color.White else Color.Black
    val selectedBackground = if (isDarkTheme) Color(0xBF5C5A5F) else Color(0xFFFFFFFF)



    val noiseControlMode = remember { mutableStateOf(NoiseControlMode.OFF) }

    val d1a = remember { mutableFloatStateOf(0f) }
    val d2a = remember { mutableFloatStateOf(0f) }
    val d3a = remember { mutableFloatStateOf(0f) }

    // this function exists solely for the dividers, should get rid of it
    fun onModeSelected(mode: NoiseControlMode, received: Boolean = false) {
        val previousMode = noiseControlMode.value

        val targetMode = if (!showOffListeningMode && mode == NoiseControlMode.OFF) {
             NoiseControlMode.TRANSPARENCY
        } else {
            mode
        }

        noiseControlMode.value = targetMode

        if (!received && targetMode != previousMode) onNoiseControlModeChanged(targetMode.ordinal + 1)


        when (noiseControlMode.value) {
            NoiseControlMode.NOISE_CANCELLATION -> {
                d1a.floatValue = 1f
                d2a.floatValue = 1f
                d3a.floatValue = 0f
            }
            NoiseControlMode.OFF -> {
                d1a.floatValue = 0f
                d2a.floatValue = 1f
                d3a.floatValue = 1f
            }
            NoiseControlMode.ADAPTIVE -> {
                d1a.floatValue = 1f
                d2a.floatValue = 0f
                d3a.floatValue = 0f
            }
            NoiseControlMode.TRANSPARENCY -> {
                d1a.floatValue = 0f
                d2a.floatValue = 0f
                d3a.floatValue = 1f
            }
        }
    }


    val index = (noiseControlModeValue - 1).coerceIn(0, NoiseControlMode.entries.size - 1)
    noiseControlMode.value = NoiseControlMode.entries[index]

    onModeSelected(noiseControlMode.value, received = true)

    Box(
        modifier = Modifier
            .background(if (isDarkTheme) Color(0xFF000000) else Color(0xFFF2F2F7))
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ){
        Text(
            text = stringResource(R.string.noise_control),
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textColor.copy(alpha = 0.6f),
                fontFamily = FontFamily(Font(R.font.sf_pro))
            )
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        val density = LocalDensity.current
        val buttonCount = if (showOffListeningMode) 4 else 3
        val buttonWidth = maxWidth / buttonCount

        val isDragging = remember { mutableStateOf(false) }
        var dragOffset by remember {
            mutableFloatStateOf(
                with(density) {
                    when(noiseControlMode.value) {
                        NoiseControlMode.OFF -> if (showOffListeningMode) 0f else buttonWidth.toPx()
                        NoiseControlMode.TRANSPARENCY -> if (showOffListeningMode) buttonWidth.toPx() else 0f
                        NoiseControlMode.ADAPTIVE -> if (showOffListeningMode) (buttonWidth * 2).toPx() else buttonWidth.toPx()
                        NoiseControlMode.NOISE_CANCELLATION -> if (showOffListeningMode) (buttonWidth * 3).toPx() else (buttonWidth * 2).toPx()
                    }
                }
            )
        }

        val animationSpec: AnimationSpec<Float> = SpringSpec(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = 0.01f
        )

        val targetOffset = buttonWidth * when(noiseControlMode.value) {
            NoiseControlMode.OFF -> if (showOffListeningMode) 0 else 1
            NoiseControlMode.TRANSPARENCY -> if (showOffListeningMode) 1 else 0
            NoiseControlMode.ADAPTIVE -> if (showOffListeningMode) 2 else 1
            NoiseControlMode.NOISE_CANCELLATION -> if (showOffListeningMode) 3 else 2
        }

        val animatedOffset by animateFloatAsState(
            targetValue = with(density) {
                if (isDragging.value) dragOffset else targetOffset.toPx()
            },
            animationSpec = animationSpec,
            label = "selector"
        )

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(backgroundColor, RoundedCornerShape(28.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (showOffListeningMode) {
                        NoiseControlButton(
                            icon = ImageBitmap.imageResource(R.drawable.noise_cancellation),
                            onClick = { onModeSelected(NoiseControlMode.OFF) },
                            textColor = if (noiseControlMode.value == NoiseControlMode.OFF) textColorSelected else textColor,
                            modifier = Modifier.weight(1f),
                            usePadding = false
                        )
                        VerticalDivider(
                            thickness = 1.dp,
                            modifier = Modifier
                                .padding(vertical = 10.dp)
                                .alpha(d1a.floatValue),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        )
                    }
                    NoiseControlButton(
                        icon = ImageBitmap.imageResource(R.drawable.transparency),
                        onClick = { onModeSelected(NoiseControlMode.TRANSPARENCY) },
                        textColor = if (noiseControlMode.value == NoiseControlMode.TRANSPARENCY) textColorSelected else textColor,
                        modifier = Modifier.weight(1f),
                        usePadding = false
                    )
                    VerticalDivider(
                        thickness = 1.dp,
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .alpha(d2a.floatValue),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
                    NoiseControlButton(
                        icon = ImageBitmap.imageResource(R.drawable.adaptive),
                        onClick = { onModeSelected(NoiseControlMode.ADAPTIVE) },
                        textColor = if (noiseControlMode.value == NoiseControlMode.ADAPTIVE) textColorSelected else textColor,
                        modifier = Modifier.weight(1f),
                        usePadding = false
                    )
                    VerticalDivider(
                        thickness = 1.dp,
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .alpha(d3a.floatValue),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
                    NoiseControlButton(
                        icon = ImageBitmap.imageResource(R.drawable.noise_cancellation),
                        onClick = { onModeSelected(NoiseControlMode.NOISE_CANCELLATION) },
                        textColor = if (noiseControlMode.value == NoiseControlMode.NOISE_CANCELLATION) textColorSelected else textColor,
                        modifier = Modifier.weight(1f),
                        usePadding = false
                    )
                }

                Box(
                    modifier = Modifier
                        .width(buttonWidth)
                        .fillMaxHeight()
                        .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                        .zIndex(0f)
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                dragOffset = (dragOffset + delta).coerceIn(
                                    0f,
                                    with(density) { (buttonWidth * (buttonCount - 1)).toPx() }
                                )
                            },
                            onDragStarted = { isDragging.value = true },
                            onDragStopped = {
                                isDragging.value = false
                                val position = dragOffset / with(density) { buttonWidth.toPx() }
                                val newIndex = position.roundToInt()
                                val newMode = when(newIndex) {
                                    0 -> if (showOffListeningMode) NoiseControlMode.OFF else NoiseControlMode.TRANSPARENCY
                                    1 -> if (showOffListeningMode) NoiseControlMode.TRANSPARENCY else NoiseControlMode.ADAPTIVE
                                    2 -> if (showOffListeningMode) NoiseControlMode.ADAPTIVE else NoiseControlMode.NOISE_CANCELLATION
                                    3 -> NoiseControlMode.NOISE_CANCELLATION
                                    else -> noiseControlMode.value // Keep current if index is invalid
                                }
                                onModeSelected(newMode)
                            }
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(3.dp)
                            .background(selectedBackground, RoundedCornerShape(26.dp))
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(1f)
                ) {
                    if (showOffListeningMode) {
                        NoiseControlButton(
                            icon = ImageBitmap.imageResource(R.drawable.noise_cancellation),
                            onClick = { onModeSelected(NoiseControlMode.OFF) },
                            textColor = if (noiseControlMode.value == NoiseControlMode.OFF) textColorSelected else textColor,
                            modifier = Modifier.weight(1f),
                            usePadding = false
                        )
                        VerticalDivider(
                            thickness = 1.dp,
                            modifier = Modifier
                                .padding(vertical = 10.dp)
                                .alpha(d1a.floatValue),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        )
                    }
                    NoiseControlButton(
                        icon = ImageBitmap.imageResource(R.drawable.transparency),
                        onClick = { onModeSelected(NoiseControlMode.TRANSPARENCY) },
                        textColor = if (noiseControlMode.value == NoiseControlMode.TRANSPARENCY) textColorSelected else textColor,
                        modifier = Modifier.weight(1f),
                        usePadding = false
                    )
                    VerticalDivider(
                        thickness = 1.dp,
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .alpha(d2a.floatValue),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
                    NoiseControlButton(
                        icon = ImageBitmap.imageResource(R.drawable.adaptive),
                        onClick = { onModeSelected(NoiseControlMode.ADAPTIVE) },
                        textColor = if (noiseControlMode.value == NoiseControlMode.ADAPTIVE) textColorSelected else textColor,
                        modifier = Modifier.weight(1f),
                        usePadding = false
                    )
                    VerticalDivider(
                        thickness = 1.dp,
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .alpha(d3a.floatValue),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
                    NoiseControlButton(
                        icon = ImageBitmap.imageResource(R.drawable.noise_cancellation),
                        onClick = { onModeSelected(NoiseControlMode.NOISE_CANCELLATION) },
                        textColor = if (noiseControlMode.value == NoiseControlMode.NOISE_CANCELLATION) textColorSelected else textColor,
                        modifier = Modifier.weight(1f),
                        usePadding = false
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                if (showOffListeningMode) {
                    Text(
                        text = stringResource(R.string.off),
                        style = TextStyle(fontSize = 12.sp, color = textColor),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = stringResource(R.string.transparency),
                    style = TextStyle(fontSize = 12.sp, color = textColor),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.adaptive),
                    style = TextStyle(fontSize = 12.sp, color = textColor),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.noise_cancellation),
                    style = TextStyle(fontSize = 12.sp, color = textColor),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
