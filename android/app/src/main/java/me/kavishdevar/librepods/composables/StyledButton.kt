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

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.refraction
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.utils.inspectDragGestures
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

@Composable
fun StyledButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    maxScale: Float = 0.1f,
    content: @Composable RowScope.() -> Unit,
) {
    val animationScope = rememberCoroutineScope()
    val progressAnimation = remember { Animatable(0f) }
    var pressStartPosition by remember { mutableStateOf(Offset.Zero) }
    val offsetAnimation = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var isPressed by remember { mutableStateOf(false) }

    val interactiveHighlightShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeShader(
                """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 offset;

half4 main(float2 coord) {
    float2 center = offset;
    float dist = distance(coord, center);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""
            )
        } else {
            null
        }
    }

    Row(
        modifier
            .then(
                if (!isInteractive) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(28f.dp) },
                        effects = {
                            blur(16f.dp.toPx())
                        },
                        layerBlock = null,
                        onDrawSurface = {
                            if (tint.isSpecified) {
                                drawRect(tint, blendMode = BlendMode.Hue)
                                drawRect(tint.copy(alpha = 0.75f))
                            } else {
                                drawRect(Color.White.copy(0.1f))
                            }
                            if (surfaceColor.isSpecified) {
                                val color = if (!isInteractive && isPressed) {
                                    Color(
                                        red = surfaceColor.red * 0.5f,
                                        green = surfaceColor.green * 0.5f,
                                        blue = surfaceColor.blue * 0.5f,
                                        alpha = surfaceColor.alpha
                                    )
                                } else {
                                    surfaceColor
                                }
                                drawRect(color)
                            }
                        },
                        onDrawFront = null,
                        highlight = { Highlight.Ambient.copy(alpha = 0f) }
                    )
                } else {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(28f.dp) },
                        effects = {
                            vibrancy()
                            blur(2f.dp.toPx())
                            refraction(12f.dp.toPx(), 24f.dp.toPx())
                        },
                        layerBlock = {
                            val width = size.width
                            val height = size.height

                            val progress = progressAnimation.value
                            val scale = lerp(1f, 1f + maxScale, progress)

                            val maxOffset = size.minDimension
                            val initialDerivative = 0.05f
                            val offset = offsetAnimation.value
                            translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                            translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                            val maxDragScale = 0.1f
                            val offsetAngle = atan2(offset.y, offset.x)
                            scaleX =
                                scale +
                                        maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                                        (width / height).fastCoerceAtMost(1f)
                            scaleY =
                                scale +
                                        maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                                        (height / width).fastCoerceAtMost(1f)
                        },
                        onDrawSurface = {
                            if (tint.isSpecified) {
                                drawRect(tint, blendMode = BlendMode.Hue)
                                drawRect(tint.copy(alpha = 0.75f))
                            } else {
                                drawRect(Color.White.copy(0.1f))
                            }
                            if (surfaceColor.isSpecified) {
                                val color = if (!isInteractive && isPressed) {
                                    Color(
                                        red = surfaceColor.red * 0.5f,
                                        green = surfaceColor.green * 0.5f,
                                        blue = surfaceColor.blue * 0.5f,
                                        alpha = surfaceColor.alpha
                                    )
                                } else {
                                    surfaceColor
                                }
                                drawRect(color)
                            }
                        },
                        onDrawFront = {
                            val progress = progressAnimation.value.fastCoerceIn(0f, 1f)
                            if (progress > 0f) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && interactiveHighlightShader != null) {
                                    drawRect(
                                        Color.White.copy(0.1f * progress),
                                        blendMode = BlendMode.Plus
                                    )
                                    interactiveHighlightShader.apply {
                                        val offset = pressStartPosition + offsetAnimation.value
                                        setFloatUniform("size", size.width, size.height)
                                        setColorUniform("color", Color.White.copy(0.15f * progress).toArgb())
                                        setFloatUniform("radius", size.maxDimension)
                                        setFloatUniform(
                                            "offset",
                                            offset.x.fastCoerceIn(0f, size.width),
                                            offset.y.fastCoerceIn(0f, size.height)
                                        )
                                    }
                                    drawRect(
                                        ShaderBrush(interactiveHighlightShader),
                                        blendMode = BlendMode.Plus
                                    )
                                } else {
                                    drawRect(
                                        Color.White.copy(0.25f * progress),
                                        blendMode = BlendMode.Plus
                                    )
                                }
                            }
                        }
                    )
                }
            )
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .then(
                if (isInteractive) {
                    Modifier.pointerInput(animationScope) {
                        val progressAnimationSpec = spring(0.5f, 300f, 0.001f)
                        val offsetAnimationSpec = spring(1f, 300f, Offset.VisibilityThreshold)
                        val onDragStop: () -> Unit = {
                            animationScope.launch {
                                launch { progressAnimation.animateTo(0f, progressAnimationSpec) }
                                launch { offsetAnimation.animateTo(Offset.Zero, offsetAnimationSpec) }
                            }
                        }
                        inspectDragGestures(
                            onDragStart = { down ->
                                pressStartPosition = down.position
                                animationScope.launch {
                                    launch { progressAnimation.animateTo(1f, progressAnimationSpec) }
                                    launch { offsetAnimation.snapTo(Offset.Zero) }
                                }
                            },
                            onDragEnd = { onDragStop() },
                            onDragCancel = onDragStop
                        ) { _, dragAmount ->
                            animationScope.launch {
                                offsetAnimation.snapTo(offsetAnimation.value + dragAmount)
                            }
                        }
                    }
                } else {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                tryAwaitRelease()
                                isPressed = false
                            },
                            onTap = {
                                onClick()
                            }
                        )
                    }
                }
            )
            .height(48f.dp)
            .padding(horizontal = 16f.dp),
        horizontalArrangement = Arrangement.spacedBy(8f.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
