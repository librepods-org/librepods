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

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.navigation.LocalIsCurrentEntry
import me.kavishdevar.librepods.presentation.navigation.LocalSharedTransitionScope
import me.kavishdevar.librepods.presentation.navigation.LocalTransitionProgress
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.R
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar as MiuixSmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyledScaffold(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    title: String,
    navigateBack: (() -> Unit)?,
    actionButtons: List<@Composable (backdrop: LayerBackdrop) -> Unit> = emptyList(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (topPadding: Dp, bottomPadding: Dp) -> Unit
) {
    val hazeState = rememberHazeState(blurEnabled = true)

    when (LocalDesignSystem.current) {
        DesignSystem.Material -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                            },
                            actions = {
                                actionButtons.forEach { actionButton ->
                                    actionButton(rememberLayerBackdrop())
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
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
                ) {
                    content(paddingValues.calculateTopPadding(), paddingValues.calculateBottomPadding())
                }
            }
        }
        DesignSystem.Miuix -> {
            val miuixSurface = MiuixTheme.colorScheme.surface
            MiuixScaffold(
                // In Miuix it is surface that is the page canvas (pure black in dark mode);
                // background is the grey a card is filled with. This layer stays behind even
                // when the top bar is hidden, so the wrong one greys out the whole page.
                containerColor = MiuixTheme.colorScheme.surface,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    // Deliberately not wrapped in AnimatedVisibility: a top bar sliding in
                    // from above makes a pushed screen look assembled in pieces, where MIUI
                    // brings the whole page in with the navigation transition and the bar is
                    // already complete on the first frame.
                    if (visible) {
                        // This has to be SmallTopAppBar. The Miuix TopAppBar carries a large
                        // collapsing title driven by a scrollBehavior, and content here is a
                        // generic lambda with nothing to hang nestedScroll on, so the large
                        // title would stay permanently expanded and leave a gap on every page.
                        MiuixSmallTopAppBar(
                            title = title,
                            // Transparent plus a haze effect makes the bar frosted glass over
                            // the content scrolling under it, the way MIUI's own bars read.
                            color = Color.Transparent,
                            modifier = Modifier.hazeEffect(state = hazeState) {
                                backgroundColor = miuixSurface
                                tints = listOf(HazeTint(miuixSurface.copy(alpha = 0.8f)))
                                blurRadius = 6.dp
                            },
                            navigationIcon = {
                                if (navigateBack != null) {
                                    MiuixIconButton(onClick = navigateBack) {
                                        MiuixIcon(
                                            imageVector = MiuixIcons.Back,
                                            contentDescription = stringResource(R.string.back),
                                            tint = MiuixTheme.colorScheme.onBackground
                                        )
                                    }
                                }
                            },
                            actions = {
                                actionButtons.forEach { actionButton ->
                                    actionButton(rememberLayerBackdrop())
                                }
                            }
                        )
                    }
                }
            ) { paddingValues ->
                Box(
                    modifier = modifier
                        .then(
                            if (visible) Modifier.padding(
                                start = paddingValues.calculateStartPadding(LocalLayoutDirection.current),
                                end = paddingValues.calculateEndPadding(LocalLayoutDirection.current)
                            ) else Modifier
                        )
                        .fillMaxSize()
                        .hazeSource(hazeState)
                ) {
                    content(
                        paddingValues.calculateTopPadding(),
                        paddingValues.calculateBottomPadding()
                    )
                }
            }
        }
        DesignSystem.Apple -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                modifier = Modifier
                    .then(
                        if (MaterialTheme.colorScheme.surface.luminance() > 0.5) Modifier.shadow(
                            elevation = 36.dp,
                            shape = RoundedCornerShape(52.dp),
                            ambientColor = Color.Black,
                            spotColor = Color.Black
                        ) else Modifier
                    )
            ) { paddingValues ->
                val topPadding = paddingValues.calculateTopPadding()
                val bottomPadding = paddingValues.calculateBottomPadding()
                val startPadding = paddingValues.calculateLeftPadding(LocalLayoutDirection.current)
                val endPadding = paddingValues.calculateRightPadding(LocalLayoutDirection.current)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(52.dp))
                        .padding(start = startPadding, end = endPadding)
                ) {
                    val backdrop = rememberLayerBackdrop()
                    val bgColor = MaterialTheme.colorScheme.surfaceContainer

//                    val density = LocalDensity.current
//                    val screenWidthPx = with(density) {
//                        LocalWindowInfo.current.containerDpSize.width.toPx()
//                    }
                    val isCurrentEntry = LocalIsCurrentEntry.current
                    val transitionProgress = LocalTransitionProgress.current
                    val sharedTransitionScope = LocalSharedTransitionScope.current

                    val showBackButton = if (transitionProgress == 0f) navigateBack != null else !isCurrentEntry

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
                                        if (!isCurrentEntry && navigateBack == null && transitionProgress < 0f) {
                                            val progress = (-transitionProgress).coerceIn(0f, 1f)

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
                            ) {
                                StyledIconButton(
                                    onClick = { navigateBack?.invoke() },
                                    backdrop = backdrop // i know, this doesn't capture what's actually beneath it. but it's going to matter just in the transition.
                                ) {
                                    Icon(
                                        imageVector = LocalIcons.current.ArrowBack,
                                        contentDescription = "Back",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onBackground
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
                            .layerBackdrop(backdrop)
                    ){
                        val scrimColor = MaterialTheme.colorScheme.scrim

                        Box(
                            modifier = Modifier.hazeEffect(
                                state = hazeState,
                            ) {
                                backgroundColor = bgColor
                                tints = listOf(
                                    HazeTint(scrimColor)
                                )
                                blurRadius = 6.dp
                            }
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
                                actionButton(backdrop)
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
