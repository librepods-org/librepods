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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.refractionWithDispersion
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.utils.inspectDragGestures
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

@Composable
fun StyledIconButton(
    onClick: () -> Unit,
    icon: String,
    darkMode: Boolean,
    tint: Color = Color.Unspecified,
    backdrop: LayerBackdrop = rememberLayerBackdrop(),
    modifier: Modifier = Modifier,
) {
    val animationScope = rememberCoroutineScope()
    val progressAnimationSpec = spring(0.5f, 300f, 0.001f)
    val offsetAnimationSpec = spring(1f, 300f, Offset.VisibilityThreshold)
    val progressAnimation = remember { Animatable(0f) }
    val offsetAnimation = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var pressStartPosition by remember { mutableStateOf(Offset.Zero) }
    val innerShadowLayer = rememberGraphicsLayer().apply {
        compositingStrategy = CompositingStrategy.Offscreen
    }

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
    val isDarkTheme = isSystemInDarkTheme()
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(56.dp),
        modifier = modifier
            .padding(horizontal = 12.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(56.dp) },
                highlight = { Highlight.Ambient.copy(alpha = if (isDarkTheme) 1f else 0f) },
                shadow = {
                    Shadow(
                        radius = 12f.dp,
                        color = Color.Black.copy(if (isDarkTheme) 0.08f else 0.2f)
                    )
                },
                layerBlock = {
                    val width = size.width
                    val height = size.height

                    val progress = progressAnimation.value
                    val scale = lerp(1f, 1.5f, progress)

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
                    val progress = progressAnimation.value.coerceIn(0f, 1f)

                    val shape = RoundedCornerShape(56.dp)
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

                    drawRect(
                        (if (isDarkTheme) Color(0xFFAFAFAF) else Color.White).copy(progress.coerceIn(0.15f, 0.35f))
                    )
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
                },
                effects = {
                    refractionWithDispersion(6f.dp.toPx(), size.height / 2f)
                    // blur(24f, TileMode.Decal)
                },
            )
            .pointerInput(animationScope) {
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
            .size(48.dp),
    ) {
        Text(
            text = icon,
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = if (tint.isSpecified) tint else if (darkMode) Color.White else Color.Black,
                fontFamily = FontFamily(Font(R.font.sf_pro))
            )
        )
    }
}
