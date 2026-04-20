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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.NavigationButton
import me.kavishdevar.librepods.services.ServiceManager
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun AboutCard(navController: NavController) {
    val isDarkTheme = isSystemInDarkTheme()
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val service = ServiceManager.getService()
    if (service == null) return
    val airpodsInstance = service.airpodsInstance
    if (airpodsInstance == null) return
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    Box(
        modifier = Modifier
            .background(if (isDarkTheme) Color(0xFF000000) else Color(0xFFF2F2F7))
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ){
        Text(
            text = stringResource(R.string.about),
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textColor.copy(alpha = 0.6f)
            )
        )
    }

    val rowHeight = remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(28.dp))
            .padding(top = 2.dp)
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
                text = stringResource(R.string.model_name),
                style = TextStyle(
                    fontSize = 16.sp,
                    color = textColor,
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                )
            )
            Text(
                text = airpodsInstance.model.displayName,
                style = TextStyle(
                    fontSize = 16.sp,
                    color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.8f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                )
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Color(0x40888888),
            modifier = Modifier
                .padding(horizontal = 12.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.model_name),
                style = TextStyle(
                    fontSize = 16.sp,
                    color = textColor,
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                )
            )
            Text(
                text = airpodsInstance.actualModelNumber,
                style = TextStyle(
                    fontSize = 16.sp,
                    color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.8f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                )
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Color(0x40888888),
            modifier = Modifier
                .padding(horizontal = 12.dp)
        )
        val serialNumbers = listOf(
            airpodsInstance.serialNumber?: "",
            "􀀛 ${airpodsInstance.leftSerialNumber}",
            "􀀧 ${airpodsInstance.rightSerialNumber}"
        )
        val serialNumber = remember { mutableStateOf(0) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.serial_number),
                style = TextStyle(
                    fontSize = 16.sp,
                    color = textColor,
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
            )
            Text(
                text = serialNumbers[serialNumber.value],
                style = TextStyle(
                    fontSize = 16.sp,
                    color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.8f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        serialNumber.value = (serialNumber.value + 1) % serialNumbers.size
                    }
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Color(0x40888888),
            modifier = Modifier
                .padding(horizontal = 12.dp)
        )
        NavigationButton(
            to = "version_info",
            navController = navController,
            name = stringResource(R.string.version),
            currentState = airpodsInstance.version3,
            independent = false,
            height = rowHeight.value + 32.dp
        )
    }
}