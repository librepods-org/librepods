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
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
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
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.StyledIconButton
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.composables.StyledSlider
import me.kavishdevar.librepods.composables.StyledToggle
import me.kavishdevar.librepods.services.ServiceManager
import me.kavishdevar.librepods.utils.ATTHandles
import me.kavishdevar.librepods.utils.RadareOffsetFinder
import me.kavishdevar.librepods.utils.TransparencySettings
import me.kavishdevar.librepods.utils.parseTransparencySettingsResponse
import me.kavishdevar.librepods.utils.sendTransparencySettings
import java.io.IOException
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "TransparencySettings"

@SuppressLint("DefaultLocale")
@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun TransparencySettingsScreen(navController: NavController) {
    val isDarkTheme = isSystemInDarkTheme()
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val verticalScrollState = rememberScrollState()
    val attManager = ServiceManager.getService()?.attManager ?: return
    val aacpManager = remember { ServiceManager.getService()?.aacpManager }
    val isSdpOffsetAvailable =
        remember { mutableStateOf(RadareOffsetFinder.isSdpOffsetAvailable()) }

    val trackColor = if (isDarkTheme) Color(0xFFB3B3B3) else Color(0xFF929491)
    val activeTrackColor = if (isDarkTheme) Color(0xFF007AFF) else Color(0xFF3C6DF5)
    val thumbColor = if (isDarkTheme) Color(0xFFFFFFFF) else Color(0xFFFFFFFF)

    val backdrop = rememberLayerBackdrop()

    StyledScaffold(
        title = stringResource(R.string.customize_transparency_mode)
    ){ spacerHeight, hazeState ->
        Column(
            modifier = Modifier
                .hazeSource(hazeState)
                .layerBackdrop(backdrop)
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(spacerHeight))
            val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

            val enabled = remember { mutableStateOf(false) }
            val amplificationSliderValue = remember { mutableFloatStateOf(0.5f) }
            val balanceSliderValue = remember { mutableFloatStateOf(0.5f) }
            val toneSliderValue = remember { mutableFloatStateOf(0.5f) }
            val ambientNoiseReductionSliderValue = remember { mutableFloatStateOf(0.0f) }
            val conversationBoostEnabled = remember { mutableStateOf(false) }
            val eq = remember { mutableStateOf(FloatArray(8)) }
            val phoneMediaEQ = remember { mutableStateOf(FloatArray(8) { 0.5f }) }

            val initialLoadComplete = remember { mutableStateOf(false) }

            val initialReadSucceeded = remember { mutableStateOf(false) }
            val initialReadAttempts = remember { mutableIntStateOf(0) }

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

            val transparencyListener = remember {
                object : (ByteArray) -> Unit {
                    override fun invoke(value: ByteArray) {
                        val parsed = parseTransparencySettingsResponse(value)
                        enabled.value = parsed.enabled
                        amplificationSliderValue.floatValue = parsed.netAmplification
                        balanceSliderValue.floatValue = parsed.balance
                        toneSliderValue.floatValue = parsed.leftTone
                        ambientNoiseReductionSliderValue.floatValue =
                            parsed.leftAmbientNoiseReduction
                        conversationBoostEnabled.value = parsed.leftConversationBoost
                        eq.value = parsed.leftEQ.copyOf()
                        Log.d(TAG, "Updated transparency settings from notification")
                    }
                }
            }

            LaunchedEffect(
                enabled.value,
                amplificationSliderValue.floatValue,
                balanceSliderValue.floatValue,
                toneSliderValue.floatValue,
                conversationBoostEnabled.value,
                ambientNoiseReductionSliderValue.floatValue,
                eq.value,
                initialLoadComplete.value,
                initialReadSucceeded.value
            ) {
                if (!initialLoadComplete.value) {
                    Log.d(TAG, "Initial device load not complete - skipping send")
                    return@LaunchedEffect
                }

                if (!initialReadSucceeded.value) {
                    Log.d(
                        TAG,
                        "Initial device read not successful yet - skipping send until read succeeds"
                    )
                    return@LaunchedEffect
                }

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
                sendTransparencySettings(attManager, transparencySettings.value)
            }

            DisposableEffect(Unit) {
                onDispose {
                    attManager.unregisterListener(ATTHandles.TRANSPARENCY, transparencyListener)
                }
            }

            LaunchedEffect(Unit) {
                Log.d(TAG, "Connecting to ATT...")
                try {
                    attManager.enableNotifications(ATTHandles.TRANSPARENCY)
                    attManager.registerListener(ATTHandles.TRANSPARENCY, transparencyListener)

                    // If we have an AACP manager, prefer its EQ data to populate EQ controls first
                    try {
                        if (aacpManager != null) {
                            Log.d(TAG, "Found AACPManager, reading cached EQ data")
                            val aacpEQ = aacpManager.eqData
                            if (aacpEQ.isNotEmpty()) {
                                eq.value = aacpEQ.copyOf()
                                phoneMediaEQ.value = aacpEQ.copyOf()
                                Log.d(TAG, "Populated EQ from AACPManager: ${aacpEQ.toList()}")
                            } else {
                                Log.d(TAG, "AACPManager EQ data empty")
                            }
                        } else {
                            Log.d(TAG, "No AACPManager available")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error reading EQ from AACPManager: ${e.message}")
                    }

                    var parsedSettings: TransparencySettings? = null
                    for (attempt in 1..3) {
                        initialReadAttempts.intValue = attempt
                        try {
                            val data = attManager.read(ATTHandles.TRANSPARENCY)
                            parsedSettings = parseTransparencySettingsResponse(data = data)
                            Log.d(TAG, "Parsed settings on attempt $attempt")
                        } catch (e: Exception) {
                            Log.w(TAG, "Read attempt $attempt failed: ${e.message}")
                        }
                        delay(200)
                    }

                    if (parsedSettings != null) {
                        Log.d(TAG, "Initial transparency settings: $parsedSettings")
                        enabled.value = parsedSettings.enabled
                        amplificationSliderValue.floatValue = parsedSettings.netAmplification
                        balanceSliderValue.floatValue = parsedSettings.balance
                        toneSliderValue.floatValue = parsedSettings.leftTone
                        ambientNoiseReductionSliderValue.floatValue =
                            parsedSettings.leftAmbientNoiseReduction
                        conversationBoostEnabled.value = parsedSettings.leftConversationBoost
                        eq.value = parsedSettings.leftEQ.copyOf()
                        initialReadSucceeded.value = true
                    } else {
                        Log.d(
                            TAG,
                            "Failed to read/parse initial transparency settings after ${initialReadAttempts.intValue} attempts"
                        )
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    initialLoadComplete.value = true
                }
            }

            // Only show transparency mode section if SDP offset is available
            if (isSdpOffsetAvailable.value) {
                StyledToggle(
                    label = stringResource(R.string.transparency_mode),
                    checkedState = enabled,
                    independent = true,
                    description = stringResource(R.string.customize_transparency_mode_description)
                )
                Spacer(modifier = Modifier.height(4.dp))
                StyledSlider(
                    label = stringResource(R.string.amplification),
                    valueRange = -1f..1f,
                    mutableFloatState = amplificationSliderValue,
                    onValueChange = {
                        amplificationSliderValue.floatValue = it
                    },
                    startIcon = "􀊥",
                    endIcon = "􀊩",
                    independent = true
                )

                StyledSlider(
                    label = stringResource(R.string.balance),
                    valueRange = -1f..1f,
                    mutableFloatState = balanceSliderValue,
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
                    mutableFloatState = toneSliderValue,
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
                    mutableFloatState = ambientNoiseReductionSliderValue,
                    onValueChange = {
                        ambientNoiseReductionSliderValue.floatValue = it
                    },
                    startLabel = stringResource(R.string.less),
                    endLabel = stringResource(R.string.more),
                    independent = true,
                )

                StyledToggle(
                    label = stringResource(R.string.conversation_boost),
                    checkedState = conversationBoostEnabled,
                    independent = true,
                    description = stringResource(R.string.conversation_boost_description)
                )
            }

            // Only show transparency mode EQ section if SDP offset is available
            if (isSdpOffsetAvailable.value) {
                Text(
                    text = stringResource(R.string.equalizer),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor.copy(alpha = 0.6f),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    ),
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

                            Slider(
                                value = eqValue.floatValue,
                                onValueChange = { newVal ->
                                    eqValue.floatValue = newVal
                                    val newEQ = eq.value.copyOf()
                                    newEQ[i] = eqValue.floatValue
                                    eq.value = newEQ
                                },
                                valueRange = 0f..100f,
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
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
        }
    }
}
