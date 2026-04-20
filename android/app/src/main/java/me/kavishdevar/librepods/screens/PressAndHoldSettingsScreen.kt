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

package me.kavishdevar.librepods.screens

import android.content.Context
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.SelectItem
import me.kavishdevar.librepods.composables.StyledIconButton
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.composables.StyledSelectList
import me.kavishdevar.librepods.constants.StemAction
import me.kavishdevar.librepods.services.ServiceManager
import me.kavishdevar.librepods.utils.AACPManager
import kotlin.experimental.and
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun RightDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = Color(0x40888888),
        modifier = Modifier
            .padding(start = 72.dp, end = 20.dp)
    )
}

@Composable
fun RightDividerNoIcon() {
    HorizontalDivider(
        thickness = 1.dp,
        color = Color(0x40888888),
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp)
    )
}

@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongPress(navController: NavController, name: String) {
    val isDarkTheme = isSystemInDarkTheme()
    val textColor = if (isDarkTheme) Color.White else Color.Black

    val modesByte = ServiceManager.getService()!!.aacpManager.controlCommandStatusList.find {
        it.identifier == AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS
    }?.value?.takeIf { it.isNotEmpty() }?.get(0)

    if (modesByte != null) {
        Log.d("PressAndHoldSettingsScreen", "Current modes state: ${modesByte.toString(2)}")
        Log.d("PressAndHoldSettingsScreen", "Off mode: ${(modesByte and 0x01) != 0.toByte()}")
        Log.d("PressAndHoldSettingsScreen", "Transparency mode: ${(modesByte and 0x04) != 0.toByte()}")
        Log.d("PressAndHoldSettingsScreen", "Noise Cancellation mode: ${(modesByte and 0x02) != 0.toByte()}")
        Log.d("PressAndHoldSettingsScreen", "Adaptive mode: ${(modesByte and 0x08) != 0.toByte()}")
    }
    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val prefKey = if (name.lowercase() == "left") "left_long_press_action" else "right_long_press_action"
    val longPressActionPref = sharedPreferences.getString(prefKey, StemAction.CYCLE_NOISE_CONTROL_MODES.name)
    Log.d("PressAndHoldSettingsScreen", "Long press action preference ($prefKey): $longPressActionPref")
    var longPressAction by remember { mutableStateOf(StemAction.valueOf(longPressActionPref ?: StemAction.CYCLE_NOISE_CONTROL_MODES.name)) }
    val backdrop = rememberLayerBackdrop()
    StyledScaffold(
        title = name
    ) { spacerHeight ->
        val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
        Column (
          modifier = Modifier
              .layerBackdrop(backdrop)
              .fillMaxSize()
              .padding(top = 8.dp)
              .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(spacerHeight))
            val actionItems = listOf(
                SelectItem(
                    name = stringResource(R.string.noise_control),
                    selected = longPressAction == StemAction.CYCLE_NOISE_CONTROL_MODES,
                    onClick = {
                        longPressAction = StemAction.CYCLE_NOISE_CONTROL_MODES
                        sharedPreferences.edit { putString(prefKey, StemAction.CYCLE_NOISE_CONTROL_MODES.name) }
                    }
                ),
                SelectItem(
                    name = stringResource(R.string.digital_assistant),
                    selected = longPressAction == StemAction.DIGITAL_ASSISTANT,
                    onClick = {
                        longPressAction = StemAction.DIGITAL_ASSISTANT
                        sharedPreferences.edit { putString(prefKey, StemAction.DIGITAL_ASSISTANT.name) }
                    }
                )
            )
            StyledSelectList(items = actionItems)

            if (longPressAction == StemAction.CYCLE_NOISE_CONTROL_MODES) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = stringResource(R.string.noise_control),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor.copy(alpha = 0.6f),
                    ),
                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                val offListeningModeValue = ServiceManager.getService()!!.aacpManager.controlCommandStatusList.find {
                    it.identifier == AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION
                }?.value?.takeIf { it.isNotEmpty() }?.get(0)
                Log.d("PressAndHoldSettingsScreen", "Allow Off state: $offListeningModeValue")
                val allowOff = offListeningModeValue == 1.toByte()
                Log.d("PressAndHoldSettingsScreen", "Allow Off option: $allowOff")

                val initialByte = ServiceManager.getService()!!.aacpManager.controlCommandStatusList.find {
                    it.identifier == AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS
                }?.value?.takeIf { it.isNotEmpty() }?.get(0)?.toInt() ?: sharedPreferences.getInt("long_press_byte", 0b0101)
                var currentByte by remember { mutableStateOf(initialByte) }

                val listeningModeItems = mutableListOf<SelectItem>()
                if (allowOff) {
                    listeningModeItems.add(
                        SelectItem(
                            name = stringResource(R.string.off),
                            description = stringResource(R.string.listening_mode_off_description),
                            iconRes = R.drawable.noise_cancellation,
                            selected = (currentByte and 0x01) != 0,
                            onClick = {
                                val bit = 0x01
                                val newValue = if ((currentByte and bit) != 0) {
                                    val temp = currentByte and bit.inv()
                                    if (countEnabledModes(temp) >= 2) temp else currentByte
                                } else {
                                    currentByte or bit
                                }
                                ServiceManager.getService()!!.aacpManager.sendControlCommand(
                                    AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS.value,
                                    newValue.toByte()
                                )
                                sharedPreferences.edit {
                                    putInt("long_press_byte", newValue)
                                }
                                currentByte = newValue
                            }
                        )
                    )
                }
                listeningModeItems.addAll(listOf(
                    SelectItem(
                        name = stringResource(R.string.transparency),
                        description = stringResource(R.string.listening_mode_transparency_description),
                        iconRes = R.drawable.transparency,
                        selected = (currentByte and 0x04) != 0,
                        onClick = {
                            val bit = 0x04
                            val newValue = if ((currentByte and bit) != 0) {
                                val temp = currentByte and bit.inv()
                                if (countEnabledModes(temp) >= 2) temp else currentByte
                            } else {
                                currentByte or bit
                            }
                            ServiceManager.getService()!!.aacpManager.sendControlCommand(
                                AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS.value,
                                newValue.toByte()
                            )
                            sharedPreferences.edit {
                                putInt("long_press_byte", newValue)
                            }
                            currentByte = newValue
                        }
                    ),
                    SelectItem(
                        name = stringResource(R.string.adaptive),
                        description = stringResource(R.string.listening_mode_adaptive_description),
                        iconRes = R.drawable.adaptive,
                        selected = (currentByte and 0x08) != 0,
                        onClick = {
                            val bit = 0x08
                            val newValue = if ((currentByte and bit) != 0) {
                                val temp = currentByte and bit.inv()
                                if (countEnabledModes(temp) >= 2) temp else currentByte
                            } else {
                                currentByte or bit
                            }
                            ServiceManager.getService()!!.aacpManager.sendControlCommand(
                                AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS.value,
                                newValue.toByte()
                            )
                            sharedPreferences.edit {
                                putInt("long_press_byte", newValue)
                            }
                            currentByte = newValue
                        }
                    ),
                    SelectItem(
                        name = stringResource(R.string.noise_cancellation),
                        description = stringResource(R.string.listening_mode_noise_cancellation_description),
                        iconRes = R.drawable.noise_cancellation,
                        selected = (currentByte and 0x02) != 0,
                        onClick = {
                            val bit = 0x02
                            val newValue = if ((currentByte and bit) != 0) {
                                val temp = currentByte and bit.inv()
                                if (countEnabledModes(temp) >= 2) temp else currentByte
                            } else {
                                currentByte or bit
                            }
                            ServiceManager.getService()!!.aacpManager.sendControlCommand(
                                AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS.value,
                                newValue.toByte()
                            )
                            sharedPreferences.edit {
                                putInt("long_press_byte", newValue)
                            }
                            currentByte = newValue
                        }
                    )
                ))
                StyledSelectList(items = listeningModeItems)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.press_and_hold_noise_control_description),
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Light,
                        color = textColor.copy(alpha = 0.6f),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    ),
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                )
            }
        }
    }
    Log.d("PressAndHoldSettingsScreen", "Current byte: ${ServiceManager.getService()!!.aacpManager.controlCommandStatusList.find {
        it.identifier == AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS
    }?.value?.takeIf { it.isNotEmpty() }?.get(0)?.toString(2)}")
}

fun countEnabledModes(byteValue: Int): Int {
    var count = 0
    if ((byteValue and 0x01) != 0) count++
    if ((byteValue and 0x02) != 0) count++
    if ((byteValue and 0x04) != 0) count++
    if ((byteValue and 0x08) != 0) count++
    return count
}
