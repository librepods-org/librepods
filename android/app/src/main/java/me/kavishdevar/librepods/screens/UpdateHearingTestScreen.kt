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

package me.kavishdevar.librepods.screens

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.services.ServiceManager
import me.kavishdevar.librepods.utils.ATTHandles
import me.kavishdevar.librepods.utils.HearingAidSettings
import me.kavishdevar.librepods.utils.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.utils.sendHearingAidSettings
import java.io.IOException
import kotlin.io.encoding.ExperimentalEncodingApi

private var debounceJob: MutableState<Job?> = mutableStateOf(null)
private const val TAG = "HearingAidAdjustments"

@SuppressLint("DefaultLocale")
@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun UpdateHearingTestScreen(@Suppress("unused") navController: NavController) {
    val verticalScrollState = rememberScrollState()
    val attManager = ServiceManager.getService()?.attManager
    if (attManager == null) {
        Text(
            text = stringResource(R.string.att_manager_is_null_try_reconnecting),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            textAlign = TextAlign.Center
        )
        return
    }

    val backdrop = rememberLayerBackdrop()
    StyledScaffold(
        title = stringResource(R.string.hearing_test)
    ) { spacerHeight, hazeState ->
        Column(
            modifier = Modifier
                .hazeSource(hazeState)
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .verticalScroll(verticalScrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val textColor = if (isSystemInDarkTheme()) Color.White else Color.Black

            Spacer(modifier = Modifier.height(spacerHeight))

            Text(
                text = stringResource(R.string.hearing_test_value_instruction),
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(
                    fontSize = 16.sp,
                    color = textColor,
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                textAlign = TextAlign.Center,
            )
            val tone = remember { mutableFloatStateOf(0.5f) }
            val ambientNoiseReduction = remember { mutableFloatStateOf(0.0f) }
            val ownVoiceAmplification = remember { mutableFloatStateOf(0.5f) }
            val leftAmplification = remember { mutableFloatStateOf(0.5f) }
            val rightAmplification = remember { mutableFloatStateOf(0.5f) }
            val conversationBoostEnabled = remember { mutableStateOf(false) }
            val leftEQ = remember { mutableStateOf(FloatArray(8)) }
            val rightEQ = remember { mutableStateOf(FloatArray(8)) }

            val initialLoadComplete = remember { mutableStateOf(false) }
            val initialReadSucceeded = remember { mutableStateOf(false) }
            val initialReadAttempts = remember { mutableIntStateOf(0) }

            val hearingAidSettings = remember {
                mutableStateOf(
                    HearingAidSettings(
                        leftEQ = leftEQ.value,
                        rightEQ = rightEQ.value,
                        leftAmplification = leftAmplification.value,
                        rightAmplification = rightAmplification.value,
                        leftTone = tone.value,
                        rightTone = tone.value,
                        leftConversationBoost = conversationBoostEnabled.value,
                        rightConversationBoost = conversationBoostEnabled.value,
                        leftAmbientNoiseReduction = ambientNoiseReduction.value,
                        rightAmbientNoiseReduction = ambientNoiseReduction.value,
                        netAmplification = leftAmplification.value + rightAmplification.value / 2,
                        balance = 0.5f + (rightAmplification.value - leftAmplification.value) / 2,
                        ownVoiceAmplification = ownVoiceAmplification.value
                    )
                )
            }

            val hearingAidATTListener = remember {
                object : (ByteArray) -> Unit {
                    override fun invoke(value: ByteArray) {
                        val parsed = parseHearingAidSettingsResponse(value)
                        if (parsed != null) {
                            leftEQ.value = parsed.leftEQ.copyOf()
                            rightEQ.value = parsed.rightEQ.copyOf()
                            conversationBoostEnabled.value = parsed.leftConversationBoost
                            tone.value = parsed.leftTone
                            ambientNoiseReduction.value = parsed.leftAmbientNoiseReduction
                            ownVoiceAmplification.value = parsed.ownVoiceAmplification
                            leftAmplification.value = parsed.leftAmplification
                            rightAmplification.value = parsed.rightAmplification
                            Log.d(TAG, "Updated hearing aid settings from notification")
                        } else {
                            Log.w(TAG, "Failed to parse hearing aid settings from notification")
                        }
                    }
                }
            }


            DisposableEffect(Unit) {
                onDispose {
                    attManager.unregisterListener(ATTHandles.HEARING_AID, hearingAidATTListener)
                }
            }

            LaunchedEffect(leftEQ.value, rightEQ.value, conversationBoostEnabled.value, initialLoadComplete.value, initialReadSucceeded.value, leftAmplification.value, rightAmplification.value, tone.value, ambientNoiseReduction.value, ownVoiceAmplification.value) {
                if (!initialLoadComplete.value) {
                    Log.d(TAG, "Initial device load not complete - skipping send")
                    return@LaunchedEffect
                }

                if (!initialReadSucceeded.value) {
                    Log.d(TAG, "Initial device read not successful yet - skipping send until read succeeds")
                    return@LaunchedEffect
                }

                hearingAidSettings.value = HearingAidSettings(
                    leftEQ = leftEQ.value,
                    rightEQ = rightEQ.value,
                    leftAmplification = leftAmplification.value,
                    rightAmplification = rightAmplification.value,
                    leftTone = tone.value,
                    rightTone = tone.value,
                    leftConversationBoost = conversationBoostEnabled.value,
                    rightConversationBoost = conversationBoostEnabled.value,
                    leftAmbientNoiseReduction = ambientNoiseReduction.value,
                    rightAmbientNoiseReduction = ambientNoiseReduction.value,
                    netAmplification = leftAmplification.value + rightAmplification.value / 2,
                    balance = 0.5f + (rightAmplification.value - leftAmplification.value) / 2,
                    ownVoiceAmplification = ownVoiceAmplification.value
                )
                Log.d(TAG, "Updated settings: ${hearingAidSettings.value}")
                sendHearingAidSettings(attManager, hearingAidSettings.value, debounceJob)
            }

            LaunchedEffect(Unit) {
                Log.d(TAG, "Connecting to ATT...")
                try {
                    attManager.enableNotifications(ATTHandles.HEARING_AID)
                    attManager.registerListener(ATTHandles.HEARING_AID, hearingAidATTListener)

                    var parsedSettings: HearingAidSettings? = null
                    for (attempt in 1..3) {
                        initialReadAttempts.intValue = attempt
                        try {
                            val data = attManager.read(ATTHandles.HEARING_AID)
                            parsedSettings = parseHearingAidSettingsResponse(data = data)
                            if (parsedSettings != null) {
                                Log.d(TAG, "Parsed settings on attempt $attempt")
                                break
                            } else {
                                Log.d(TAG, "Parsing returned null on attempt $attempt")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Read attempt $attempt failed: ${e.message}")
                        }
                        delay(200)
                    }

                    if (parsedSettings != null) {
                        Log.d(TAG, "Initial hearing aid settings: $parsedSettings")
                        leftEQ.value = parsedSettings.leftEQ.copyOf()
                        rightEQ.value = parsedSettings.rightEQ.copyOf()
                        conversationBoostEnabled.value = parsedSettings.leftConversationBoost
                        tone.value = parsedSettings.leftTone
                        ambientNoiseReduction.value = parsedSettings.leftAmbientNoiseReduction
                        ownVoiceAmplification.value = parsedSettings.ownVoiceAmplification
                        leftAmplification.value = parsedSettings.leftAmplification
                        rightAmplification.value = parsedSettings.rightAmplification
                        initialReadSucceeded.value = true
                    } else {
                        Log.d(TAG, "Failed to read/parse initial hearing aid settings after ${initialReadAttempts.intValue} attempts")
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    initialLoadComplete.value = true
                }
            }

            val frequencies = listOf("250Hz", "500Hz", "1kHz", "2kHz", "3kHz", "4kHz", "6kHz", "8kHz")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(modifier = Modifier.width(60.dp))
                Text(
                    text = stringResource(R.string.left),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = textColor
                    )
                )
                Text(
                    text = stringResource(R.string.right),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = textColor
                    )
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
                        style = TextStyle(
                            color = textColor,
                            fontSize = 16.sp,
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        ),
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
        }
    }
}
