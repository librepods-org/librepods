package me.kavishdevar.librepods.presentation.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallControlScreen(viewModel: AirPodsViewModel, action: String, onCallControlValueChanged: (Boolean) -> Unit) {
    val state by viewModel.uiState.collectAsState()

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    val scrollState = rememberScrollState()

    val bytes =
        state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG]?.take(
            2
        )?.toByteArray() ?: byteArrayOf(0x00, 0x00)
    val flipped = try {
        bytes[1] == 0x02.toByte()
    } catch (e: Exception) {
        false
    }

    val pressOnceText = stringResource(R.string.press_once)
    val pressTwiceText = stringResource(R.string.press_twice)
    val muteUnmuteText = stringResource(R.string.mute_unmute)

    var singlePressAction by remember { mutableStateOf(if ((action == muteUnmuteText) == !flipped) pressOnceText else pressTwiceText) }

    val pressOnceIsAction by remember { derivedStateOf { singlePressAction == pressOnceText } }
    val flippedValue = action != muteUnmuteText

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
                name = pressOnceText,
                selected = pressOnceIsAction,
                onClick = {
                    singlePressAction = pressOnceText
                    onCallControlValueChanged(flippedValue)
                }
            )

            StyledListItem(
                name = pressTwiceText,
                selected = !pressOnceIsAction,
                onClick = {
                    singlePressAction = pressTwiceText
                    onCallControlValueChanged(!flippedValue)
                }
            )
        }

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}
