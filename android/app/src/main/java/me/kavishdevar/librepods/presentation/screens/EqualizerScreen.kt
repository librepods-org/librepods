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

package me.kavishdevar.librepods.presentation.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.visible
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsUiState
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.demoState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun EqualizerRoute(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Box (
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        EqualizerScreen(
            state = state,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
            setCustomEqEnabled = viewModel::setCustomEqEnabled,
            setCustomEq = viewModel::setCustomEq
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
fun EqualizerScreen(
    state: AirPodsUiState,
    topPadding: Dp = 16.dp,
    bottomPadding: Dp = 16.dp,
    setCustomEqEnabled: (Boolean) -> Unit,
    setCustomEq: (Int, Int, Int) -> Unit
) {
    val customEq = state.customEq

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val height = 200.dp
        val maxOffset = with(LocalDensity.current) { height.toPx() } / 2

        val offsets = remember(state.customEq) {
            listOf(
                mutableFloatStateOf(lerp(maxOffset, -maxOffset, customEq.low.toFloat() / 100)),
                mutableFloatStateOf(lerp(maxOffset, -maxOffset, customEq.mid.toFloat() / 100)),
                mutableFloatStateOf(lerp(maxOffset, -maxOffset, customEq.high.toFloat() / 100))
            )
        }

        LaunchedEffect(offsets) {
            snapshotFlow {
                Triple(
                    offsets[0].floatValue,
                    offsets[1].floatValue,
                    offsets[2].floatValue
                )
            }
                .debounce(100.milliseconds) // cool, should've been using this since the very beginning
                .collect { (lowF, midF, highF) ->
                    val low =
                        100 - ((lowF / (2 * maxOffset) + 0.5f) * 100).roundToInt()
                    val mid =
                        100 - ((midF / (2 * maxOffset) + 0.5f) * 100).roundToInt()
                    val high =
                        100 - ((highF / (2 * maxOffset) + 0.5f) * 100).roundToInt()

                    setCustomEq(low, mid, high)
                }
        }

        Spacer(modifier = Modifier.height(topPadding))

        val enabled = customEq.isEnabled()

        StyledList {
            StyledListItem(
                name = stringResource(R.string.recommended),
                selected = !enabled,
                onClick = { setCustomEqEnabled(false) }
            )

            StyledListItem(
                name = stringResource(R.string.custom),
                selected = enabled,
                onClick = { setCustomEqEnabled(true) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Crossfade (
            customEq.isEnabled()
        ) { visible ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .visible(visible),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                EqualizerCard(
                    lowOffset = offsets[0],
                    midOffset = offsets[1],
                    highOffset = offsets[2]
                )

                val resetButtonEnabled = remember { derivedStateOf { !offsets.all { it.floatValue == 0f } } }

                StyledButton(
                    onClick = {
                        offsets[0].floatValue = 0f
                        offsets[1].floatValue = 0f
                        offsets[2].floatValue = 0f
                    },
                    backdrop = rememberLayerBackdrop(),
                    modifier = Modifier.fillMaxWidth(),
                    isInteractive = false,
                    enabled = resetButtonEnabled.value
                ) {
                    Text(
                        text = stringResource(R.string.reset),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}


@Composable
fun EqualizerCard(
    lowOffset: MutableState<Float>,
    midOffset: MutableState<Float>,
    highOffset: MutableState<Float>
) {
    val height = 200.dp
    val maxOffset = with(LocalDensity.current) { height.toPx() } / 2

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
    ) {
        val dashColor = if (isSystemInDarkTheme()) Color(0x80AAAAAA) else Color(0x809D9D9D)

        val backdrop = rememberLayerBackdrop()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
        ) {
            Spacer(modifier = Modifier.height(42.dp))
            //                Row(
            //                    modifier = Modifier
            //                        .fillMaxWidth()
            //                        .padding(18.dp),
            //                    verticalAlignment = Alignment.CenterVertically,
            //                    horizontalArrangement = Arrangement.spacedBy(12.dp)
            //                ) {
            //                    Box(
            //                        modifier = Modifier
            //                            .size(64.dp)
            //                            .background(if (isSystemInDarkTheme()) Color.DarkGray else Color.LightGray, RoundedCornerShape(12.dp))
            //                    )
            //                    Column(
            //                        modifier = Modifier
            //                            .weight(1f),
            //                        verticalArrangement = Arrangement.Center
            //                    ) {
            //                        Text(
            //                            text = "Written into Changes",
            //                            style = TextStyle(
            //                                fontSize = 16.sp,
            //                                fontFamily = FontFamily(Font(R.font.sf_pro)),
            //                                fontWeight = FontWeight.Bold,
            //                                color = if (isSystemInDarkTheme()) Color.White else Color.Black
            //                            )
            //                        )
            //                        Spacer(modifier = Modifier.height(4.dp))
            //                        Text(
            //                            text = "Avalon Emerson",
            //                            style = TextStyle(
            //                                fontSize = 14.sp,
            //                                fontFamily = FontFamily(Font(R.font.sf_pro)),
            //                                fontWeight = FontWeight.Normal,
            //                                color = if (isSystemInDarkTheme()) Color.White else Color.Black
            //                            )
            //                        )
            //                    }
            //                    val paused = remember { mutableStateOf(false) }
            //                    Box(
            //                        modifier = Modifier
            //                            .size(48.dp)
            //                            .background(Color(0x600091FF), CircleShape)
            //                            .clickable(
            //                                interactionSource = remember { MutableInteractionSource() },
            //                                indication = null,
            //                            ) {
            //                                paused.value = !paused.value
            //                            },
            //                        contentAlignment = Alignment.Center
            //                    ) {
            //                        Crossfade(
            //                            targetState = paused.value,
            //                            label = "media_icon"
            //                        ) { p ->
            //                            Text(
            //                                text = if (p) "􀊄" else "􀊆",
            //                                style = TextStyle(
            //                                    fontSize = 24.sp,
            //                                    fontFamily = FontFamily(Font(R.font.sf_pro)),
            //                                    fontWeight = FontWeight.Normal,
            //                                    color = Color(0xFF0091FF),
            //                                    textAlign = TextAlign.Center
            //                                )
            //                            )
            //                        }
            //                    }
            //                }
            //
            //                HorizontalDivider(
            //                    thickness = 1.dp,
            //                    color = Color(0x40888888),
            //                    modifier = Modifier
            //                        .padding(horizontal = 20.dp)
            //                        .padding(bottom = 16.dp)
            //                )

            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                fun colorFromY(y: Float): Color {
                    val f = ((y + maxOffset) / (2f * maxOffset)).coerceIn(0f, 1f)
                    val stops = listOf(
                        0.0f to Color(0xFFFFA300),
                        0.25f to Color(0xFFFCE600),
                        0.5f to Color(0xFF00FAAF),
                        0.75f to Color(0xFF00FAFF),
                        1.0f to Color(0xFF00B5FF)
                    )
                    val (start, end) = stops.zipWithNext()
                        .first { f <= it.second.first }
                    val c = (f - start.first) / (end.first - start.first)
                    return lerp(start.second, end.second, c)
                }

                fun pathBrush(
                    startY: Float,
                    endY: Float,
                ): Brush {
                    val stops = (0..20).map { i ->
                        val t = i / 20f
                        val y = lerp(startY, endY, t)
                        t to colorFromY(y)
                    }

                    return Brush.linearGradient(
                        colorStops = stops.toTypedArray()
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .layerBackdrop(backdrop)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(height)
                            .padding(horizontal = 20.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                        ) {
                            val dashCount = (height / 10.dp).toInt()
                            repeat(3) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxHeight(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        for (i in 1..(dashCount)) {
                                            val t = i.toFloat() / dashCount
                                            val centerDistance = abs(0.5f - t)
                                            val alpha = 1f - (centerDistance * 2f)
                                            Box(
                                                modifier = Modifier
                                                    .height(9.dp)
                                                    .width(0.75.dp)
                                                    .background(
                                                        dashColor.copy(alpha),
                                                        RoundedCornerShape(28.dp)
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        val backgroundColor = MaterialTheme.colorScheme.surface

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                        ) {
                            val canvasWidth = size.width

                            drawLine(
                                color = backgroundColor,
                                start = Offset(
                                    x = 0f,
                                    y = lowOffset.value + maxOffset
                                ),
                                end = Offset(
                                    x = 1 / 6f * canvasWidth,
                                    y = lowOffset.value + maxOffset
                                ),
                                strokeWidth = 10f
                            )
                            drawLine(
                                color = colorFromY(lowOffset.value),
                                start = Offset(
                                    x = 0f,
                                    y = lowOffset.value + maxOffset
                                ),
                                end = Offset(
                                    x = 1 / 6f * canvasWidth,
                                    y = lowOffset.value + maxOffset
                                ),
                                strokeWidth = 8f
                            )

                            val lowToMidPath = Path()
                            lowToMidPath.moveTo(
                                x = 1 / 6f * canvasWidth,
                                y = lowOffset.value + maxOffset
                            )
                            lowToMidPath.cubicTo(
                                x1 = canvasWidth * 1 / 6f + 108.dp.value,
                                y1 = lowOffset.value + maxOffset,
                                x2 = canvasWidth * 0.5f - 108.dp.value,
                                y2 = midOffset.value + maxOffset,
                                x3 = canvasWidth * 0.5f,
                                y3 = midOffset.value + maxOffset
                            )
                            drawPath(
                                color = backgroundColor,
                                path = lowToMidPath,
                                style = Stroke(width = 10f)
                            )
                            drawPath(
                                brush = pathBrush(
                                    lowOffset.value,
                                    midOffset.value
                                ),
                                path = lowToMidPath,
                                style = Stroke(width = 8f)
                            )

                            val midToHighPath = Path()
                            midToHighPath.moveTo(
                                x = 0.5f * canvasWidth,
                                y = midOffset.value + maxOffset
                            )
                            midToHighPath.cubicTo(
                                x1 = canvasWidth * 0.5f + 108.dp.value,
                                y1 = midOffset.value + maxOffset,
                                x2 = canvasWidth * 5 / 6f - 108.dp.value,
                                y2 = highOffset.value + maxOffset,
                                x3 = canvasWidth * 5 / 6f,
                                y3 = highOffset.value + maxOffset
                            )
                            drawPath(
                                color = backgroundColor,
                                path = midToHighPath,
                                style = Stroke(width = 10f)
                            )
                            drawPath(
                                brush = pathBrush(
                                    midOffset.value,
                                    highOffset.value
                                ),
                                path = midToHighPath,
                                style = Stroke(width = 8f)
                            )
                            drawLine(
                                color = backgroundColor,
                                start = Offset(
                                    x = 5 / 6f * canvasWidth,
                                    y = highOffset.value + maxOffset
                                ),
                                end = Offset(
                                    x = 1f * canvasWidth,
                                    y = highOffset.value + maxOffset
                                ),
                                strokeWidth = 10f
                            )
                            drawLine(
                                color = colorFromY(highOffset.value),
                                start = Offset(
                                    x = 5 / 6f * canvasWidth,
                                    y = highOffset.value + maxOffset
                                ),
                                end = Offset(
                                    x = 1f * canvasWidth,
                                    y = highOffset.value + maxOffset
                                ),
                                strokeWidth = 8f
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp, horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Low".uppercase(),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                                    fontWeight = FontWeight.Bold,
                                    color = (if (isSystemInDarkTheme()) Color.White else Color.Black).copy(
                                        0.2f
                                    ),
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Box(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Mid".uppercase(),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                                    fontWeight = FontWeight.Bold,
                                    color = (if (isSystemInDarkTheme()) Color.White else Color.Black).copy(
                                        0.2f
                                    ),
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Box(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "High".uppercase(),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                                    fontWeight = FontWeight.Bold,
                                    color = (if (isSystemInDarkTheme()) Color.White else Color.Black).copy(
                                        0.2f
                                    ),
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height)
                        .padding(horizontal = 20.dp),

                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0..2) {
                        Row(
                            modifier = Modifier
                                .weight(1f),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            val pressed = remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            x = 0,
                                            y = when (i) { 0 -> lowOffset.value; 1 -> midOffset.value; 2-> highOffset.value else -> 0f}.roundToInt()
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Crossfade(
                                    pressed.value
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(96.dp)
                                            .then(
                                                if (it) {
                                                    Modifier.drawBackdrop(
                                                        backdrop = backdrop,
                                                        shape = { CircleShape },
                                                        highlight = {
                                                            Highlight.Ambient
                                                        },
                                                        onDrawSurface = {
                                                            drawCircle(
                                                                color = Color.White.copy(
                                                                    0.2f
                                                                ),
                                                                radius = size.height
                                                            )
                                                            drawCircle(
                                                                color = colorFromY(
                                                                    when (i) {
                                                                        0 -> lowOffset.value; 1 -> midOffset.value; 2 -> highOffset.value
                                                                        else -> 0f
                                                                    }
                                                                ),
                                                                style = Stroke(2.dp.value),
                                                                radius = size.height / 2
                                                            )
                                                        },
                                                        effects = {
                                                            lens(
                                                                refractionHeight = 32f.dp.value,
                                                                refractionAmount = size.height
                                                            )
                                                        }
                                                    )
                                                } else Modifier
                                            )
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .background(
                                            colorFromY(
                                                when (i) {
                                                    0 -> lowOffset.value; 1 -> midOffset.value; 2 -> highOffset.value
                                                    else -> 0f
                                                }
                                            ),
                                            CircleShape
                                        )
                                        .border(
                                            2.5.dp,
                                            MaterialTheme.colorScheme.surfaceContainer,
                                            CircleShape
                                        )
                                        .draggable(
                                            orientation = Orientation.Vertical,
                                            state = rememberDraggableState { delta ->
                                                when (i) {
                                                    0 -> {
                                                        lowOffset.value =
                                                            (lowOffset.value + delta).coerceIn(
                                                                -maxOffset,
                                                                maxOffset
                                                            )
                                                    }

                                                    1 -> {
                                                        midOffset.value =
                                                            (midOffset.value + delta).coerceIn(
                                                                -maxOffset,
                                                                maxOffset
                                                            )
                                                    }

                                                    2 -> {
                                                        highOffset.value =
                                                            (highOffset.value + delta).coerceIn(
                                                                -maxOffset,
                                                                maxOffset
                                                            )
                                                    }
                                                }
                                            },
                                            onDragStarted = {
                                                pressed.value = true
                                            },
                                            onDragStopped = {
                                                pressed.value = false
                                            }
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Apple")
@Composable
fun EqualizerScreenPreviewApple() {
    LibrePodsTheme(
        m3eEnabled = false
    ) {
        Box (
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            EqualizerScreen(
                state = demoState,
                setCustomEqEnabled = { },
                setCustomEq = {_, _, _ -> }
            )
        }
    }
}

@Preview(name = "Material")
@Composable
fun EqualizerScreenPreviewMaterial() {
    LibrePodsTheme(
        m3eEnabled = true
    ) {
        val state = remember { mutableStateOf(demoState) }
        Box (
            modifier = Modifier
                .wrapContentHeight()
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            EqualizerScreen(
                state = state.value,
                setCustomEqEnabled = { state.value = state.value.copy(customEq = state.value.customEq.copy(state = if (it) 2 else 1)) },
                setCustomEq = {low, mid, high -> state.value = state.value.copy(customEq = state.value.customEq.copy(low = low, mid = mid, high = high))}
            )
        }
    }
}
