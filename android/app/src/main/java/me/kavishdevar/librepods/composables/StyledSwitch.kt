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

import android.content.res.Configuration
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.refractionWithDispersion
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun StyledSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val isDarkTheme = isSystemInDarkTheme()

    val onColor = if (enabled) Color(0xFF34C759) else if (isDarkTheme) Color(0xFF5B5B5E) else Color(0xFFD1D1D6)
    val offColor = if (enabled) if (isDarkTheme) Color(0xFF5B5B5E) else Color(0xFFD1D1D6) else if (isDarkTheme) Color(0xFF5B5B5E) else Color(0xFFD1D1D6)

    val trackWidth = 64.dp
    val trackHeight = 28.dp
    val thumbHeight = 24.dp
    val thumbWidth = 39.dp

    val backdrop = rememberLayerBackdrop()
    val switchBackdrop = rememberLayerBackdrop()
    val fraction by remember {
        derivedStateOf { if (checked) 1f else 0f }
    }
    val animatedFraction = remember { Animatable(fraction) }
    val trackWidthPx = remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()
    val progressAnimationSpec = spring(0.5f, 300f, 0.001f)
    val colorAnimationSpec = tween<Color>(200, easing = FastOutSlowInEasing)
    val progressAnimation = remember { Animatable(0f) }
    val innerShadowLayer = rememberGraphicsLayer().apply {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    val animatedTrackColor = remember { Animatable(if (checked) onColor else offColor) }
    val totalDrag = remember { mutableFloatStateOf(0f) }
    val tapThreshold = 10f
    val isFirstComposition = remember { mutableStateOf(true) }
    LaunchedEffect(checked) {
        if (!isFirstComposition.value) {
            coroutineScope {
                launch {
                    val targetColor = if (checked) onColor else offColor
                    animatedTrackColor.animateTo(targetColor, colorAnimationSpec)
                }
                launch {
                    val targetFrac = if (checked) 1f else 0f
                    animatedFraction.animateTo(targetFrac, progressAnimationSpec)
                }
                if (progressAnimation.value > 0f) return@coroutineScope
                launch {
                    progressAnimation.animateTo(1f, tween(175, easing = FastOutSlowInEasing))
                    progressAnimation.animateTo(0f, tween(175, easing = FastOutSlowInEasing))
                }
            }
        }
        isFirstComposition.value = false
    }

    Box(
        modifier = Modifier
            .width(trackWidth)
            .height(trackHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .layerBackdrop(switchBackdrop)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(animatedTrackColor.value)
                .width(trackWidth)
                .height(trackHeight)
                .onSizeChanged { trackWidthPx.floatValue = it.width.toFloat() }
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .graphicsLayer {
                    translationX = animatedFraction.value * (trackWidthPx.floatValue - with(density) { thumbWidth.toPx() + 4.dp.toPx() })
                }
                .then(if (enabled) Modifier.draggable(
                    rememberDraggableState { delta ->
                        if (trackWidthPx.floatValue > 0f) {
                            val newFraction = (animatedFraction.value + delta / trackWidthPx.floatValue).fastCoerceIn(-0.3f, 1.3f)
                            animationScope.launch {
                                animatedFraction.snapTo(newFraction)
                            }
                            totalDrag.floatValue += kotlin.math.abs(delta)
                            val newChecked = newFraction >= 0.5f
                            if (newChecked != checked) {
                                onCheckedChange(newChecked)
                            }
                        }
                    },
                    Orientation.Horizontal,
                    startDragImmediately = true,
                    onDragStarted = {
                        totalDrag.floatValue = 0f
                        animationScope.launch {
                            progressAnimation.animateTo(1f, progressAnimationSpec)
                        }
                    },
                    onDragStopped = {
                        animationScope.launch {
                            if (totalDrag.floatValue < tapThreshold) {
                                val newChecked = !checked
                                onCheckedChange(newChecked)
                                val snappedFraction = if (newChecked) 1f else 0f
                                coroutineScope {
                                    launch { progressAnimation.animateTo(0f, progressAnimationSpec) }
                                    launch { animatedFraction.animateTo(snappedFraction, progressAnimationSpec) }
                                }
                            } else {
                                val snappedFraction = if (animatedFraction.value >= 0.5f) 1f else 0f
                                onCheckedChange(snappedFraction >= 0.5f)
                                coroutineScope {
                                    launch { progressAnimation.animateTo(0f, progressAnimationSpec) }
                                    launch { animatedFraction.animateTo(snappedFraction, progressAnimationSpec) }
                                }
                            }
                        }
                    }
                ) else Modifier)
                .drawBackdrop(
                    rememberCombinedBackdrop(backdrop, switchBackdrop),
                    { RoundedCornerShape(thumbHeight / 2) },
                    highlight = {
                        val progress = progressAnimation.value
                        Highlight.Ambient.copy(
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(0.05f)
                        )
                    },
                    layerBlock = {
                        val progress = progressAnimation.value
                        val scale = lerp(1f, 1.5f, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawBackdrop = { drawScope ->
                        drawIntoCanvas { canvas ->
                            canvas.save()
                            canvas.drawRect(
                                left = 0f,
                                top = 0f,
                                right = size.width,
                                bottom = size.height,
                                paint = Paint().apply {
                                    color = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
                                }
                            )
                            scale(0.7f) {
                                drawScope()
                            }
                        }
                    },
                    onDrawSurface = {
                        val progress = progressAnimation.value.fastCoerceIn(0f, 1f)

                        val shape = RoundedCornerShape(thumbHeight / 2)
                        val outline = shape.createOutline(size, layoutDirection, this)
                        val innerShadowOffset = 4f.dp.toPx()
                        val innerShadowBlurRadius = 4f.dp.toPx()

                        innerShadowLayer.alpha = progress
                        innerShadowLayer.renderEffect =
                            BlurEffect(
                                innerShadowBlurRadius,
                                innerShadowBlurRadius,
                                TileMode.Decal
                            )
                        innerShadowLayer.record {
                            drawOutline(outline, Color.Black.copy(0.2f))
                            translate(0f, innerShadowOffset) {
                                drawOutline(
                                    outline,
                                    Color.Transparent,
                                    blendMode = BlendMode.Clear
                                )
                            }
                        }
                        drawLayer(innerShadowLayer)

                        drawRect(Color.White.copy(1f - progress))
                    },
                    effects = {
                        refractionWithDispersion(6f.dp.toPx(), size.height / 2f)
                    }
                )
                .width(thumbWidth)
                .height(thumbHeight)
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun StyledSwitchPreview() {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    Box(
        modifier = Modifier
            .background(backgroundColor)
            .width(100.dp)
            .height(150.dp),
        contentAlignment = Alignment.Center
    ) {
        val checked = remember { mutableStateOf(true) }
        StyledSwitch(
            checked = checked.value,
            onCheckedChange = {
                checked.value = it
            },
            enabled = true,
        )
//        LaunchedEffect(Unit) {
//            delay(1000)
//            checked.value = false
//            delay(1000)
//            checked.value = true
//        }
    }
}
