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

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.Job
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.data.HearingAidSettings
import me.kavishdevar.librepods.data.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.data.sendHearingAidSettings
import me.kavishdevar.librepods.presentation.components.StyledSlider
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "HearingAidAdjustments"

@SuppressLint("DefaultLocale")
@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun HearingAidAdjustmentsScreen(viewModel: AirPodsViewModel) {
    val verticalScrollState = rememberScrollState()
    val state by viewModel.uiState.collectAsState()

    val debounceJob = remember { mutableStateOf<Job?>(null) }

    val amplificationSliderValue = rememberSaveable { mutableFloatStateOf(0.5f) }
    val balanceSliderValue = rememberSaveable { mutableFloatStateOf(0.5f) }
    val toneSliderValue = rememberSaveable { mutableFloatStateOf(0.5f) }
    val ambientNoiseReductionSliderValue = rememberSaveable { mutableFloatStateOf(0.0f) }
    val conversationBoostEnabled = rememberSaveable { mutableStateOf(false) }
    val leftEQ = rememberSaveable { mutableStateOf(FloatArray(8)) }
    val rightEQ = rememberSaveable { mutableStateOf(FloatArray(8)) }
    val ownVoiceAmplification = rememberSaveable { mutableFloatStateOf(0.5f) }

    val initialized = rememberSaveable { mutableStateOf(false) }

    val hearingAidSettings = remember { mutableStateOf(
        HearingAidSettings(
            leftEQ = leftEQ.value,
            rightEQ = rightEQ.value,
            leftAmplification = 0f,
            rightAmplification = 0f,
            leftTone = 0f,
            rightTone = 0f,
            leftConversationBoost = false,
            rightConversationBoost = false,
            leftAmbientNoiseReduction = 0f,
            rightAmbientNoiseReduction = 0f,
            netAmplification = 0f,
            balance = 0f,
            ownVoiceAmplification = 0f
        )
    ) }

    LaunchedEffect(state.hearingAidData) {
        parseHearingAidSettingsResponse(state.hearingAidData)?.let { parsed ->
            amplificationSliderValue.floatValue = parsed.netAmplification
            balanceSliderValue.floatValue = parsed.balance
            toneSliderValue.floatValue = parsed.leftTone
            ambientNoiseReductionSliderValue.floatValue = parsed.leftAmbientNoiseReduction
            conversationBoostEnabled.value = parsed.leftConversationBoost
            leftEQ.value = parsed.leftEQ.copyOf()
            rightEQ.value = parsed.rightEQ.copyOf()
            ownVoiceAmplification.floatValue = parsed.ownVoiceAmplification
            initialized.value = true
        }
    }

    LaunchedEffect(
        amplificationSliderValue.floatValue,
        balanceSliderValue.floatValue,
        toneSliderValue.floatValue,
        conversationBoostEnabled.value,
        ambientNoiseReductionSliderValue.floatValue,
        ownVoiceAmplification.floatValue
    ) {
        if (!initialized.value) return@LaunchedEffect
        hearingAidSettings.value = HearingAidSettings(
            leftEQ = leftEQ.value,
            rightEQ = rightEQ.value,
            leftAmplification = amplificationSliderValue.floatValue + if (balanceSliderValue.floatValue < 0) -balanceSliderValue.floatValue else 0f,
            rightAmplification = amplificationSliderValue.floatValue + if (balanceSliderValue.floatValue > 0) balanceSliderValue.floatValue else 0f,
            leftTone = toneSliderValue.floatValue,
            rightTone = toneSliderValue.floatValue,
            leftConversationBoost = conversationBoostEnabled.value,
            rightConversationBoost = conversationBoostEnabled.value,
            leftAmbientNoiseReduction = ambientNoiseReductionSliderValue.floatValue,
            rightAmbientNoiseReduction = ambientNoiseReductionSliderValue.floatValue,
            netAmplification = amplificationSliderValue.floatValue,
            balance = balanceSliderValue.floatValue,
            ownVoiceAmplification = ownVoiceAmplification.floatValue
        )
        Log.d(TAG, "Updated settings: ${hearingAidSettings.value}")
        sendHearingAidSettings(state.hearingAidData, hearingAidSettings.value, debounceJob, viewModel::setATTCharacteristicValue)
    }

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(verticalScrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        StyledSlider(
            label = stringResource(R.string.amplification),
            valueRange = -1f..1f,
            value = amplificationSliderValue.floatValue,
            onValueChange = {
                amplificationSliderValue.floatValue = it
            },
            startIcon = "􀊥",
            endIcon = "􀊩",
            independent = true,
        )

        StyledToggle(
            label = stringResource(R.string.swipe_to_control_amplification),
            checked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.HPS_GAIN_SWIPE]?.getOrNull(0) == 0x01.toByte(),
            onCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.HPS_GAIN_SWIPE, it) },
            description = stringResource(R.string.swipe_amplification_description)
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
            onCheckedChange = { conversationBoostEnabled.value = it },
            description = stringResource(R.string.conversation_boost_description)
        )

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}
