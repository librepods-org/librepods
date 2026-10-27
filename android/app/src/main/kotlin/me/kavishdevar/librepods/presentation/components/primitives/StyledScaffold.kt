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

package me.kavishdevar.librepods.presentation.components.primitives

import android.annotation.SuppressLint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.RoundedCorner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.app.ColorStyle
import me.kavishdevar.librepods.data.app.DesignSystem
import me.kavishdevar.librepods.presentation.design.LocalDesign
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.navigation.LocalIsCurrentEntry
import me.kavishdevar.librepods.presentation.navigation.LocalSharedTransitionScope
import me.kavishdevar.librepods.presentation.navigation.LocalTransitionProgress

@Composable
fun StyledScaffold(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    title: String,
    navigateBack: (() -> Unit)?,
    actionButtons: List<@Composable (hazeInput: HazeInput) -> Unit> = emptyList(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable BoxScope.(topPadding: Dp, bottomPadding: Dp) -> Unit
) {
    val design = LocalDesign.current

    val hazeState = rememberHazeState()

    val windowInsets = LocalView.current.rootWindowInsets
    val density = LocalDensity.current

    val topRadius = with(density) {
        windowInsets
            ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
            ?.radius
            ?.toDp() ?: 0.dp
    }
    val bottomRadius = with(density) {
        windowInsets
            ?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
            ?.radius
            ?.toDp() ?: 0.dp
    }

    val screenCutoutShape = RoundedCornerShape(topStart = topRadius, topEnd = topRadius, bottomStart = bottomRadius, bottomEnd = bottomRadius)

    when (design.system) {
        DesignSystem.Material -> {
            Scaffold(
                containerColor = if (design.colorStyle == ColorStyle.Custom) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                modifier = Modifier
                    .then(
                        if (MaterialTheme.colorScheme.surface.luminance() > 0.5) Modifier.shadow(
                            elevation = 36.dp,
                            shape = screenCutoutShape,
                            ambientColor = Color.Black,
                            spotColor = Color.Black
                        ) else Modifier.shadow(
                            elevation = 36.dp,
                            shape = screenCutoutShape,
                            ambientColor = Color.Gray,
                            spotColor = Color.Gray
                        )
                    )
                    .clip(screenCutoutShape),
                topBar = {
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it })
                    ) {
                        TopAppBar(
                            navigationIcon = {
                                if (navigateBack != null) {
                                    Row {
                                        Spacer(modifier = Modifier.width(12.dp))
                                        FilledTonalIconButton(
                                            onClick = navigateBack,
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .size(
                                                    IconButtonDefaults.mediumContainerSize(
                                                        IconButtonDefaults.IconButtonWidthOption.Narrow
                                                    )
                                                ),
                                            shape = IconButtonDefaults.mediumRoundShape
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Default.ArrowBack,
                                                contentDescription = "",
                                                modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                                            )
                                        }
                                    }
                                }
                            },
                            title = {
                                Crossfade(targetState = title) {
                                    Text(
                                        text = it,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = if (navigateBack != null) 8.dp else 12.dp, end = 12.dp),
                                        style = MaterialTheme.typography.titleLargeEmphasized
                                    )
                                }
                            },
                            actions = {
                                actionButtons.forEach { actionButton ->
                                    actionButton(HazeInput.Sources(hazeState))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                            },
                            modifier = if (design.blurTopBarInMaterial) Modifier.hazeBlur(
                                input = HazeInput.Sources(hazeState),
                            ) else Modifier,
                            colors = TopAppBarDefaults.topAppBarColors().copy(containerColor = if (design.blurTopBarInMaterial) Color.Transparent else if (design.colorStyle == ColorStyle.Custom) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer)
                        )
                    }
                },
            ) { paddingValues ->
                Box(
                    modifier = modifier
                        .then(
                            if (visible) Modifier.padding(
                                start = paddingValues.calculateStartPadding(
                                    LocalLayoutDirection.current
                                ),
                                end = paddingValues.calculateEndPadding(LocalLayoutDirection.current)
                            ) else Modifier
                        )
                        .fillMaxSize()
                        .hazeSource(hazeState)
                        .clip(screenCutoutShape)
                ) {
                    content(paddingValues.calculateTopPadding(), paddingValues.calculateBottomPadding())
                }
            }
        }
        DesignSystem.Apple -> {
            Scaffold(
                containerColor = if (design.colorStyle == ColorStyle.Custom) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                modifier = Modifier
                    .then(
                        if (MaterialTheme.colorScheme.surface.luminance() > 0.5) Modifier.shadow(
                            elevation = 36.dp,
                            shape = screenCutoutShape,
                            ambientColor = Color.Black,
                            spotColor = Color.Black
                        ) else Modifier.shadow(
                            elevation = 36.dp,
                            shape = screenCutoutShape,
                            ambientColor = Color.Gray,
                            spotColor = Color.Gray
                        )
                    )
                    .clip(screenCutoutShape)
            ) { paddingValues ->
                val topPadding = paddingValues.calculateTopPadding()
                val bottomPadding = paddingValues.calculateBottomPadding()
                val startPadding = paddingValues.calculateLeftPadding(LocalLayoutDirection.current)
                val endPadding = paddingValues.calculateRightPadding(LocalLayoutDirection.current)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(screenCutoutShape)
                        .padding(start = startPadding, end = endPadding)
                ) {
                    val density = LocalDensity.current
                    val screenWidthPx = with(density) {
                        LocalWindowInfo.current.containerDpSize.width.toPx()
                    }

                    val isCurrentEntry = LocalIsCurrentEntry.current
                    val transitionProgress = LocalTransitionProgress.current
                    var sharedTransitionScope = LocalSharedTransitionScope.current

                    val showBackButton = if (transitionProgress == 0f) navigateBack != null else !isCurrentEntry

                    @SuppressLint("UnusedSharedTransitionModifierParameter")
                    if (sharedTransitionScope == null) {
                        SharedTransitionScope {
                            sharedTransitionScope = this
                        }
                    }

                    sharedTransitionScope = sharedTransitionScope!!


                    with(sharedTransitionScope) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .renderInSharedTransitionScopeOverlay(
                                    zIndexInOverlay = 1f,
                                    renderInOverlay = { transitionProgress != 0f }
                                )
                                .hazeSource(hazeState)
                        )
                    }

                    if (showBackButton) {
                        with(sharedTransitionScope) {
                            Box(
                                modifier = Modifier
                                    .zIndex(3f)
                                    .padding(top = topPadding, start = 8.dp)
                                    .align(Alignment.TopStart)
                                    .pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                awaitFirstDown(requireUnconsumed = false)

                                                do {
                                                    val event = awaitPointerEvent()

                                                    event.changes.forEach { change ->
                                                        change.consume()
                                                    }
                                                } while (event.changes.any { it.pressed })
                                            }
                                        }
                                    }
                                    .renderInSharedTransitionScopeOverlay(
                                        zIndexInOverlay = 3f,
                                        renderInOverlay = {
                                            !isCurrentEntry && transitionProgress != 0f
                                        }
                                    )
                                    .graphicsLayer { // AI generated
                                        if (!isCurrentEntry && transitionProgress < 0f) {
                                            val progress = (-transitionProgress).coerceIn(0f, 1f)

                                            translationX = (screenWidthPx / 3f) * (1f - progress)

                                            if (navigateBack == null) {
                                                val eased = progress * progress * (3f - 2f * progress)

                                                val scale = 1f - 0.18f * eased
                                                scaleX = scale
                                                scaleY = scale

                                                alpha = 1f - 0.28f * eased

                                                val blur = 8f * progress
                                                renderEffect = RenderEffect.createBlurEffect(
                                                    blur,
                                                    blur,
                                                    Shader.TileMode.DECAL
                                                ).asComposeRenderEffect()
                                            }
                                        }
                                    }
                            ) {
                                StyledIconButton(
                                    onClick = { navigateBack?.invoke() },
                                    hazeInput = HazeInput.Sources(hazeState)
                                ) {
                                    Icon(
                                        imageVector = LocalIcons.current.ArrowBack,
                                        contentDescription = stringResource(R.string.back),
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onBackground,
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn() + scaleIn(
                            initialScale = 0f,
                            animationSpec = tween()
                        ),
                        exit = fadeOut() + scaleOut(
                            targetScale = 0.5f,
                            animationSpec = tween(100)
                        ),
                        modifier = Modifier
                            .zIndex(2f)
                            .height(64.dp + topPadding)
                            .fillMaxWidth()
                    ){
                        Box(
                            modifier = Modifier
                                .hazeSource(state = hazeState, zIndex = 1f, key = "titleBar")
                                .hazeBlur(
                                    input = HazeInput.Sources(state = hazeState)
                                )
                        ) {

                            Column(modifier = Modifier.fillMaxSize()) {
                                Spacer(modifier = Modifier.height(topPadding + 12.dp))
                                Crossfade(targetState = title) {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = visible && actionButtons.isNotEmpty(),
                        enter = fadeIn() + scaleIn(
                            initialScale = 0f,
                            animationSpec = tween()
                        ),
                        exit = fadeOut() + scaleOut(
                            targetScale = 0.5f,
                            animationSpec = tween(100)
                        ),
                        modifier = Modifier
                            .zIndex(3f)
                            .padding(top = topPadding, end = 8.dp)
                            .align(Alignment.TopEnd)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitFirstDown(requireUnconsumed = false)

                                        do {
                                            val event = awaitPointerEvent()

                                            event.changes.forEach { change ->
                                                change.consume()
                                            }
                                        } while (event.changes.any { it.pressed })
                                    }
                                }
                            }
                    ) {
                        Row{
                            actionButtons.forEach { actionButton ->
                                actionButton(HazeInput.Sources(hazeState))
                            }
                        }
                    }

                    Box(
                        modifier = modifier
                            .hazeSource(hazeState)
                            .fillMaxSize()
                    ) {
                        content(topPadding + 64.dp, bottomPadding)
                    }
                }
            }
        }
    }
}
