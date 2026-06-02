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

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R

@Composable
fun AppInfoCard() {
    val rowHeight = remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isDarkTheme) Color.White else Color.Black

    Column {
        Box(
            modifier = Modifier
                .background(if (isDarkTheme) Color.Black else Color(0xFFF2F2F7))
                .padding(start = 16.dp, bottom = 8.dp, end = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.about), style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                )
            )
        }

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .fillMaxWidth()
                .background(backgroundColor, RoundedCornerShape(28.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .onGloballyPositioned { coordinates ->
                        rowHeight.value = with(density) { coordinates.size.height.toDp() }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.version), style = TextStyle(
                        fontSize = 16.sp,
                        color = textColor,
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
                Text(
                    text = BuildConfig.VERSION_NAME, style = TextStyle(
                        fontSize = 16.sp,
                        color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(
                            alpha = 0.8f
                        ),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0x40888888),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.version_code), style = TextStyle(
                        fontSize = 16.sp,
                        color = textColor,
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
                Text(
                    text = BuildConfig.VERSION_CODE.toString(), style = TextStyle(
                        fontSize = 16.sp,
                        color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(
                            alpha = 0.8f
                        ),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0x40888888),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.flavor), style = TextStyle(
                        fontSize = 16.sp,
                        color = textColor,
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
                Text(
                    text = BuildConfig.FLAVOR, style = TextStyle(
                        fontSize = 16.sp,
                        color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(
                            alpha = 0.8f
                        ),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0x40888888),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.build_type), style = TextStyle(
                        fontSize = 16.sp,
                        color = textColor,
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
                Text(
                    text = BuildConfig.BUILD_TYPE,
                    style = TextStyle(
                        fontSize = 16.sp,
                        color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(
                            alpha = 0.8f
                        ),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
            }
        }
    }
}
