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

package me.kavishdevar.librepods.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun StyledToggle(
    title: String? = null,
    label: String,
    description: String? = null,
    checked: Boolean = false,
    independent: Boolean = true,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val currentChecked by rememberUpdatedState(checked)

    val isDarkTheme = isSystemInDarkTheme()
    val textColor = if (isDarkTheme) Color.White else Color.Black

    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var backgroundColor by remember {
        mutableStateOf(
            if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
        )
    }

    val animatedBackgroundColor by animateColorAsState(
        targetValue = backgroundColor,
        animationSpec = tween(durationMillis = 500)
    )

    if (independent) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor.copy(alpha = 0.6f),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    ),
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 4.dp
                    )
                )
            }

            Box(
                modifier = Modifier
                    .background(animatedBackgroundColor, RoundedCornerShape(28.dp))
                    .padding(4.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                if (enabled) {
                                    backgroundColor =
                                        if (isDarkTheme) Color(0x40888888) else Color(0x40D9D9D9)
                                    tryAwaitRelease()
                                    backgroundColor =
                                        if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
                                }
                            },
                            onTap = {
                                if (enabled) {
                                    scope.launch { haptics.performHapticFeedback(if (!currentChecked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff) }
                                    onCheckedChange(!currentChecked)
                                }
                            }
                        )
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(55.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            fontWeight = FontWeight.Normal,
                            color = textColor
                        )
                    )

                    StyledSwitch(
                        checked = checked,
                        enabled = enabled,
                        onCheckedChange = {
                            if (enabled) {
                                scope.launch { haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff) }
                                onCheckedChange(it)
                            }
                        }
                    )
                }
            }

            if (description != null) {
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .background(
                            if (isDarkTheme) Color(0xFF000000)
                            else Color(0xFFF2F2F7)
                        )
                ) {
                    Text(
                        text = description,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Light,
                            color = textColor.copy(alpha = 0.6f),
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    )
                }
            }
        }
    } else {
        val isPressed = remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    shape = RoundedCornerShape(28.dp),
                    color = if (isPressed.value) Color(0xFFE0E0E0) else Color.Transparent
                )
                .padding(16.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed.value = true
                            tryAwaitRelease()
                            isPressed.value = false
                        }
                    )
                }
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    if (enabled) {
                        scope.launch { haptics.performHapticFeedback(if (!currentChecked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff) }
                        onCheckedChange(!currentChecked)
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        fontWeight = FontWeight.Normal,
                        color = textColor
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                if (description != null) {
                    Text(
                        text = description,
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = textColor.copy(0.6f),
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                        )
                    )
                }
            }

            StyledSwitch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = {
                    if (enabled) {
                        onCheckedChange(it)
                    }
                }
            )
        }
    }
}

@Preview
@Composable
fun StyledTogglePreview() {
    val checked = remember { mutableStateOf(false) }
    StyledToggle(
        label = "Example Toggle",
        description = "This is an example description for the styled toggle.",
        checked = checked.value,
        onCheckedChange = { checked.value = !checked.value }
    )
}
