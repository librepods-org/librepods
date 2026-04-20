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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.R

data class SelectItem(
    val name: String,
    val description: String? = null,
    val iconRes: Int? = null,
    val selected: Boolean,
    val onClick: () -> Unit,
    val enabled: Boolean = true
)

data class SelectItem2(
    val name: String,
    val description: String? = null,
    val iconRes: Int? = null,
    val selected: () -> Boolean,
    val onClick: () -> Unit,
    val enabled: Boolean = true
)


@Composable
fun StyledSelectList(
    items: List<SelectItem>,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isDarkTheme) Color.White else Color.Black

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(28.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val visibleItems = items.filter { it.enabled }
        visibleItems.forEachIndexed { index, item ->
            val isFirst = index == 0
            val isLast = index == visibleItems.size - 1
            val hasIcon = item.iconRes != null

            val shape = when {
                isFirst -> RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                isLast -> RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
                else -> RoundedCornerShape(0.dp)
            }
            var itemBackgroundColor by remember { mutableStateOf(backgroundColor) }
            val animatedBackgroundColor by animateColorAsState(targetValue = itemBackgroundColor, animationSpec = tween(durationMillis = 500))

            Row(
                modifier = Modifier
                    .height(if (hasIcon) 72.dp else 55.dp)
                    .background(animatedBackgroundColor, shape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                itemBackgroundColor = if (isDarkTheme) Color(0x40888888) else Color(0x40D9D9D9)
                                tryAwaitRelease()
                                itemBackgroundColor = backgroundColor
                                item.onClick()
                            }
                        )
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (hasIcon) {
                    Icon(
                        painter = painterResource(item.iconRes!!),
                        contentDescription = "Icon",
                        tint = Color(0xFF007AFF),
                        modifier = Modifier
                            .height(48.dp)
                            .wrapContentWidth()
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp)
                        .padding(start = if (hasIcon) 8.dp else 4.dp)
                ) {
                    Text(
                        item.name,
                        fontSize = 16.sp,
                        color = textColor,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                    )
                    item.description?.let {
                        Text(
                            it,
                            fontSize = 14.sp,
                            color = textColor.copy(alpha = 0.6f),
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                        )
                    }
                }
                val floatAnimateState by animateFloatAsState(
                    targetValue = if (item.selected) 1f else 0f,
                    animationSpec = tween(durationMillis = 300)
                )
                Text(
                    text = "􀆅",
                    style = TextStyle(
                        fontSize = 20.sp,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = Color(0xFF007AFF).copy(alpha = floatAnimateState),
                    ),
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            if (!isLast) {
                if (hasIcon) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier.padding(start = 72.dp, end = 20.dp)
                    )
                } else {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp)
                    )
                }
            }
        }
    }
}