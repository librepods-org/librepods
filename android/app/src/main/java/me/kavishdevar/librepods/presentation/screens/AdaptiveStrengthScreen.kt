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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.components.StyledSlider
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel

@Composable
fun AdaptiveStrengthScreen(viewModel: AirPodsViewModel, navController: NavController) {
    val state by viewModel.uiState.collectAsState()
    val backdrop = rememberLayerBackdrop()

    StyledScaffold(
        title = stringResource(R.string.customize_adaptive_audio)
    ) { spacerHeight ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
            val sliderValue = remember {
                mutableFloatStateOf(100f - (state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH]?.getOrNull(0)?.toFloat() ?: 50f))
            }
            var job by remember { mutableStateOf<Job?>(null) }
            val scope = rememberCoroutineScope()
            StyledSlider(
                label = stringResource(R.string.customize_adaptive_audio),
                value = sliderValue.floatValue,
                onValueChange = {
                    sliderValue.floatValue = it
                    job?.cancel()
                    job = scope.launch {
                        delay(150)
                        viewModel.setControlCommandValue(
                            AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH,
                            byteArrayOf((100 - it).toInt().toByte())
                        )
                    }
                },
                valueRange = 0f..100f,
                snapPoints = listOf(0f, 50f, 100f),
                startIcon = "􀊥",
                endIcon = "􀊩",
                independent = true,
                description = stringResource(R.string.adaptive_audio_description),
                enabled = state.isPremium
            )
        }
    }
}
