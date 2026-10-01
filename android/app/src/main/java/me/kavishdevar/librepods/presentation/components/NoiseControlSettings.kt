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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.NoiseControlMode
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.theme.sectionHeader
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@SuppressLint("UnspecifiedRegisterReceiverFlag", "UnusedBoxWithConstraintsScope")
@Composable
fun NoiseControlSettings(
    showOffListeningMode: Boolean,
    noiseControlModeValue: Int,
    onNoiseControlModeChanged: (Int) -> Unit,
    /** The AirPods Max and the first-generation Pro have no Adaptive listening mode. */
    showAdaptiveMode: Boolean = true
) {
    when (LocalDesignSystem.current) {
        DesignSystem.Material -> {
            val options = buildList {
                if (showOffListeningMode) {
                    add(
                        Triple(
                            NoiseControlMode.OFF,
                            R.string.off,
                            R.drawable.noise_cancellation
                        )
                    )
                }

                add(
                    Triple(
                        NoiseControlMode.TRANSPARENCY,
                        R.string.transparency,
                        R.drawable.transparency
                    )
                )
                if (showAdaptiveMode) {
                    add(
                        Triple(
                            NoiseControlMode.ADAPTIVE,
                            R.string.adaptive,
                            R.drawable.adaptive
                        )
                    )
                }
                add(
                    Triple(
                        NoiseControlMode.NOISE_CANCELLATION,
                        R.string.noise_cancellation,
                        R.drawable.noise_cancellation
                    )
                )
            }

            val selectedMode = NoiseControlMode.entries[(noiseControlModeValue - 1).coerceIn(0, NoiseControlMode.entries.lastIndex)]

            Column {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.noise_control),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmallEmphasized
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    options.forEachIndexed { index, (mode, labelRes, iconRes) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            ToggleButton(
                                checked = selectedMode == mode,
                                onCheckedChange = {
                                    if (it) {
                                        onNoiseControlModeChanged(mode.ordinal + 1)
                                    }
                                },
                                shapes = when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                                colors = ToggleButtonDefaults.toggleButtonColors()
                                    .copy(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    bitmap = ImageBitmap.imageResource(iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(42.dp)
                                )
                            }

                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        DesignSystem.Apple -> {
            val isDarkTheme = isSystemInDarkTheme()
            val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFE3E3E8)
            val textColor = if (isDarkTheme) Color.White else Color.Black
            val textColorSelected = if (isDarkTheme) Color.White else Color.Black
            val selectedBackground = if (isDarkTheme) Color(0xBF5C5A5F) else Color(0xFFFFFFFF)

            // The segments this model actually offers. Off is optional, and the AirPods Max have
            // no Adaptive mode, so the control is built from this list instead of a fixed four.
            val modes = buildList {
                if (showOffListeningMode) add(NoiseControlMode.OFF)
                add(NoiseControlMode.TRANSPARENCY)
                if (showAdaptiveMode) add(NoiseControlMode.ADAPTIVE)
                add(NoiseControlMode.NOISE_CANCELLATION)
            }

            val noiseControlMode = remember { mutableStateOf(modes.first()) }

            fun onModeSelected(mode: NoiseControlMode, received: Boolean = false) {
                val previousMode = noiseControlMode.value
                // The device can report a mode we do not show; snap it to the nearest one we do.
                val targetMode = when {
                    mode in modes -> mode
                    mode == NoiseControlMode.OFF -> NoiseControlMode.TRANSPARENCY
                    else -> NoiseControlMode.NOISE_CANCELLATION
                }

                noiseControlMode.value = targetMode

                if (!received && targetMode != previousMode) {
                    onNoiseControlModeChanged(targetMode.ordinal + 1)
                }
            }

            val reportedIndex =
                (noiseControlModeValue - 1).coerceIn(0, NoiseControlMode.entries.size - 1)
            onModeSelected(NoiseControlMode.entries[reportedIndex], received = true)

            val selectedIndex = modes.indexOf(noiseControlMode.value).coerceAtLeast(0)
            // A divider is hidden while it touches the selected pill.
            val dividerAlpha =
                { i: Int -> if (selectedIndex == i || selectedIndex == i + 1) 0f else 1f }

            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.noise_control),
                    color = MaterialTheme.colorScheme.sectionHeader,
                    style = MaterialTheme.typography.labelSmallEmphasized
                )
            }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                val density = LocalDensity.current
                val buttonCount = modes.size
                val buttonWidth = maxWidth / buttonCount

                val isDragging = remember { mutableStateOf(false) }
                var dragOffset by remember {
                    mutableFloatStateOf(with(density) { (buttonWidth * selectedIndex).toPx() })
                }

                val animationSpec: AnimationSpec<Float> = SpringSpec(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = 0.01f
                )

                val targetOffset = buttonWidth * selectedIndex

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
                        NoiseControlSegments(
                            modes = modes,
                            selectedMode = noiseControlMode.value,
                            textColor = textColor,
                            textColorSelected = textColorSelected,
                            dividerAlpha = dividerAlpha,
                            onModeSelected = { onModeSelected(it) }
                        )

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
                                            with(density) {
                                                (buttonWidth * (buttonCount - 1)).toPx()
                                            }
                                        )
                                    },
                                    onDragStarted = { isDragging.value = true },
                                    onDragStopped = {
                                        isDragging.value = false
                                        val position =
                                            dragOffset / with(density) { buttonWidth.toPx() }
                                        onModeSelected(
                                            modes.getOrElse(position.roundToInt()) {
                                                noiseControlMode.value
                                            }
                                        )
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

                        NoiseControlSegments(
                            modes = modes,
                            selectedMode = noiseControlMode.value,
                            textColor = textColor,
                            textColorSelected = textColorSelected,
                            dividerAlpha = dividerAlpha,
                            onModeSelected = { onModeSelected(it) },
                            modifier = Modifier.zIndex(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        modes.forEach { mode ->
                            Text(
                                text = stringResource(noiseControlLabelRes(mode)),
                                style = TextStyle(fontSize = 12.sp, color = textColor),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}


private fun noiseControlIconRes(mode: NoiseControlMode): Int = when (mode) {
    NoiseControlMode.OFF -> R.drawable.noise_cancellation
    NoiseControlMode.TRANSPARENCY -> R.drawable.transparency
    NoiseControlMode.ADAPTIVE -> R.drawable.adaptive
    NoiseControlMode.NOISE_CANCELLATION -> R.drawable.noise_cancellation
}

private fun noiseControlLabelRes(mode: NoiseControlMode): Int = when (mode) {
    NoiseControlMode.OFF -> R.string.off
    NoiseControlMode.TRANSPARENCY -> R.string.transparency
    NoiseControlMode.ADAPTIVE -> R.string.adaptive
    NoiseControlMode.NOISE_CANCELLATION -> R.string.noise_cancellation
}

/** One row of segment buttons, drawn twice: once under the sliding pill and once above it. */
@Composable
private fun NoiseControlSegments(
    modes: List<NoiseControlMode>,
    selectedMode: NoiseControlMode,
    textColor: Color,
    textColorSelected: Color,
    dividerAlpha: (Int) -> Float,
    onModeSelected: (NoiseControlMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            if (index > 0) {
                VerticalDivider(
                    thickness = 1.dp,
                    modifier = Modifier
                        .padding(vertical = 10.dp)
                        .alpha(dividerAlpha(index - 1)),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            }
            NoiseControlButton(
                icon = ImageBitmap.imageResource(noiseControlIconRes(mode)),
                onClick = { onModeSelected(mode) },
                textColor = if (selectedMode == mode) textColorSelected else textColor,
                modifier = Modifier.weight(1f),
                usePadding = false
            )
        }
    }
}

@Preview
@Composable
fun NoiseControlSettingsPreview() {
    LibrePodsTheme(
        m3eEnabled = true
    ) {
        Box(
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            NoiseControlSettings(
                showOffListeningMode = false,
                noiseControlModeValue = 2,
                onNoiseControlModeChanged = { }
            )
        }
    }
}
