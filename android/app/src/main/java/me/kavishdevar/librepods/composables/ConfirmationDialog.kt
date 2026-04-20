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

package me.kavishdevar.librepods.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.CupertinoMaterials
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.R

@ExperimentalHazeMaterialsApi
@Composable
fun ConfirmationDialog(
    showDialog: MutableState<Boolean>,
    title: String,
    message: String,
    confirmText: String = "Enable",
    dismissText: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit = { showDialog.value = false },
    hazeState: HazeState,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val accentColor = if (isDarkTheme) Color(0xFF007AFF) else Color(0xFF3C6DF5)
    if (showDialog.value) {
        Dialog(onDismissRequest = { showDialog.value = false }) {
            Box(
                modifier = Modifier
                    // .fillMaxWidth(0.75f)
                    .requiredWidthIn(min = 200.dp, max = 360.dp)
                    .background(Color.Transparent, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .hazeEffect(
                        hazeState,
                        style = CupertinoMaterials.regular(
                            containerColor = if (isDarkTheme) Color(0xFF1C1C1E).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.95f)
                        )
                    )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        title,
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        message,
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = textColor.copy(alpha = 0.8f),
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier.fillMaxWidth()
                    )
                    var leftPressed by remember { mutableStateOf(false) }
                    var rightPressed by remember { mutableStateOf(false) }
                    val pressedColor = if (isDarkTheme) Color(0x40888888) else Color(0x40D9D9D9)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val position = event.changes.first().position
                                        val width = size.width.toFloat()
                                        val height = size.height.toFloat()
                                        val isWithinBounds = position.y >= 0 && position.y <= height
                                        val isLeft = position.x < width / 2
                                        event.changes.first().consume()
                                        when (event.type) {
                                            PointerEventType.Press -> {
                                                if (isWithinBounds) {
                                                    leftPressed = isLeft
                                                    rightPressed = !isLeft
                                                } else {
                                                    leftPressed = false
                                                    rightPressed = false
                                                }
                                            }
                                            PointerEventType.Move -> {
                                                if (isWithinBounds) {
                                                    leftPressed = isLeft
                                                    rightPressed = !isLeft
                                                } else {
                                                    leftPressed = false
                                                    rightPressed = false
                                                }
                                            }
                                            PointerEventType.Release -> {
                                                if (isWithinBounds) {
                                                    if (leftPressed) {
                                                        onDismiss()
                                                    } else if (rightPressed) {
                                                        onConfirm()
                                                    }
                                                }
                                                leftPressed = false
                                                rightPressed = false
                                            }
                                        }
                                    }
                                }
                            },
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(if (leftPressed) pressedColor else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dismissText,
                                style = TextStyle(
                                    color = accentColor,
                                    fontFamily = FontFamily(Font(R.font.sf_pro))
                                )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(Color(0x40888888))
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(if (rightPressed) pressedColor else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = confirmText,
                                style = TextStyle(
                                    color = accentColor,
                                    fontFamily = FontFamily(Font(R.font.sf_pro))
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
