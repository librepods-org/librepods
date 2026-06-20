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

@file:OptIn(ExperimentalStdlibApi::class, ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.presentation.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.presentation.components.ListItemOrientation
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import kotlin.experimental.and
import kotlin.io.encoding.ExperimentalEncodingApi

@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongPress(viewModel: AirPodsViewModel, name: String, navigateToPurchase: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    val modesByte = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS]?.get(0) ?: 0

    Log.d("PressAndHoldSettingsScreen", "Current modes state: ${modesByte.toString(2)}")
    Log.d("PressAndHoldSettingsScreen", "Off mode: ${(modesByte and 0x01) != 0.toByte()}")
    Log.d("PressAndHoldSettingsScreen", "Transparency mode: ${(modesByte and 0x04) != 0.toByte()}")
    Log.d("PressAndHoldSettingsScreen", "Noise Cancellation mode: ${(modesByte and 0x02) != 0.toByte()}")
    Log.d("PressAndHoldSettingsScreen", "Adaptive mode: ${(modesByte and 0x08) != 0.toByte()}")

    val longPressAction = if (name.lowercase() == "left") state.leftAction else state.rightAction

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    val scrollState = rememberScrollState()

    Column (
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
                name = stringResource(R.string.noise_control),
                selected = longPressAction == StemAction.CYCLE_NOISE_CONTROL_MODES,
                onClick = {
                    viewModel.setLongPressAction(
                        name,
                        StemAction.CYCLE_NOISE_CONTROL_MODES
                    )
                }
            )

            StyledListItem(
                name = stringResource(R.string.digital_assistant),
                selected = longPressAction == StemAction.DIGITAL_ASSISTANT,
                onClick = {
                    viewModel.setLongPressAction(
                        name,
                        StemAction.DIGITAL_ASSISTANT
                    )
                },
                enabled = state.isPremium
            )
        }

        if (!state.isPremium) {
            Spacer(modifier = Modifier.height(24.dp))
            StyledButton(
                onClick = navigateToPurchase,
                backdrop = rememberLayerBackdrop(),
                modifier = Modifier.fillMaxWidth(),
                maxScale = 0.05f,
                surfaceColor = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    stringResource(R.string.unlock_advanced_features),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (longPressAction == StemAction.CYCLE_NOISE_CONTROL_MODES) {
            Spacer(modifier = Modifier.height(32.dp))

            val currentByte = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS]?.get(0)?.toInt() ?: 0

            StyledList(
                title = stringResource(R.string.noise_control),
                description = stringResource(R.string.press_and_hold_noise_control_description)
            ) {
                if (state.offListeningMode) {
                    StyledListItem(
                        name = stringResource(R.string.off),
                        description = stringResource(R.string.listening_mode_off_description),
                        selected = (currentByte and 0x01) != 0,
                        onClick = {
                            viewModel.toggleListeningMode(0x01)
                        },
                        orientation = ListItemOrientation.Vertical,
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.noise_cancellation),
                                contentDescription = "Icon",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .height(42.dp)
                                    .wrapContentWidth()
                            )
                        }
                    )
                }

                StyledListItem(
                    name = stringResource(R.string.transparency),
                    description = stringResource(R.string.listening_mode_transparency_description),
                    selected = (currentByte and 0x04) != 0,
                    onClick = {
                        viewModel.toggleListeningMode(0x04)
                    },
                    orientation = ListItemOrientation.Vertical,
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.transparency),
                            contentDescription = "Icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .height(42.dp)
                                .wrapContentWidth()
                        )
                    }
                )

                StyledListItem(
                    name = stringResource(R.string.adaptive),
                    description = stringResource(R.string.listening_mode_adaptive_description),
                    selected = (currentByte and 0x08) != 0,
                    onClick = {
                        viewModel.toggleListeningMode(0x08)
                    },
                    orientation = ListItemOrientation.Vertical,
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.adaptive),
                            contentDescription = "Icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .height(42.dp)
                                .wrapContentWidth()
                        )
                    }
                )

                StyledListItem(
                    name = stringResource(R.string.noise_cancellation),
                    description = stringResource(R.string.listening_mode_noise_cancellation_description),
                    selected = (currentByte and 0x02) != 0,
                    onClick = {
                        viewModel.toggleListeningMode(0x02)
                    },
                    orientation = ListItemOrientation.Vertical,
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.noise_cancellation),
                            contentDescription = "Icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .height(42.dp)
                                .wrapContentWidth()
                        )
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(bottomPadding))
    }
}
