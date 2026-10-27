package me.kavishdevar.librepods.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel

@Composable
fun AppAccessibilitySettingsRoute(
    viewModel: AppSettingsViewModel,
    navigateBack: (() -> Unit)?
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings
    val accessibilitySettings = settings.accessibilitySettings
    AppAccessibilitySettingsScreen(
        navigateBack = navigateBack,
        differentiateWithoutColor = accessibilitySettings.differentiateWithoutColor,
        onDifferentiateWithoutColorChanged = { differentiateWithoutColor ->
            viewModel.updateSettings {
                it.copy(accessibilitySettings = accessibilitySettings.copy(differentiateWithoutColor = differentiateWithoutColor))
            }
        },
        reduceTransparency = accessibilitySettings.reduceTransparency,
        onReduceTransparencyChanged = { reduceTransparency ->
            viewModel.updateSettings {
                it.copy(accessibilitySettings = accessibilitySettings.copy(reduceTransparency = reduceTransparency))
            }
        },
        increaseContrast = accessibilitySettings.increaseContrast,
        onIncreaseContrastChanged = { increaseContrast ->
            viewModel.updateSettings {
                it.copy(accessibilitySettings = accessibilitySettings.copy(increaseContrast = increaseContrast))
            }
        },
        showBorders = accessibilitySettings.showBorders,
        onShowBordersChanged = { showBorders ->
            viewModel.updateSettings {
                it.copy(accessibilitySettings = accessibilitySettings.copy(showBorders = showBorders))
            }
        }
    )
}

@Composable
fun AppAccessibilitySettingsScreen(
    navigateBack: (() -> Unit)? = null,
    differentiateWithoutColor: Boolean,
    onDifferentiateWithoutColorChanged: (Boolean) -> Unit,
    reduceTransparency: Boolean,
    onReduceTransparencyChanged: (Boolean) -> Unit,
    increaseContrast: Boolean,
    onIncreaseContrastChanged: (Boolean) -> Unit,
    showBorders: Boolean,
    onShowBordersChanged: (Boolean) -> Unit
) {
    val scrollState = rememberScrollState()

    StyledScaffold(
        title = stringResource(R.string.accessibility),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            StyledList {
                styledToggle(
                    label = stringResource(R.string.differentiate_without_color),
                    checked = differentiateWithoutColor,
                    onCheckedChange = onDifferentiateWithoutColorChanged
                )
            }

            StyledList(title = stringResource(R.string.glass_effects)) {
                styledToggle(
                    label = stringResource(R.string.reduce_transparency),
                    checked = reduceTransparency,
                    onCheckedChange = onReduceTransparencyChanged
                )
                styledToggle(
                    label = stringResource(R.string.increase_contrast),
                    checked = increaseContrast,
                    onCheckedChange = onIncreaseContrastChanged
                )
                styledToggle(
                    label = stringResource(R.string.show_borders),
                    checked = showBorders,
                    onCheckedChange = onShowBordersChanged
                )
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}
