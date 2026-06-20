package me.kavishdevar.librepods.presentation.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.toPath
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.AndroidUiModes.UI_MODE_NIGHT_NO
import androidx.compose.ui.tooling.preview.AndroidUiModes.UI_MODE_NIGHT_YES
import androidx.compose.ui.tooling.preview.Devices.PIXEL_9_PRO_XL
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.Wallpapers.GREEN_DOMINATED_EXAMPLE
import androidx.compose.ui.tooling.preview.Wallpapers.RED_DOMINATED_EXAMPLE
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.updates.UpdateItem
import me.kavishdevar.librepods.data.updates.update0_3_1
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseNotesScreen(
    updates: List<UpdateItem>,
    releaseNotesShown: () -> Unit
) {
    val state = rememberCarouselState(
        initialItem = 0,
        itemCount = { updates.size + 1 }
    )

    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material

    LibrePodsTheme(m3eEnabled = true) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(52.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(topPadding))
            Text(
                text = stringResource(R.string.what_s_new),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            val versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug")
            val url = "https://github.com/kavishdevar/librepods/releases/v$versionName"
            val fullText = "${stringResource(R.string.version)} $versionName"
            val textColor = MaterialTheme.colorScheme.primary

            val annotatedString = buildAnnotatedString {
                append(fullText)
                addLink(
                    url = LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(color = textColor)
                        )
                    ),
                    start = 0,
                    end = fullText.length
                )
            }
            Text(
                text = annotatedString,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.tertiary,
                textDecoration = TextDecoration.Underline
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalUncontainedCarousel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = state,
                itemSpacing = 16.dp,
                contentPadding = PaddingValues(horizontal = 16.dp),
                itemWidth = LocalWindowInfo.current.containerDpSize.width - 64.dp,
                flingBehavior = CarouselDefaults.singleAdvanceFlingBehavior(state)
            ) { index ->

                val shape = rememberMaskShape(RoundedCornerShape(48.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = shape,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        if (index != updates.size) {
                            val updateItem = updates[index]

                            val deviceBorderColor = MaterialTheme.colorScheme.tertiary

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(top = 16.dp)
                                    .heightIn(max = 700.dp)
                                    .align(Alignment.TopCenter)
                                    .drawWithCache {
                                        val path = createTopWavyRectPath(
                                            width = size.width,
                                            height = size.height,
                                            cornerRadius = 52.dp.toPx(),
                                            amplitude = 4.dp.toPx(),
                                            wavelength = 36.dp.toPx()
                                        )
                                        onDrawWithContent {
                                            drawContent()

                                            drawPath(
                                                path,
                                                color = deviceBorderColor,
                                                style = Stroke(
                                                    width = 4.dp.toPx()
                                                )
                                            )
                                        }
                                    }

                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(2.dp)
                                        .clip(RoundedCornerShape(50.dp))
                                ) {
                                    CompositionLocalProvider(
                                        LocalDensity provides Density(
                                            density = LocalDensity.current.density * 0.8f,
                                            fontScale = LocalDensity.current.fontScale
                                        ),
                                        LocalDesignSystem provides if (m3eEnabled) DesignSystem.Material else DesignSystem.Apple
                                    ) {
                                        updateItem.demoComposeable()
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .aspectRatio(1f)
                                    .background(
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f),
                                        MaterialShapes.Arch.toShape()
                                    )
                                    .align(Alignment.BottomCenter)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(32.dp)
                                        .align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(updateItem.titleRes),
                                        style = MaterialTheme.typography.displayMediumEmphasized,
                                        color = MaterialTheme.colorScheme.onTertiary,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = stringResource(updateItem.descriptionRes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onTertiary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            var pressed by remember { mutableStateOf(false) }

                            val morph = remember {
                                Morph(
                                    MaterialShapes.Cookie7Sided.normalized(),
                                    MaterialShapes.SoftBurst.normalized()
                                )
                            }

                            val morphProgress by animateFloatAsState(
                                targetValue = if (pressed) 1f else 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "morph",
                            )

                            val rotationSpeed by animateFloatAsState(
                                targetValue = if (pressed) 0f else 1f,
                                animationSpec = tween(1200),
                                label = "rotationSpeed"
                            )

                            var rotation by remember { mutableFloatStateOf(0f) }

                            LaunchedEffect(Unit) {
                                var lastFrame = withFrameNanos { it }

                                while (true) {
                                    val frame = withFrameNanos { it }

                                    val dt = (frame - lastFrame) / 1_000_000_000f
                                    lastFrame = frame

                                    rotation += 60f * rotationSpeed * dt
                                }
                            }

                            val path = remember { Path() }
                            val matrix = remember { Matrix() }

                            val tertiary = MaterialTheme.colorScheme.tertiary

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .align(Alignment.Center)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onPress = {
                                                pressed = true
                                                tryAwaitRelease()
                                                pressed = false
                                            }
                                        )
                                    }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(260.dp)
                                        .align(Alignment.Center)
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onTap = { releaseNotesShown() },
                                                onPress = {
                                                    pressed = true
                                                    tryAwaitRelease()
                                                    pressed = false
                                                }
                                            )
                                        }
                                        .drawBehind {
                                            val shapePath = morph.toPath(
                                                progress = morphProgress,
                                                path = path
                                            )

                                            val bounds = shapePath.getBounds()

                                            val scale = min(
                                                size.width / bounds.width,
                                                size.height / bounds.height
                                            ) * 0.9f

                                            matrix.reset()
                                            matrix.scale(scale, scale)

                                            shapePath.transform(matrix)

                                            shapePath.translate(
                                                size.center -
                                                    shapePath.getBounds().center
                                            )

                                            rotate(rotation) {
                                                drawPath(
                                                    path = shapePath,
                                                    color = tertiary
                                                )
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Default.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(100.dp),
                                        tint = MaterialTheme.colorScheme.onTertiary
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

@Preview(uiMode = UI_MODE_NIGHT_YES, wallpaper = GREEN_DOMINATED_EXAMPLE, device = PIXEL_9_PRO_XL)
@Preview(uiMode = UI_MODE_NIGHT_NO, wallpaper = RED_DOMINATED_EXAMPLE, device = PIXEL_9_PRO_XL)
@Composable
fun ReleaseNotesScreenPreview() {
    LibrePodsTheme(
        m3eEnabled = false
    ) {
        ReleaseNotesScreen(
            updates = update0_3_1,
            releaseNotesShown = { }
        )
    }
}

// ai gen'd helper
fun createTopWavyRectPath(
    width: Float,
    height: Float,
    cornerRadius: Float,
    amplitude: Float,
    wavelength: Float
): Path {
    return Path().apply {
        moveTo(cornerRadius, 0f)

        var x = cornerRadius

        while (x < width - cornerRadius - wavelength) {
            quadraticTo(
                x + wavelength / 4f,
                -amplitude,
                x + wavelength / 2f,
                0f
            )

            quadraticTo(
                x + wavelength * 3f / 4f,
                amplitude,
                x + wavelength,
                0f
            )

            x += wavelength
        }

        arcTo(
            rect = Rect(
                left = width - 2 * cornerRadius,
                top = 0f,
                right = width,
                bottom = 2 * cornerRadius
            ),
            startAngleDegrees = -90f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )

        lineTo(width, height - cornerRadius)

        arcTo(
            rect = Rect(
                left = width - 2 * cornerRadius,
                top = height - 2 * cornerRadius,
                right = width,
                bottom = height
            ),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )

        lineTo(cornerRadius, height)

        arcTo(
            rect = Rect(
                left = 0f,
                top = height - 2 * cornerRadius,
                right = 2 * cornerRadius,
                bottom = height
            ),
            startAngleDegrees = 90f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )

        lineTo(0f, cornerRadius)

        arcTo(
            rect = Rect(
                left = 0f,
                top = 0f,
                right = 2 * cornerRadius,
                bottom = 2 * cornerRadius
            ),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )

        close()
    }
}
