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

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.CupertinoMaterials
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.R

@ExperimentalHazeMaterialsApi
@Composable
fun StyledDropdown(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    options: List<String>,
    selectedOption: String,
    touchOffset: Offset?,
    boxPosition: Offset,
    onOptionSelected: (String) -> Unit,
    externalHoveredIndex: Int? = null,
    externalDragActive: Boolean = false,
    hazeState: HazeState,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier
) {
    if (expanded) {
        val relativeOffset = touchOffset?.let { it - boxPosition } ?: Offset.Zero
        Popup(
            offset = IntOffset(relativeOffset.x.toInt(), relativeOffset.y.toInt()),
            onDismissRequest = onDismissRequest
        ) {
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
                Card(
                    modifier = modifier
                        .padding(8.dp)
                        .width(300.dp)
                        .background(Color.Transparent)
                        .clip(RoundedCornerShape(8.dp)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    var hoveredIndex by remember { mutableStateOf<Int?>(null) }
                    val itemHeight = 48.dp

                    var popupSize by remember { mutableStateOf(IntSize(0, 0)) }
                    var lastDragPosition by remember { mutableStateOf<Offset?>(null) }

                    LaunchedEffect(externalHoveredIndex, externalDragActive) {
                        if (externalDragActive) {
                            hoveredIndex = externalHoveredIndex
                        }
                    }

                    Column(
                        modifier = Modifier
                            .onGloballyPositioned { coordinates ->
                                popupSize = coordinates.size
                            }
                            .pointerInput(popupSize) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        hoveredIndex = (offset.y / itemHeight.toPx()).toInt()
                                        lastDragPosition = offset
                                    },
                                    onDrag = { change, _ ->
                                        val y = change.position.y
                                        hoveredIndex = (y / itemHeight.toPx()).toInt()
                                        lastDragPosition = change.position
                                    },
                                    onDragEnd = {
                                        val pos = lastDragPosition
                                        val withinBounds = pos != null &&
                                            pos.x >= 0f && pos.y >= 0f &&
                                            pos.x <= popupSize.width.toFloat() && pos.y <= popupSize.height.toFloat()

                                        if (withinBounds) {
                                            hoveredIndex?.let { idx ->
                                                if (idx in options.indices) {
                                                    onOptionSelected(options[idx])
                                                }
                                            }
                                            onDismissRequest()
                                        } else {
                                            hoveredIndex = null
                                        }
                                    }
                                )
                            }
                    ) {
                        options.forEachIndexed { index, text ->
                            val isHovered =
                                if (externalDragActive && externalHoveredIndex != null) {
                                    index == externalHoveredIndex
                                } else {
                                    index == hoveredIndex
                                }
                            val isSystemInDarkTheme = isSystemInDarkTheme()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(itemHeight)
                                    .background(
                                        Color.Transparent
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        onOptionSelected(text)
                                        onDismissRequest()
                                    }
                                    .hazeEffect(
                                        state = hazeState,
                                        style = CupertinoMaterials.regular(),
                                        block = fun HazeEffectScope.() {
                                            alpha = 1f
                                            backgroundColor = if (isSystemInDarkTheme) {
                                                Color(0xB02C2C2E)
                                            } else {
                                                Color(0xB0FFFFFF)
                                            }
                                            tints = if (isHovered) listOf(
                                                HazeTint(
                                                    color = if (isSystemInDarkTheme) Color(0x338A8A8A) else Color(0x40D9D9D9)
                                                )
                                            ) else listOf()
                                        })
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text,
                                        style = TextStyle(
                                            fontSize = 16.sp,
                                            color = if (isSystemInDarkTheme()) Color.White else Color.Black.copy(alpha = 0.75f),
                                            fontFamily = FontFamily(Font(R.font.sf_pro))
                                        )
                                    )
                                    Checkbox(
                                        checked = text == selectedOption,
                                        onCheckedChange = { onOptionSelected(text) },
                                        colors = CheckboxDefaults.colors().copy(
                                            checkedCheckmarkColor = Color(0xFF007AFF),
                                            uncheckedCheckmarkColor = Color.Transparent,
                                            checkedBoxColor = Color.Transparent,
                                            uncheckedBoxColor = Color.Transparent,
                                            checkedBorderColor = Color.Transparent,
                                            uncheckedBorderColor = Color.Transparent,
                                            disabledBorderColor = Color.Transparent,
                                            disabledCheckedBoxColor = Color.Transparent,
                                            disabledUncheckedBoxColor = Color.Transparent,
                                            disabledUncheckedBorderColor = Color.Transparent
                                        )
                                    )
                                }
                            }

                            if (index != options.lastIndex) {
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = Color(0x40888888),
                                    modifier = Modifier.padding(start = 12.dp, end = 0.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
