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

package me.kavishdevar.librepods.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
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
    backdrop: LayerBackdrop,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val accentColor = if (isDarkTheme) Color(0xFF0091FF) else Color(0xFF0088FF)

    AnimatedVisibility(
        visible = showDialog.value,
        enter = scaleIn(initialScale = 1.05f) + fadeIn(),
        exit = scaleOut(targetScale = 1.05f) + fadeOut()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val innerBackdrop = rememberLayerBackdrop()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(enabled = false, onClick = {}),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .requiredWidthIn(min = 200.dp, max = 360.dp)
                        .clip(RoundedCornerShape(48.dp))
                        .drawBackdrop(
                            backdrop = backdrop,
                            exportedBackdrop = innerBackdrop,
                            shape = { RoundedCornerShape(48.dp) },
                            effects = {
                                vibrancy()
                                blur(4f.dp.toPx())
                                lens(12f.dp.toPx(), 48f.dp.toPx(), true)
                            },
                            onDrawSurface = {
                                drawRect(
                                    if (isDarkTheme) Color(0xFF1F1F1F).copy(alpha = 0.35f) else Color(0xFFE0E0E0).copy(alpha = 0.7f)
                                )
                            })) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(modifier = Modifier.height(24.dp))
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
                        Spacer(modifier = Modifier.height(12.dp))
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
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(0.9f),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            StyledButton(
                                onClick = onDismiss,
                                backdrop = innerBackdrop,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = dismissText, style = TextStyle(
                                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp,
                                        color = textColor
                                    )
                                )
                            }
                            StyledButton(
                                onClick = onConfirm,
                                backdrop = innerBackdrop,
                                modifier = Modifier.weight(1f),
                                surfaceColor = accentColor
                            ) {
                                Text(
                                    text = confirmText, style = TextStyle(
                                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp,
                                        color = Color.White
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}
