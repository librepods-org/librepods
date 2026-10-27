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

package me.kavishdevar.librepods.presentation.screens.apple

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.att.types.TransparencySettings
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.sendTransparencySettings
import me.kavishdevar.librepods.data.app.ColorStyle
import me.kavishdevar.librepods.data.app.DesignSystem
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.design.LocalDesign
import me.kavishdevar.librepods.presentation.design.sectionHeader
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel

private const val TAG = "TransparencySettings"

@SuppressLint("DefaultLocale")
@Composable
fun TransparencySettingsScreen(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?,
) {
    val design = LocalDesign.current

    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val verticalScrollState = rememberScrollState()

    val trackColor = if (isDarkTheme) Color(0xFFB3B3B3) else Color(0xFF929491)
    val activeTrackColor = if (isDarkTheme) Color(0xFF007AFF) else Color(0xFF3C6DF5)
    val thumbColor = if (isDarkTheme) Color(0xFFFFFFFF) else Color(0xFFFFFFFF)

    val uiState by viewModel.uiState.collectAsState()

    val state = uiState.state

    StyledScaffold(
        title = stringResource(R.string.customize_transparency_mode),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(if (design.colorStyle == ColorStyle.Custom) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer)
                .verticalScroll(verticalScrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))
            val backgroundColor = if (design.colorStyle == ColorStyle.Custom) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer

            val enabled = rememberSaveable { mutableStateOf(false) }
            val amplificationSliderValue = rememberSaveable { mutableFloatStateOf(0.5f) }
            val balanceSliderValue = rememberSaveable { mutableFloatStateOf(0.5f) }
            val toneSliderValue = rememberSaveable { mutableFloatStateOf(0.5f) }
            val ambientNoiseReductionSliderValue = rememberSaveable { mutableFloatStateOf(0.0f) }
            val conversationBoostEnabled = rememberSaveable { mutableStateOf(false) }
            val eq = rememberSaveable(
                saver = Saver(
                    save = { it.value.toList() },
                    restore = { mutableStateOf(it.toFloatArray()) }
                )
            ) { mutableStateOf(FloatArray(8)) }

//        val phoneMediaEQ = rememberSaveable(
//            saver = Saver(
//                save = { it.value.toList() },
//                restore = { mutableStateOf(it.toFloatArray()) }
//            )
//        ) { mutableStateOf(FloatArray(8) { 0.5f }) }

            val initialized = rememberSaveable { mutableStateOf(false) }

            val transparencySettings = remember {
                mutableStateOf(
                    TransparencySettings(
                        enabled = enabled.value,
                        leftEQ = eq.value,
                        rightEQ = eq.value,
                        leftAmplification = amplificationSliderValue.floatValue + (0.5f - balanceSliderValue.floatValue) * amplificationSliderValue.floatValue * 2,
                        rightAmplification = amplificationSliderValue.floatValue + (balanceSliderValue.floatValue - 0.5f) * amplificationSliderValue.floatValue * 2,
                        leftTone = toneSliderValue.floatValue,
                        rightTone = toneSliderValue.floatValue,
                        leftConversationBoost = conversationBoostEnabled.value,
                        rightConversationBoost = conversationBoostEnabled.value,
                        leftAmbientNoiseReduction = ambientNoiseReductionSliderValue.floatValue,
                        rightAmbientNoiseReduction = ambientNoiseReductionSliderValue.floatValue,
                        netAmplification = amplificationSliderValue.floatValue,
                        balance = balanceSliderValue.floatValue
                    )
                )
            }

            LaunchedEffect(
                enabled.value,
                amplificationSliderValue.floatValue,
                balanceSliderValue.floatValue,
                toneSliderValue.floatValue,
                conversationBoostEnabled.value,
                ambientNoiseReductionSliderValue.floatValue,
                eq.value
            ) {
                if (!initialized.value) return@LaunchedEffect
                transparencySettings.value = TransparencySettings(
                    enabled = enabled.value,
                    leftEQ = eq.value,
                    rightEQ = eq.value,
                    leftAmplification = amplificationSliderValue.floatValue + if (balanceSliderValue.floatValue < 0) -balanceSliderValue.floatValue else 0f,
                    rightAmplification = amplificationSliderValue.floatValue + if (balanceSliderValue.floatValue > 0) balanceSliderValue.floatValue else 0f,
                    leftTone = toneSliderValue.floatValue,
                    rightTone = toneSliderValue.floatValue,
                    leftConversationBoost = conversationBoostEnabled.value,
                    rightConversationBoost = conversationBoostEnabled.value,
                    leftAmbientNoiseReduction = ambientNoiseReductionSliderValue.floatValue,
                    rightAmbientNoiseReduction = ambientNoiseReductionSliderValue.floatValue,
                    netAmplification = amplificationSliderValue.floatValue,
                    balance = balanceSliderValue.floatValue
                )
                Log.d("TransparencySettings", "Updated settings: ${transparencySettings.value}")
                sendTransparencySettings(viewModel::writeATTCharacteristic, transparencySettings.value)
            }

            LaunchedEffect(state.transparencyData) {
                val parsedSettings = parseTransparencySettingsResponse(data = state.transparencyData) ?: return@LaunchedEffect
                Log.d(TAG, "Initial transparency settings: $parsedSettings")
                enabled.value = parsedSettings.enabled
                amplificationSliderValue.floatValue = parsedSettings.netAmplification
                balanceSliderValue.floatValue = parsedSettings.balance
                toneSliderValue.floatValue = parsedSettings.leftTone
                ambientNoiseReductionSliderValue.floatValue =
                    parsedSettings.leftAmbientNoiseReduction
                conversationBoostEnabled.value = parsedSettings.leftConversationBoost
                if (!eq.value.contentEquals(parsedSettings.leftEQ)) {
                    eq.value = parsedSettings.leftEQ.copyOf()
                }
                initialized.value = true
            }

            if (uiState.vendorIdHook) {
                StyledToggle(
                    label = stringResource(R.string.transparency_mode),
                    checked = enabled.value,
                    description = stringResource(R.string.customize_transparency_mode_description),
                    onCheckedChange = { enabled.value = it }
                )
                Spacer(modifier = Modifier.height(4.dp))
                StyledSlider(
                    label = stringResource(R.string.amplification),
                    valueRange = -1f..1f,
                    value = amplificationSliderValue.floatValue,
                    onValueChange = {
                        amplificationSliderValue.floatValue = it
                    },
                    startImageVector = LocalIcons.current.SpeakerMin,
                    endImageVector = LocalIcons.current.SpeakerMax,
                    independent = true
                )

                StyledSlider(
                    label = stringResource(R.string.balance),
                    valueRange = -1f..1f,
                    value = balanceSliderValue.floatValue,
                    onValueChange = {
                        balanceSliderValue.floatValue = it
                    },
                    snapPoints = listOf(-1f, 0f, 1f),
                    startLabel = stringResource(R.string.left),
                    endLabel = stringResource(R.string.right),
                    independent = true,
                )

                StyledSlider(
                    label = stringResource(R.string.tone),
                    valueRange = -1f..1f,
                    value = toneSliderValue.floatValue,
                    onValueChange = {
                        toneSliderValue.floatValue = it
                    },
                    startLabel = stringResource(R.string.darker),
                    endLabel = stringResource(R.string.brighter),
                    independent = true,
                )

                StyledSlider(
                    label = stringResource(R.string.ambient_noise_reduction),
                    valueRange = 0f..1f,
                    value = ambientNoiseReductionSliderValue.floatValue,
                    onValueChange = {
                        ambientNoiseReductionSliderValue.floatValue = it
                    },
                    startLabel = stringResource(R.string.less),
                    endLabel = stringResource(R.string.more),
                    independent = true,
                )

                StyledToggle(
                    label = stringResource(R.string.conversation_boost),
                    checked = conversationBoostEnabled.value,
                    description = stringResource(R.string.conversation_boost_description),
                    onCheckedChange = { conversationBoostEnabled.value = it }
                )

                Text(
                    text = stringResource(R.string.equalizer),
                    style = MaterialTheme.typography.labelSmallEmphasized,
                    color = if (design.system == DesignSystem.Material) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.sectionHeader,
                    modifier = Modifier.padding(16.dp, bottom = 4.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor, RoundedCornerShape(28.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    for (i in 0 until 8) {
                        val eqValue = remember(eq.value[i]) { mutableFloatStateOf(eq.value[i]) }
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                        ) {
                            Text(
                                text = String.format("%.2f", eqValue.floatValue),
                                fontSize = 12.sp,
                                color = textColor,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )

                            val sliderState = rememberSliderState(
                                value = eqValue.floatValue,
                                trackRange = 0f..100f
                            )

                            Slider(
                                state = sliderState,
                                onValueChange = { newVal ->
                                    eqValue.floatValue = newVal
                                    val newEQ = eq.value.copyOf()
                                    newEQ[i] = eqValue.floatValue
                                    eq.value = newEQ
                                },
                                // weight, not fillMaxWidth(0.9f): the band label after the slider
                                // only got the leftover width and was clipped in wider scripts
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                                    .height(36.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = thumbColor,
                                    activeTrackColor = activeTrackColor,
                                    inactiveTrackColor = trackColor
                                ),
                                thumb = {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .shadow(4.dp, CircleShape)
                                            .background(thumbColor, CircleShape)
                                    )
                                },
                                track = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(12.dp),
                                        contentAlignment = Alignment.CenterStart
                                    )
                                    {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .background(trackColor, RoundedCornerShape(4.dp))
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(eqValue.floatValue / 100f)
                                                .height(4.dp)
                                                .background(
                                                    activeTrackColor,
                                                    RoundedCornerShape(4.dp)
                                                )
                                        )
                                    }
                                }
                            )

                            Text(
                                text = stringResource(R.string.band_label, i + 1),
                                fontSize = 12.sp,
                                color = textColor,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}
