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

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.data.HearingAidSettings
import me.kavishdevar.librepods.data.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.data.sendHearingAidSettings
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsUiState
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.demoState

private const val TAG = "UpdateHearingTestScreen"

@Composable
fun UpdateHearingTestRoute(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        UpdateHearingTestScreen(
            state = state,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
            setATTCharacteristicValue = viewModel::setATTCharacteristicValue
        )
    }
}

@Composable
fun UpdateHearingTestScreen(
    state: AirPodsUiState,
    topPadding: Dp = 16.dp,
    bottomPadding: Dp = 16.dp,
    setATTCharacteristicValue: (ATTHandles, ByteArray) -> Unit
) {
    val verticalScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .verticalScroll(verticalScrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        Text(
            text = stringResource(R.string.hearing_test_value_instruction),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
        val tone = rememberSaveable { mutableFloatStateOf(0.5f) }
        val ambientNoiseReduction = rememberSaveable { mutableFloatStateOf(0.0f) }
        val ownVoiceAmplification = rememberSaveable { mutableFloatStateOf(0.5f) }
        val leftAmplification = rememberSaveable { mutableFloatStateOf(0.5f) }
        val rightAmplification = rememberSaveable { mutableFloatStateOf(0.5f) }
        val conversationBoostEnabled = rememberSaveable { mutableStateOf(false) }
        val leftEQ = rememberSaveable(
            saver = Saver(
                save = { it.value.toList() },
                restore = { mutableStateOf(it.toFloatArray()) }
            )
        ) {
            mutableStateOf(FloatArray(8))
        }
        val rightEQ = rememberSaveable(
            saver = Saver(
                save = { it.value.toList() },
                restore = { mutableStateOf(it.toFloatArray()) }
            )
        ) {
            mutableStateOf(FloatArray(8))
        }

        val debounceJob = remember { mutableStateOf<Job?>(null) }
        val initialized = rememberSaveable { mutableStateOf(false) }

        val hearingAidSettings = remember {
            mutableStateOf(
                HearingAidSettings(
                    leftEQ = leftEQ.value,
                    rightEQ = rightEQ.value,
                    leftAmplification = leftAmplification.floatValue,
                    rightAmplification = rightAmplification.floatValue,
                    leftTone = tone.floatValue,
                    rightTone = tone.floatValue,
                    leftConversationBoost = conversationBoostEnabled.value,
                    rightConversationBoost = conversationBoostEnabled.value,
                    leftAmbientNoiseReduction = ambientNoiseReduction.floatValue,
                    rightAmbientNoiseReduction = ambientNoiseReduction.floatValue,
                    netAmplification = leftAmplification.floatValue + rightAmplification.floatValue / 2,
                    balance = 0.5f + (rightAmplification.floatValue - leftAmplification.floatValue) / 2,
                    ownVoiceAmplification = ownVoiceAmplification.floatValue
                )
            )
        }

        LaunchedEffect(state.hearingAidData) {
            val parsed = parseHearingAidSettingsResponse(state.hearingAidData)
            if (parsed != null) {
                leftEQ.value = parsed.leftEQ.copyOf()
                rightEQ.value = parsed.rightEQ.copyOf()
                conversationBoostEnabled.value = parsed.leftConversationBoost
                tone.floatValue = parsed.leftTone
                ambientNoiseReduction.floatValue = parsed.leftAmbientNoiseReduction
                ownVoiceAmplification.floatValue = parsed.ownVoiceAmplification
                leftAmplification.floatValue = parsed.leftAmplification
                rightAmplification.floatValue = parsed.rightAmplification
                initialized.value = true
                Log.d(TAG, "Updated hearing aid settings from notification")
            } else {
                Log.w(TAG, "Failed to parse hearing aid settings from notification")
            }
        }

        LaunchedEffect(
            leftEQ.value,
            rightEQ.value,
            conversationBoostEnabled.value,
            leftAmplification.floatValue,
            rightAmplification.floatValue,
            tone.floatValue,
            ambientNoiseReduction.floatValue,
            ownVoiceAmplification.floatValue
        ) {
            if (!initialized.value) return@LaunchedEffect
            hearingAidSettings.value = HearingAidSettings(
                leftEQ = leftEQ.value,
                rightEQ = rightEQ.value,
                leftAmplification = leftAmplification.floatValue,
                rightAmplification = rightAmplification.floatValue,
                leftTone = tone.floatValue,
                rightTone = tone.floatValue,
                leftConversationBoost = conversationBoostEnabled.value,
                rightConversationBoost = conversationBoostEnabled.value,
                leftAmbientNoiseReduction = ambientNoiseReduction.floatValue,
                rightAmbientNoiseReduction = ambientNoiseReduction.floatValue,
                netAmplification = leftAmplification.floatValue + rightAmplification.floatValue / 2,
                balance = 0.5f + (rightAmplification.floatValue - leftAmplification.floatValue) / 2,
                ownVoiceAmplification = ownVoiceAmplification.floatValue
            )
            Log.d(TAG, "Updated settings: ${hearingAidSettings.value}")
            sendHearingAidSettings(state.hearingAidData, hearingAidSettings.value, debounceJob, setATTCharacteristicValue)
        }

        val frequencies =
            listOf("250Hz", "500Hz", "1kHz", "2kHz", "3kHz", "4kHz", "6kHz", "8kHz")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.width(60.dp))
            Text(
                text = stringResource(R.string.left),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMediumEmphasized
            )
            Text(
                text = stringResource(R.string.right),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMediumEmphasized
            )
        }

        frequencies.forEachIndexed { index, freq ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = freq,
                    modifier = Modifier
                        .width(60.dp)
                        .align(Alignment.CenterVertically),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelSmall
                )
                OutlinedTextField(
                    value = leftEQ.value[index].toString(),
                    onValueChange = { newValue ->
                        val parsed = newValue.toFloatOrNull()
                        if (parsed != null) {
                            val newArray = leftEQ.value.copyOf()
                            newArray[index] = parsed
                            leftEQ.value = newArray
                            Log.d(TAG, "Left EQ updated at index $index to $parsed")
                        }
                    },
//                        label = { Text("Value", fontSize = 14.sp, fontFamily = FontFamily(Font(R.font.sf_pro))) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = TextStyle(
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        fontSize = 14.sp
                    ),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = rightEQ.value[index].toString(),
                    onValueChange = { newValue ->
                        val parsed = newValue.toFloatOrNull()
                        if (parsed != null) {
                            val newArray = rightEQ.value.copyOf()
                            newArray[index] = parsed
                            rightEQ.value = newArray
                            Log.d(TAG, "Right EQ updated at index $index to $parsed")
                        }
                    },
//                        label = { Text("Value", fontSize = 14.sp, fontFamily = FontFamily(Font(R.font.sf_pro))) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = TextStyle(
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        fontSize = 14.sp
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

@Preview(name = "Apple")
@Composable
fun UpdateHearingTestScreenPreviewApple() {
    LibrePodsTheme(
        m3eEnabled = false
    ) {
        Box (
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            UpdateHearingTestScreen(
                state = demoState,
                setATTCharacteristicValue = { _, _ -> }
            )
        }
    }
}

@Preview(name = "Material")
@Composable
fun UpdateHearingTestScreenPreviewMaterial() {
    LibrePodsTheme(
        m3eEnabled = true
    ) {
        Box (
            modifier = Modifier
                .wrapContentHeight()
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            UpdateHearingTestScreen(
                state = demoState,
                setATTCharacteristicValue = { _, _ -> }
            )
        }
    }
}

