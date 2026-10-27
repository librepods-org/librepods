package me.kavishdevar.librepods.presentation.screens.settings.island

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.app.Design
import me.kavishdevar.librepods.data.app.DesignSystem
import me.kavishdevar.librepods.data.app.FontFamilyOption
import me.kavishdevar.librepods.data.app.FontSettings
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.presentation.components.primitives.MaterialButtonStyle
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.overlays.IslandState
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel

@Composable
fun IslandSettingsRoute(
    viewModel: AppSettingsViewModel,
    navigateBack: (() -> Unit)?,
    navigateToExpandedSizeAndPosition: () -> Unit,
    navigateToCompactSizeAndPosition: () -> Unit,
    navigateToExpandedCustomize: () -> Unit,
    navigateToCompactCustomize: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings

    val context = LocalContext.current
    val islandWindow = (context.applicationContext as LibrePodsApplication).islandWindow

    val previousState = islandWindow.islandState

    DisposableEffect(Unit) {
        onDispose {
            if (previousState != islandWindow.islandState) {
                islandWindow.changeState(previousState)
            }
        }
    }

    LaunchedEffect(settings.islandSettings.enableExpanded) {
        islandWindow.show(
            forcedState = if (settings.islandSettings.enableExpanded) IslandState.EXPANDED else IslandState.COMPACT,
            keepOpen = true
        )
    }

    IslandSettingsScreen(
        navigateBack = navigateBack,
        islandSettings = settings.islandSettings,
        navigateToExpandedSizeAndPosition = navigateToExpandedSizeAndPosition,
        navigateToCompactSizeAndPosition = navigateToCompactSizeAndPosition,
        navigateToExpandedCustomize = navigateToExpandedCustomize,
        navigateToCompactCustomize = navigateToCompactCustomize,
        navigateToAndroidAccessibility = {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        },
        accessibilityServiceAvailable = uiState.accessibilityServiceAvailable,
        updateIslandSettings = { islandSettings ->
            viewModel.updateSettings {
                it.copy(
                    islandSettings = islandSettings
                )
            }
        },
        resetAllIslandSettings = {
            viewModel.updateSettings {
                it.copy(
                    islandSettings = IslandSettings()
                )
            }
        },
        showIsland = {
            islandWindow.show(
                forcedState = if (settings.islandSettings.enableExpanded) IslandState.EXPANDED else IslandState.COMPACT,
                keepOpen = true
            )
        },
        hideIsland = {
            islandWindow.changeState(previousState)
        }
    )
}

@Composable
fun IslandSettingsScreen(
    navigateBack: (() -> Unit)? = null,
    navigateToExpandedSizeAndPosition: () -> Unit,
    navigateToCompactSizeAndPosition: () -> Unit,
    navigateToExpandedCustomize: () -> Unit,
    navigateToCompactCustomize: () -> Unit,
    islandSettings: IslandSettings,
    navigateToAndroidAccessibility: () -> Unit,
    accessibilityServiceAvailable: Boolean,
    updateIslandSettings: (IslandSettings) -> Unit,
    resetAllIslandSettings: () -> Unit,
    showIsland: () -> Unit,
    hideIsland: () -> Unit
) {
    val scrollState = rememberScrollState()

    StyledScaffold(
        title = stringResource(R.string.island_settings),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StyledList(
                    title = stringResource(R.string.default_style)
                ) {
                    styledListItem(
                        contentText = stringResource(R.string.expanded),
                        selected = islandSettings.enableExpanded,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    enableExpanded = true
                                )
                            )
                        }
                    )
                    styledListItem(
                        contentText = stringResource(R.string.compact),
                        selected = !islandSettings.enableExpanded,
                        onClick = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    enableExpanded = false
                                )
                            )
                        }
                    )
                }

                StyledList {
                    styledToggle(
                        label = stringResource(R.string.keep_compact_island_visible),
                        description = stringResource(R.string.island_accessibility_service_needed),
                        checked = islandSettings.keepCompactVisible,
                        enabled = accessibilityServiceAvailable,
                        onCheckedChange = {
                            updateIslandSettings(
                                islandSettings.copy(
                                    keepCompactVisible = it
                                )
                            )
                        }
                    )
                    styledListItem(
                        contentText = stringResource(R.string.accessibility_service),
                        supportingText = if (accessibilityServiceAvailable) stringResource(R.string.available) else stringResource(R.string.not_available),
                        onClick = navigateToAndroidAccessibility,
                    )
                }

                AnimatedVisibility(
                    visible = islandSettings.enableExpanded
                ) {
                    StyledList(
                        title = stringResource(R.string.expanded)
                    ) {
                        styledToggle(
                            label = stringResource(R.string.listening_mode_controls),
                            checked = islandSettings.showListeningModeControl,
                            onCheckedChange = {
                                updateIslandSettings(
                                    islandSettings.copy(
                                        showListeningModeControl = it
                                    )
                                )
                            }
                        )
                        styledListItem(
                            contentText = stringResource(R.string.size_and_position),
                            onClick = navigateToExpandedSizeAndPosition
                        )
                        styledListItem(
                            contentText = stringResource(R.string.customize),
                            onClick = navigateToExpandedCustomize
                        )
                    }
                }

                AnimatedVisibility(
                    visible = !islandSettings.enableExpanded || islandSettings.keepCompactVisible
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        StyledList(
                            title = stringResource(R.string.compact)
                        ) {
                            styledListItem(
                                contentText = stringResource(R.string.size_and_position),
                                onClick = navigateToCompactSizeAndPosition
                            )
                            styledListItem(
                                contentText = stringResource(R.string.customize),
                                onClick = navigateToCompactCustomize
                            )
                        }
                    }
                }

                StyledButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { resetAllIslandSettings() },
                    materialButtonStyle = MaterialButtonStyle.Outlined
                ) {
                    Text(
                        text = stringResource(R.string.reset_island_settings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StyledButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        hideIsland()
                    },
                    materialButtonStyle = MaterialButtonStyle.Outlined
                ) {
                    Text(
                        text = stringResource(R.string.hide_island),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                StyledButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showIsland()
                    },
                    materialButtonStyle = MaterialButtonStyle.Outlined
                ) {
                    Text(
                        text = stringResource(R.string.show_island),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

@Preview
@Composable
fun IslandSettingsScreenPreview(){
    LibrePodsTheme(
//        designSystem = DesignSystem.Apple,
//        fontSettings = FontSettings(fontFamilyOption = FontFamilyOption.Inter),
        design = Design(
            system = DesignSystem.Material,
        ),
        fontSettings = FontSettings(fontFamilyOption = FontFamilyOption.RobotoFlexExpressive),
        darkTheme = true
    ) {
        val islandSettings = remember { mutableStateOf(IslandSettings()) }

        IslandSettingsScreen(
            navigateToExpandedSizeAndPosition = {},
            navigateToCompactSizeAndPosition = {},
            navigateToExpandedCustomize = {},
            navigateToCompactCustomize = {},
            islandSettings = islandSettings.value,
            navigateToAndroidAccessibility = {},
            accessibilityServiceAvailable = false,
            updateIslandSettings = { islandSettings.value = it },
            resetAllIslandSettings = {},
            showIsland = {},
            hideIsland = {}
        )
    }
}
