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

package me.kavishdevar.librepods.presentation.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel

@Composable
fun HearingProtectionScreen(viewModel: AirPodsViewModel, navController: NavController) {
    val backdrop = rememberLayerBackdrop()
    val state by viewModel.uiState.collectAsState()
    StyledScaffold(
        title = stringResource(R.string.hearing_protection),
    ) { spacerHeight ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(spacerHeight))
            if (!state.isPremium) {
                StyledButton(
                    onClick = {
                        navController.navigate("purchase_screen")
                    },
                    backdrop = rememberLayerBackdrop(),
                    modifier = Modifier.fillMaxWidth(),
                    maxScale = 0.05f,
                    surfaceColor = if (isSystemInDarkTheme()) Color(0xFF916100) else Color(0xFFE59900)
                ) {
                    Text(
                        stringResource(R.string.unlock_advanced_features),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            color = Color.White
                        ),
                    )
                }
            }
            if (state.vendorIdHook) {
                StyledToggle(
                    title = stringResource(R.string.environmental_noise),
                    label = stringResource(R.string.loud_sound_reduction),
                    description = stringResource(R.string.loud_sound_reduction_description),
                    checked = state.loudSoundReductionEnabled,
                    onCheckedChange = {
                        viewModel.setATTCharacteristicValue(
                            ATTHandles.LOUD_SOUND_REDUCTION,
                            byteArrayOf(if (it) 1.toByte() else 0.toByte())
                        )
                    },
                    enabled = state.isPremium
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
            StyledToggle(
                title = stringResource(R.string.workspace_use),
                label = stringResource(R.string.ppe),
                description = stringResource(R.string.workspace_use_description),
                checked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.PPE_TOGGLE_CONFIG]?.getOrNull(
                    0
                )?.toInt() == 1,
                onCheckedChange = {
                    viewModel.setControlCommandBoolean(
                        AACPManager.Companion.ControlCommandIdentifiers.PPE_TOGGLE_CONFIG, it
                    )
                },
                enabled = state.isPremium
            )
        }
    }
}
