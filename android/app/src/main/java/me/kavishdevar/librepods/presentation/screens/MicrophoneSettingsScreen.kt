package me.kavishdevar.librepods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel

@Composable
fun MicrophoneSettingsRoute(
    viewModel: AirPodsViewModel
) {
    val state by viewModel.uiState.collectAsState()

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    val id = AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE

    Box (
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        MicrophoneSettingsScreen(
            selectedMode = state.controlStates[id]?.getOrNull(0)?.toInt() ?: 0,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
            onMicrophoneSettingsChanged = {
                viewModel.setControlCommandInt(id, it)
            }
        )
    }
}

@Composable
fun MicrophoneSettingsScreen(
    selectedMode: Int,
    topPadding: Dp = 16.dp,
    bottomPadding: Dp = 16.dp,
    onMicrophoneSettingsChanged: (Int) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(scrollState)
            .padding(top = 8.dp)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        StyledList {
            StyledListItem(
                name = stringResource(R.string.microphone_automatic),
                selected = selectedMode == 0,
                onClick = { onMicrophoneSettingsChanged(0) }
            )

            StyledListItem(
                name = stringResource(R.string.microphone_always_right),
                selected = selectedMode == 1,
                onClick = { onMicrophoneSettingsChanged(1) }
            )

            StyledListItem(
                name = stringResource(R.string.microphone_always_left),
                selected = selectedMode == 2,
                onClick = { onMicrophoneSettingsChanged(2) }
            )
        }

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}
