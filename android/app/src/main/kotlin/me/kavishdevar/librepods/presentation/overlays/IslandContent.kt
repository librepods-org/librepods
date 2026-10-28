package me.kavishdevar.librepods.presentation.overlays

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.data.app.OverlayBackgroundColor
import me.kavishdevar.librepods.data.app.OverlayBatteryStyle
import me.kavishdevar.librepods.data.app.resolve
import me.kavishdevar.librepods.presentation.components.apple.NoiseControlSettings
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.design.LocalDesign
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.core.R as coreR

@Composable
fun IslandContent(
    name: String,
    type: IslandType,
    batteryLevel: Int,
    reversed: Boolean,
    otherDeviceName: String?,

    islandSettings: IslandSettings,
    islandState: IslandState,

    openApp: () -> Unit = {},
    changeState: (IslandState, Boolean) -> Unit = { _, _ ->},
    onClosed: () -> Unit = {},
    resetTimer: () -> Unit = {},

    showOffListeningMode: Boolean,
    noiseControlModeValue: Int,
    onNoiseControlModeChanged: (Int) -> Unit,
    adaptiveStrength: Int = 0,
    onAdaptiveStrengthChanged: (Int) -> Unit,
) {
    val density = LocalDensity.current

    // Starts as a dot below the camera and grows out of it.
    val animWidth = remember { Animatable(islandSettings.compactHeight) }
    val animHeight = remember { Animatable(islandSettings.compactHeight) }
    val animRadius = remember { Animatable(islandSettings.compactBorderRadius) }
    val animBorderWidth = remember { Animatable(0f) }

    // Opens with a slight overshoot like the iOS island, and closes without one.
    val springSpec = spring<Float>(
        dampingRatio = if (islandState == IslandState.CLOSE) Spring.DampingRatioNoBouncy else 0.78f,
        stiffness = if (islandState == IslandState.CLOSE) Spring.StiffnessMedium else Spring.StiffnessMediumLow
    )

    LaunchedEffect(
        islandState,
        islandSettings.expandedWidth,
        islandSettings.expandedHeight,
        islandSettings.compactWidth,
        islandSettings.compactHeight,
        islandSettings.expandedBorderRadius,
        islandSettings.compactBorderRadius,
        islandSettings.expandedBorderWidth,
        islandSettings.compactBorderWidth
    ) {
        val (targetW, targetH) = when (islandState) {
            IslandState.EXPANDED -> islandSettings.expandedWidth to islandSettings.expandedHeight
            IslandState.COMPACT -> islandSettings.compactWidth to islandSettings.compactHeight
            IslandState.CLOSE -> islandSettings.compactHeight to islandSettings.compactHeight
        }

        val targetRadius = when (islandState) {
            IslandState.EXPANDED -> islandSettings.expandedBorderRadius
            IslandState.COMPACT -> islandSettings.compactBorderRadius
            IslandState.CLOSE -> islandSettings.compactBorderRadius
        }

        val targetBorderWidth = when (islandState) {
            IslandState.EXPANDED -> islandSettings.expandedBorderWidth
            IslandState.COMPACT -> islandSettings.compactBorderWidth
            IslandState.CLOSE -> islandSettings.compactBorderWidth
        }

        val wJob = launch { animWidth.animateTo(targetW, springSpec) }
        val hJob = launch { animHeight.animateTo(targetH, springSpec) }
        val rJob = launch { animRadius.animateTo(targetRadius, springSpec) }
        val bJob = launch { animBorderWidth.animateTo(targetBorderWidth, springSpec) }

        wJob.join()
        hJob.join()
        rJob.join()
        bJob.join()

        if (islandState == IslandState.CLOSE) {
            onClosed()
        }
    }

    val scale = remember { Animatable(0f) }

    // Content blurs in once the island has room for it, and blurs out before it shrinks.
    val contentReveal = remember { Animatable(0f) }
    LaunchedEffect(islandState) {
        if (islandState == IslandState.CLOSE) contentReveal.animateTo(0f, tween(120))
        else contentReveal.animateTo(1f, tween(250, delayMillis = 90))
    }

    val widthDp = with(density) { animWidth.value.coerceAtLeast(1f).toDp() * (1f + scale.value * if (islandState == IslandState.EXPANDED) 0.01f else 0.1f) }
    val heightDp = with(density) { animHeight.value.coerceAtLeast(1f).toDp() * (1f + scale.value * if (islandState == IslandState.EXPANDED) 0.01f else 0.05f) }
    val borderWidthDp = with(density) { animBorderWidth.value.toDp() }

    val padding = animateFloatAsState(
        targetValue = when (islandState) {
            IslandState.EXPANDED -> 16f
            IslandState.COMPACT -> 8f
            else -> 0f
        },
        animationSpec = tween(100)
    )

    val backgroundColor = islandSettings.expandedBackgroundColor.resolve()

    val backgroundColorWithAlpha = animateColorAsState(
        targetValue = when (islandState) {
            IslandState.EXPANDED -> backgroundColor.copy(alpha = islandSettings.expandedBackgroundAlpha)
            IslandState.COMPACT -> Color.Black
            IslandState.CLOSE -> backgroundColor.copy(alpha = 0f)
        },
        // When closing, stay visible while shrinking and fade out at the end.
        animationSpec = if (islandState == IslandState.CLOSE) tween(150, delayMillis = 200) else tween(100)
    )

    val textColor = if (islandSettings.expandedBackgroundColor is OverlayBackgroundColor.Default) Color.White else contentColorFor(backgroundColor)
    val shape = RoundedCornerShape(with(density) { animRadius.value.toDp() } )

    val typeText = when (type) {
        IslandType.CONNECTED -> stringResource(R.string.island_connected_text)
        IslandType.TAKING_OVER -> stringResource(R.string.island_taking_over_text)
        IslandType.MOVED_TO_REMOTE -> stringResource(R.string.island_moved_to_remote_text)
        IslandType.MOVED_TO_OTHER_DEVICE -> {
            if (otherDeviceName.isNullOrEmpty()) {
                Log.w("IslandWindow", "Other device name is null or empty for MOVED_TO_OTHER_DEVICE type")
            }
            if (reversed) {
                stringResource(R.string.island_moved_to_other_device_reversed_text)
            } else {
                stringResource(R.string.island_moved_to_other_device_text, otherDeviceName ?: "")
            }
        }
    }

    val listeningModeControlHeight = with(density) { if (islandSettings.showListeningModeControl) 72.dp.toPx() else 0f }

    val bottomAlign = islandSettings.expandedHeight - listeningModeControlHeight > 280f &&
        islandSettings.offsetY < 150f

    val animationCoroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .requiredSize(width = widthDp, height = heightDp)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(backgroundColorWithAlpha.value, shape)
                .border(
                    width = borderWidthDp,
                    color = textColor.copy(if (borderWidthDp > 0.dp) 0.2f else 0f),
                    shape = shape
                )
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            resetTimer()
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount > 0) changeState(IslandState.EXPANDED, true)
                        else changeState(
                            if (islandSettings.keepCompactVisible) IslandState.COMPACT else IslandState.CLOSE,
                            true
                        )
                    }
                }
                .padding(padding.value.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .graphicsLayer { alpha = contentReveal.value }
                    .blur(10.dp * (1f - contentReveal.value), BlurredEdgeTreatment.Unbounded)
                    .pointerInput(islandState) {
                        detectTapGestures(
                            onPress = {
                                scale.animateTo(1f, springSpec)
                                tryAwaitRelease()
                                scale.animateTo(0f)
                            },
                            onTap = {
                                when (islandState) {
                                    IslandState.EXPANDED -> {
                                        if (islandSettings.expandedOpenAppOnTap) openApp()
                                        else if (islandSettings.keepCompactVisible) changeState(
                                            IslandState.COMPACT,
                                            false
                                        )
                                    }

                                    IslandState.COMPACT -> {
                                        if (islandSettings.compactOpenAppOnTap) openApp()
                                        else changeState(IslandState.EXPANDED, true)
                                    }

                                    else -> {}
                                }
                            },
                            onLongPress = {
                                animationCoroutineScope.launch { scale.animateTo(0f) }
                                when (islandState) {
                                    IslandState.EXPANDED -> {
                                        if (!islandSettings.expandedOpenAppOnTap) openApp()
                                        else if (islandSettings.keepCompactVisible) changeState(
                                            IslandState.COMPACT,
                                            false
                                        )
                                    }

                                    IslandState.COMPACT -> {
                                        if (!islandSettings.compactOpenAppOnTap) openApp()
                                        else changeState(IslandState.EXPANDED, true)
                                    }

                                    else -> {}
                                }
                            }
                        )
                    },
                verticalAlignment = if (bottomAlign && islandState == IslandState.EXPANDED) Alignment.Bottom else Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // In the expanded island the icon and battery ring fill its height, as on iOS.
                val accessoryHeightFraction = if (islandState == IslandState.EXPANDED) 1f else 0.8f
                val useMonochrome = when (islandState) {
                    IslandState.EXPANDED -> islandSettings.expandedUseMonochrome
                    IslandState.COMPACT -> islandSettings.compactUseMonochrome
                    IslandState.CLOSE -> islandSettings.compactUseMonochrome
                }
                AnimatedContent(
                    targetState = useMonochrome,
                ) {
                    if (it) {
                        Icon(
                            imageVector = LocalIcons.current.AirPodsPro3,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.fillMaxHeight(accessoryHeightFraction)
                        )
                    } else {
                        Image(
                            bitmap = ImageBitmap.imageResource(coreR.drawable.img_airpods_pro_2_buds), // TODO: use correct icon for model
                            contentDescription = null,
                            modifier = Modifier
                                .heightIn(max = 64.dp)
                                .aspectRatio(1f)
                                .fillMaxHeight(accessoryHeightFraction)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = islandState == IslandState.EXPANDED,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Column {
                        AnimatedVisibility(visible = islandSettings.fontSize > 15) {
                            Text(
                                text = typeText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = islandSettings.fontSize.sp * 0.78f,
                                fontWeight = FontWeight.Normal,
                                color = textColor.copy(0.6f),
                            )
                        }

                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = islandSettings.fontSize.sp,
                            color = textColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                val currentBatteryStyle = when (islandState) {
                    IslandState.EXPANDED -> islandSettings.expandedBatteryStyle
                    IslandState.COMPACT -> islandSettings.compactBatteryStyle
                    IslandState.CLOSE -> islandSettings.compactBatteryStyle
                }

                val showRing = currentBatteryStyle == OverlayBatteryStyle.CIRCLE || currentBatteryStyle == OverlayBatteryStyle.CIRCLE_TEXT
                val showText = currentBatteryStyle == OverlayBatteryStyle.TEXT || currentBatteryStyle == OverlayBatteryStyle.CIRCLE_TEXT

                BoxWithConstraints(
                    modifier = Modifier
                        .heightIn(max = 64.dp)
                        .fillMaxHeight(accessoryHeightFraction)
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center
                ) {
                    val isDarkTheme = backgroundColor.luminance() < 0.5f
                    val batteryColor =
                        if (batteryLevel > 25) if (isDarkTheme) Color(0xFF2ED158) else Color(0xFF35C759)
                        else if (isDarkTheme) Color(0xFFFC4244) else Color(0xFFfe373C)

                    this@Row.AnimatedVisibility(
                        visible = showRing,
                        enter = fadeIn(animationSpec = tween(180, delayMillis = 80)),
                        exit = fadeOut(animationSpec = tween(100)),
                    ) {
                        val strokeWidthPx = with(LocalDensity.current) { (maxHeight / 11f).toPx() }

                        val progress = batteryLevel / 100f

                        Canvas(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            val startAngle = -90f
                            val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)

                            drawArc(
                                color = batteryColor.copy(0.2f),
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = stroke
                            )

                            drawArc(
                                color = batteryColor,
                                startAngle = startAngle,
                                sweepAngle = 360f * progress,
                                useCenter = false,
                                style = stroke
                            )
                        }
                    }
                    this@Row.AnimatedVisibility(
                        visible = showText,
                        enter = fadeIn(animationSpec = tween(180, delayMillis = 80)),
                        exit = fadeOut(animationSpec = tween(100)),
                    ) {
                        val fontSize = with(LocalDensity.current) {
                            (maxHeight / 2.9f).toSp()
                        }
                        Text(
                            text = batteryLevel.toString(),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = fontSize,
                                fontWeight = FontWeight.Normal
                            ),
                            color = batteryColor,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = islandSettings.showListeningModeControl && islandState == IslandState.EXPANDED,
                enter = fadeIn(animationSpec = tween(180, delayMillis = 80)),
                exit = fadeOut(animationSpec = tween(100)),
                modifier = Modifier
                    .requiredHeightIn(min = 20.dp, max = 64.dp)
                    .requiredWidth(with(density) { islandSettings.expandedWidth.toDp() - padding.value.dp - 12.dp })
                    .weight(1f)
            ) {
                LibrePodsTheme(
                    design = LocalDesign.current,
                    darkTheme = backgroundColor.luminance() < 0.5f
                ){
                    NoiseControlSettings(
                        modifier = Modifier.fillMaxHeight(),
                        showOffListeningMode = showOffListeningMode,
                        noiseControlModeValue = noiseControlModeValue,
                        onNoiseControlModeChanged = onNoiseControlModeChanged,
                        adaptiveStrength = adaptiveStrength,
                        onAdaptiveStrengthChanged = onAdaptiveStrengthChanged,
                        showLabels = false
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun IslandContentPreview(){
    IslandContent(
        name = "Kavish's AirPods Pro 3",
        type = IslandType.CONNECTED,
        batteryLevel = 75,
        reversed = false,
        otherDeviceName = null,

        islandState = IslandState.EXPANDED,
        islandSettings = IslandSettings(),

        openApp = {},
        changeState = { _, _ -> },
        onClosed = {},
        resetTimer = {},

        showOffListeningMode = false,
        noiseControlModeValue = 0,
        onNoiseControlModeChanged = {},
        adaptiveStrength = 0,
        onAdaptiveStrengthChanged = {}
    )
}
