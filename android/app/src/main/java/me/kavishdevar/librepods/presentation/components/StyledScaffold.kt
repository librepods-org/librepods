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

package me.kavishdevar.librepods.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyledScaffold(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    title: String,
    showBackButton: Boolean = false,
    onNavigateBack: () -> Unit = {},
    actionButtons: List<@Composable (backdrop: LayerBackdrop) -> Unit> = emptyList(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable () -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
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
                                if (showBackButton) {
                                    Row {
                                        Spacer(modifier = Modifier.width(12.dp))
                                        FilledTonalIconButton(
                                            onClick = onNavigateBack,
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow)),
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
                                        modifier = Modifier.padding(start = if (showBackButton) 8.dp else 12.dp, end = 12.dp),
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
                        .then(if (visible) Modifier.padding(paddingValues) else Modifier)
                        .fillMaxSize()
                        .hazeSource(hazeState)
                ) {
                    content()
                }
            }
        }
        DesignSystem.Apple -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                modifier = Modifier
                    .then(
                        if (!isDarkTheme) Modifier.shadow(
                            elevation = 36.dp,
                            shape = RoundedCornerShape(52.dp),
                            ambientColor = Color.Black,
                            spotColor = Color.Black
                        ) else Modifier
                    )
                    .clip(RoundedCornerShape(52.dp))
            ) { paddingValues ->
                val topPadding = paddingValues.calculateTopPadding()
                val startPadding = paddingValues.calculateLeftPadding(LocalLayoutDirection.current)
                val endPadding = paddingValues.calculateRightPadding(LocalLayoutDirection.current)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = startPadding, end = endPadding)
                ) {
                    val backdrop = rememberLayerBackdrop()
                    val bgColor = MaterialTheme.colorScheme.surfaceContainer
                    AnimatedVisibility(
                        visible = showBackButton,
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
                            .padding(top = topPadding, start = 8.dp)
                            .align(Alignment.TopStart)
                    ) {
                        StyledIconButton(
                            onClick = onNavigateBack,
                            icon = "􀯶",
                            backdrop = backdrop
                        )
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
                        Box(
                            modifier = Modifier.hazeEffect(
                                state = hazeState,
                            ) {
                                backgroundColor = bgColor
                                tints = listOf(
                                    HazeTint(
                                        if (isDarkTheme) Color.Black.copy(0.55f) else Color(
                                            0xFFF2F2F7
                                        ).copy(alpha = 0.85f)
                                    )
                                )
                                blurRadius = 6.dp
                            }
                        ) {

                            Column(modifier = Modifier.fillMaxSize()) {
                                Spacer(modifier = Modifier.height(topPadding + 12.dp))
                                Crossfade(targetState = title) {
                                    Text(
                                        text = it,
                                        style = TextStyle(
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isDarkTheme) Color.White else Color.Black,
                                            fontFamily = FontFamily(Font(R.font.sf_pro))
                                        ),
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
                        content()
                    }
                }
            }
        }
    }
}
