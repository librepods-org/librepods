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
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.refractionWithDispersion
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.utils.inspectDragGestures
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun rememberMomentumAnimation(
    maxScale: Float,
    progressAnimationSpec: FiniteAnimationSpec<Float> =
        spring(1f, 1000f, 0.01f),
    velocityAnimationSpec: FiniteAnimationSpec<Float> =
        spring(0.5f, 250f, 5f),
    scaleXAnimationSpec: FiniteAnimationSpec<Float> =
        spring(0.4f, 400f, 0.01f),
    scaleYAnimationSpec: FiniteAnimationSpec<Float> =
        spring(0.6f, 400f, 0.01f)
): MomentumAnimation {
    val animationScope = rememberCoroutineScope()
    return remember(
        maxScale,
        animationScope,
        progressAnimationSpec,
        velocityAnimationSpec,
        scaleXAnimationSpec,
        scaleYAnimationSpec
    ) {
        MomentumAnimation(
            maxScale = maxScale,
            animationScope = animationScope,
            progressAnimationSpec = progressAnimationSpec,
            velocityAnimationSpec = velocityAnimationSpec,
            scaleXAnimationSpec = scaleXAnimationSpec,
            scaleYAnimationSpec = scaleYAnimationSpec
        )
    }
}

class MomentumAnimation(
    val maxScale: Float,
    private val animationScope: CoroutineScope,
    private val progressAnimationSpec: FiniteAnimationSpec<Float>,
    private val velocityAnimationSpec: FiniteAnimationSpec<Float>,
    private val scaleXAnimationSpec: FiniteAnimationSpec<Float>,
    private val scaleYAnimationSpec: FiniteAnimationSpec<Float>
) {

    private val velocityTracker = VelocityTracker()

    private val progressAnimation = Animatable(0f)
    private val velocityAnimation = Animatable(0f)
    private val scaleXAnimation = Animatable(1f)
    private val scaleYAnimation = Animatable(1f)

    val progress: Float get() = progressAnimation.value
    val velocity: Float get() = velocityAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value

    var isDragging: Boolean by mutableStateOf(false)
        private set

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = {
                isDragging = true
                velocityTracker.resetTracking()
                startPressingAnimation()
            },
            onDragEnd = { change ->
                isDragging = false
                val velocity = velocityTracker.calculateVelocity()
                updateVelocity(velocity)
                velocityTracker.addPointerInputChange(change)
                velocityTracker.resetTracking()
                endPressingAnimation()
                settleVelocity()
            },
            onDragCancel = {
                isDragging = false
                velocityTracker.resetTracking()
                endPressingAnimation()
                settleVelocity()
            }
        ) { change, _ ->
            isDragging = true
            velocityTracker.addPointerInputChange(change)
            val velocity = velocityTracker.calculateVelocity()
            updateVelocity(velocity)
        }
    }

    private fun updateVelocity(velocity: Velocity) {
        animationScope.launch { velocityAnimation.animateTo(velocity.x, velocityAnimationSpec) }
    }

    private fun settleVelocity() {
        animationScope.launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
    }

    fun startPressingAnimation() {
        animationScope.launch {
            launch { progressAnimation.animateTo(1f, progressAnimationSpec) }
            launch { scaleXAnimation.animateTo(maxScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(maxScale, scaleYAnimationSpec) }
        }
    }

    fun endPressingAnimation() {
        animationScope.launch {
            launch { progressAnimation.animateTo(0f, progressAnimationSpec) }
            launch { scaleXAnimation.animateTo(1f, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(1f, scaleYAnimationSpec) }
        }
    }
}

@Composable
fun StyledSlider(
    label: String? = null,
    mutableFloatState: MutableFloatState,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    backdrop: Backdrop = rememberLayerBackdrop(),
    snapPoints: List<Float> = emptyList(),
    snapThreshold: Float = 0.05f,
    startIcon: String? = null,
    endIcon: String? = null,
    startLabel: String? = null,
    endLabel: String? = null,
    independent: Boolean = false,
    description: String? = null
) {
    val backgroundColor = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val trackColor =
        if (isLightTheme) Color(0xFF787878).copy(0.2f)
        else Color(0xFF787880).copy(0.36f)
    val labelTextColor = if (isLightTheme) Color.Black else Color.White

    val fraction by remember {
        derivedStateOf {
            ((mutableFloatState.floatValue - valueRange.start) / (valueRange.endInclusive - valueRange.start))
                .fastCoerceIn(0f, 1f)
        }
    }

    val sliderBackdrop = rememberLayerBackdrop()
    val trackWidthState = remember { mutableFloatStateOf(0f) }
    val trackPositionState = remember { mutableFloatStateOf(0f) }
    val startIconWidthState = remember { mutableFloatStateOf(0f) }
    val endIconWidthState = remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    val momentumAnimation = rememberMomentumAnimation(maxScale = 1.5f)

    val content = @Composable {
        Box(
            Modifier
                .fillMaxWidth(if (startIcon == null && endIcon == null) 0.95f else 1f)
        ) {
            Box(
                Modifier
                    .padding(vertical = 4.dp)
                    .layerBackdrop(sliderBackdrop)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(1f)
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (startLabel != null || endLabel != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = startLabel ?: "",
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = labelTextColor,
                                    fontFamily = FontFamily(Font(R.font.sf_pro))
                                )
                            )
                            Text(
                                text = endLabel ?: "",
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = labelTextColor,
                                    fontFamily = FontFamily(Font(R.font.sf_pro))
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .then(if (startIcon == null && endIcon == null) Modifier.padding(horizontal = 8.dp) else Modifier),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            if (startIcon != null) {
                                Text(
                                    text = startIcon,
                                    style = TextStyle(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = accentColor,
                                        fontFamily = FontFamily(Font(R.font.sf_pro))
                                    ),
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp)
                                        .onGloballyPositioned {
                                            startIconWidthState.floatValue = it.size.width.toFloat()
                                        }
                                )
                            }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .onSizeChanged { trackWidthState.floatValue = it.width.toFloat() }
                                    .onGloballyPositioned {
                                        trackPositionState.floatValue =
                                            it.positionInParent().y + it.size.height / 2f
                                    }
                            ) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(28.dp))
                                        .background(trackColor)
                                        .height(6f.dp)
                                        .fillMaxWidth()
                                )

                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(28.dp))
                                        .background(accentColor)
                                        .height(6f.dp)
                                        .layout { measurable, constraints ->
                                            val placeable = measurable.measure(constraints)
                                            val fraction = fraction
                                            val width =
                                                (fraction * constraints.maxWidth).fastRoundToInt()
                                            layout(width, placeable.height) {
                                                placeable.place(0, 0)
                                            }
                                        }
                                )
                            }
                            if (endIcon != null) {
                                Text(
                                    text = endIcon,
                                    style = TextStyle(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = accentColor,
                                        fontFamily = FontFamily(Font(R.font.sf_pro))
                                    ),
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp)
                                        .onGloballyPositioned {
                                            endIconWidthState.floatValue = it.size.width.toFloat()
                                        }
                                )
                            }
                        }
                        if (snapPoints.isNotEmpty() && startLabel != null && endLabel != null) Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (snapPoints.isNotEmpty()) {
                                val trackWidth = if (startIcon != null && endIcon != null) trackWidthState.floatValue - with(density) { 6.dp.toPx() } * 2 else trackWidthState.floatValue- with(density) { 22.dp.toPx() }
                                val startOffset =
                                    if (startIcon != null) startIconWidthState.floatValue + with(
                                        density
                                    ) { 34.dp.toPx() } else with(density) { 14.dp.toPx() }
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                ) {
                                    snapPoints.forEach { point ->
                                        val pointFraction =
                                            ((point - valueRange.start) / (valueRange.endInclusive - valueRange.start))
                                                .fastCoerceIn(0f, 1f)
                                        Box(
                                            Modifier
                                                .graphicsLayer {
                                                    translationX =
                                                        startOffset + pointFraction * trackWidth - 4.dp.toPx()
                                                }
                                                .size(2.dp)
                                                .background(
                                                    trackColor,
                                                    CircleShape
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Box(
                Modifier
                    .graphicsLayer {
//                        val startOffset =
//                            if (startIcon != null) startIconWidthState.floatValue + with(density) { 24.dp.toPx() } else with(density) { 12.dp.toPx() }
//                        translationX =
//                            startOffset + fraction * trackWidthState.floatValue - size.width / 2f
                        val startOffset =
                            if (startIcon != null)
                                startIconWidthState.floatValue + with(density) { 24.dp.toPx() }
                            else
                                with(density) { 8.dp.toPx() }

                        translationX =
                            (startOffset + fraction * trackWidthState.floatValue - size.width / 2f)
                                .fastCoerceIn(
                                    startOffset - size.width / 4f,
                                    startOffset + trackWidthState.floatValue - size.width * 3f / 4f
                                )
                        translationY =  if (startLabel != null || endLabel != null) trackPositionState.floatValue + with(density) { 26.dp.toPx() } + size.height / 2f else trackPositionState.floatValue + with(density) { 8.dp.toPx() }
                    }
                    .draggable(
                        rememberDraggableState { delta ->
                            val trackWidth = trackWidthState.floatValue
                            if (trackWidth > 0f) {
                                val targetFraction = fraction + delta / trackWidth
                                val targetValue =
                                    lerp(valueRange.start, valueRange.endInclusive, targetFraction)
                                        .fastCoerceIn(valueRange.start, valueRange.endInclusive)
                                val snappedValue = if (snapPoints.isNotEmpty()) snapIfClose(
                                    targetValue,
                                    snapPoints,
                                    snapThreshold
                                ) else targetValue
                                onValueChange(snappedValue)
                            }
                        },
                        Orientation.Horizontal,
                        startDragImmediately = true,
                        onDragStarted = {
                            // Remove this block as momentumAnimation handles pressing
                        },
                        onDragStopped = {
                            // Remove this block as momentumAnimation handles pressing
                            onValueChange((mutableFloatState.floatValue * 100).roundToInt() / 100f)
                        }
                    )
                    .then(momentumAnimation.modifier)
                    .drawBackdrop(
                        rememberCombinedBackdrop(backdrop, sliderBackdrop),
                        { RoundedCornerShape(28.dp) },
                        highlight = {
                            val progress = momentumAnimation.progress
                            Highlight.Ambient.copy(alpha = progress)
                        },
                        shadow = {
                            Shadow(
                                radius = 4f.dp,
                                color = Color.Black.copy(0.05f)
                            )
                        },
                        innerShadow = {
                            val progress = momentumAnimation.progress
                            InnerShadow(
                                radius = 4f.dp * progress,
                                alpha = progress
                            )
                        },
                        layerBlock = {
                            scaleX = momentumAnimation.scaleX
                            scaleY = momentumAnimation.scaleY
                            val velocity = momentumAnimation.velocity / 5000f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.15f, 0.15f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.15f, 0.15f)
                        },
                        onDrawSurface = {
                            val progress = momentumAnimation.progress
                            drawRect(Color.White.copy(alpha = 1f - progress))
                        },
                        effects = {
                            val progress = momentumAnimation.progress
                            blur(8f.dp.toPx() * (1f - progress))
                            refractionWithDispersion(
                                height = 6f.dp.toPx() * progress,
                                amount = size.height / 2f * progress
                            )
                        }
                    )
                    .size(40f.dp, 24f.dp)
            )
        }
    }

    if (independent) {

        Column (
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (label != null) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = labelTextColor.copy(alpha = 0.6f),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    ),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor, RoundedCornerShape(28.dp))
                    .padding(horizontal = 8.dp, vertical = 0.dp)
                    .heightIn(min = 58.dp),
                contentAlignment = Alignment.Center
            ) {
                content()
            }

            if (description != null) {
                Text(
                    text = description,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Light,
                        color = (if (isSystemInDarkTheme()) Color.White else Color.Black).copy(alpha = 0.6f),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    ),
                    modifier = Modifier
                        .padding(horizontal = 18.dp, vertical = 4.dp)
                )
            }
        }
    } else {
        if (label != null) Log.w("StyledSlider", "Label is ignored when independent is false")
        if (description != null) Log.w("StyledSlider", "Description is ignored when independent is false")
        content()
    }
}

private fun snapIfClose(value: Float, points: List<Float>, threshold: Float = 0.05f): Float {
    val nearest = points.minByOrNull { abs(it - value) } ?: value
    return if (abs(nearest - value) <= threshold) nearest else value
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun StyledSliderPreview() {
    val a = remember { mutableFloatStateOf(0.5f) }
    Box(
        Modifier
            .background(if (isSystemInDarkTheme()) Color(0xFF000000) else Color(0xFFF0F0F0))
            .padding(16.dp)
            .fillMaxSize()
    ) {
        Box (
            Modifier.align(Alignment.Center)
        )
        {
            StyledSlider(
                mutableFloatState = a,
                onValueChange = {
                    a.floatValue = it
                },
                valueRange = 0f..2f,
                snapPoints = listOf(1f),
                snapThreshold = 0.1f,
                independent = true,
                startIcon = "A",
                endIcon = "B",
            )
        }
    }
}
