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

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.constants.StemAction

@Composable
fun PressAndHoldSettings(navController: NavController) {
    val isDarkTheme = isSystemInDarkTheme()
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val dividerColor = Color(0x40888888)

    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val leftAction = sharedPreferences.getString("left_long_press_action", StemAction.CYCLE_NOISE_CONTROL_MODES.name)
    val rightAction = sharedPreferences.getString("right_long_press_action", StemAction.CYCLE_NOISE_CONTROL_MODES.name)

    val leftActionText = when (StemAction.valueOf(leftAction ?: StemAction.CYCLE_NOISE_CONTROL_MODES.name)) {
        StemAction.CYCLE_NOISE_CONTROL_MODES -> stringResource(R.string.noise_control)
        StemAction.DIGITAL_ASSISTANT -> "Digital Assistant"
        else -> "INVALID!!"
    }

    val rightActionText = when (StemAction.valueOf(rightAction ?: StemAction.CYCLE_NOISE_CONTROL_MODES.name)) {
        StemAction.CYCLE_NOISE_CONTROL_MODES -> stringResource(R.string.noise_control)
        StemAction.DIGITAL_ASSISTANT -> "Digital Assistant"
        else -> "INVALID!!"
    }
    Box(
        modifier = Modifier
            .background(if (isDarkTheme) Color(0xFF000000) else Color(0xFFF2F2F7))
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ){
        Text(
            text = stringResource(R.string.press_and_hold_airpods),
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textColor.copy(alpha = 0.6f),
                fontFamily = FontFamily(Font(R.font.sf_pro))
            )
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF), RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
    ) {
        NavigationButton(
            to = "long_press/Left",
            name = stringResource(R.string.left),
            navController = navController,
            independent = false,
            currentState = leftActionText,
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = dividerColor,
            modifier = Modifier
                .padding(horizontal = 16.dp)
        )
        NavigationButton(
            to = "long_press/Right",
            name = stringResource(R.string.right),
            navController = navController,
            independent = false,
            currentState = rightActionText,
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PressAndHoldSettingsPreview() {
    PressAndHoldSettings(navController = NavController(LocalContext.current))
}
