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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.composables

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.services.ServiceManager
import me.kavishdevar.librepods.utils.AACPManager
import kotlin.io.encoding.ExperimentalEncodingApi

@ExperimentalHazeMaterialsApi
@Composable
fun MicrophoneSettings(hazeState: HazeState) {
    val isDarkTheme = isSystemInDarkTheme()
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(28.dp))
            .padding(top = 2.dp)
    ) {
        val service = ServiceManager.getService()!!
        val micModeValue = service.aacpManager.controlCommandStatusList.find {
            it.identifier == AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE
        }?.value?.get(0) ?: 0x00.toByte()

        var selectedMode by remember {
            mutableStateOf(
                when (micModeValue) {
                    0x00.toByte() -> "Automatic"
                    0x01.toByte() -> "Always Right"
                    0x02.toByte() -> "Always Left"
                    else -> "Automatic"
                }
            )
        }
        var showDropdown by remember { mutableStateOf(false) }
        var touchOffset by remember { mutableStateOf<Offset?>(null) }
        var boxPosition by remember { mutableStateOf(Offset.Zero) }
        var lastDismissTime by remember { mutableLongStateOf(0L) }
        val reopenThresholdMs = 250L

        val listener = object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                if (AACPManager.Companion.ControlCommandIdentifiers.fromByte(controlCommand.identifier) ==
                    AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE
                ) {
                    selectedMode = when (controlCommand.value[0]) {
                        0x00.toByte() -> "Automatic"
                        0x01.toByte() -> "Always Right"
                        0x02.toByte() -> "Always Left"
                        else -> "Automatic"
                    }
                    Log.d("MicrophoneSettings", "Microphone mode received: $selectedMode")
                }
            }
        }

        LaunchedEffect(Unit) {
            service.aacpManager.registerControlCommandListener(
                AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE,
                listener
            )
        }

        DisposableEffect(Unit) {
            onDispose {
                service.aacpManager.unregisterControlCommandListener(
                    AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE,
                    listener
                )
            }
        }

        val density = LocalDensity.current
        val itemHeightPx = with(density) { 48.dp.toPx() }
        var parentHoveredIndex by remember { mutableStateOf<Int?>(null) }
        var parentDragActive by remember { mutableStateOf(false) }
        val microphoneAutomaticText = stringResource(R.string.microphone_automatic)
        val microphoneAlwaysRightText = stringResource(R.string.microphone_always_right)
        val microphoneAlwaysLeftText = stringResource(R.string.microphone_always_left)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(58.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val now = System.currentTimeMillis()
                        if (showDropdown) {
                            showDropdown = false
                            lastDismissTime = now
                        } else {
                            if (now - lastDismissTime > reopenThresholdMs) {
                                touchOffset = offset
                                showDropdown = true
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val now = System.currentTimeMillis()
                            touchOffset = offset
                            if (!showDropdown && now - lastDismissTime > reopenThresholdMs) {
                                showDropdown = true
                            }
                            lastDismissTime = now
                            parentDragActive = true
                            parentHoveredIndex = 0
                        },
                        onDrag = { change, _ ->
                            val current = change.position
                            val touch = touchOffset ?: current
                            val posInPopupY = current.y - touch.y
                            val idx = (posInPopupY / itemHeightPx).toInt()
                            parentHoveredIndex = idx
                        },
                        onDragEnd = {
                            parentDragActive = false
                            parentHoveredIndex?.let { idx ->
                                val options = listOf(
                                    microphoneAutomaticText,
                                    microphoneAlwaysRightText,
                                    microphoneAlwaysLeftText
                                )
                                if (idx in options.indices) {
                                    val option = options[idx]
                                    selectedMode = option
                                    showDropdown = false
                                    lastDismissTime = System.currentTimeMillis()
                                    val byteValue = when (option) {
                                        options[0] -> 0x00
                                        options[1] -> 0x01
                                        options[2] -> 0x02
                                        else -> 0x00
                                    }
                                    service.aacpManager.sendControlCommand(
                                        AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE.value,
                                        byteArrayOf(byteValue.toByte())
                                    )
                                }
                            }
                            parentHoveredIndex = null
                        },
                        onDragCancel = {
                            parentDragActive = false
                            parentHoveredIndex = null
                        }
                    )
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.microphone_mode),
                style = TextStyle(
                    fontSize = 16.sp,
                    color = textColor,
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Box(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    boxPosition = coordinates.positionInParent()
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedMode,
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = textColor.copy(alpha = 0.8f),
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    )
                    Text(
                        text = "􀆏",
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = textColor.copy(alpha = 0.6f),
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        ),
                        modifier = Modifier
                            .padding(start = 6.dp)
                    )
                }

                val microphoneAutomaticText = stringResource(R.string.microphone_automatic)
                val microphoneAlwaysRightText = stringResource(R.string.microphone_always_right)
                val microphoneAlwaysLeftText = stringResource(R.string.microphone_always_left)

                StyledDropdown(
                    expanded = showDropdown,
                    onDismissRequest = {
                        showDropdown = false
                        lastDismissTime = System.currentTimeMillis()
                    },
                    options = listOf(
                        microphoneAutomaticText,
                        microphoneAlwaysRightText,
                        microphoneAlwaysLeftText
                    ),
                    selectedOption = selectedMode,
                    touchOffset = touchOffset,
                    boxPosition = boxPosition,
                    externalHoveredIndex = parentHoveredIndex,
                    externalDragActive = parentDragActive,
                    onOptionSelected = { option ->
                        selectedMode = option
                        showDropdown = false
                        val byteValue = when (option) {
                            microphoneAutomaticText -> 0x00
                            microphoneAlwaysRightText -> 0x01
                            microphoneAlwaysLeftText -> 0x02
                            else -> 0x00
                        }
                        service.aacpManager.sendControlCommand(
                            AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE.value,
                            byteArrayOf(byteValue.toByte())
                        )
                    },
                    hazeState = hazeState
                )
            }
        }
    }
}

@ExperimentalHazeMaterialsApi
@Preview
@Composable
fun MicrophoneSettingsPreview() {
    MicrophoneSettings(HazeState())
}
