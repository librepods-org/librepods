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


import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.R

@Composable
fun BatteryIndicator(
    batteryPercentage: Int,
    charging: Boolean = false,
    prefix: String = "",
    previousCharging: Boolean = false,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color.Black else Color(0xFFF2F2F7)
    val batteryTextColor = if (isDarkTheme) Color.White else Color.Black
    val batteryFillColor = if (batteryPercentage > 25)
        if (isDarkTheme) Color(0xFF2ED158) else Color(0xFF35C759)
        else if (isDarkTheme) Color(0xFFFC4244) else Color(0xFFfe373C)

    val initialScale = if (previousCharging) 1f else 0f
    val scaleAnim = remember { Animatable(initialScale) }
    val targetScale = if (charging) 1f else 0f

    LaunchedEffect(previousCharging, charging) {
        scaleAnim.animateTo(targetScale, animationSpec = tween(durationMillis = 250))
    }

    Column(
        modifier = Modifier
            .background(backgroundColor), // just for haze to work
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.padding(bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { batteryPercentage / 100f },
                modifier = Modifier.size(40.dp),
                color = batteryFillColor,
                gapSize = 0.dp,
                strokeCap = StrokeCap.Round,
                strokeWidth = 4.dp,
                trackColor = if (isDarkTheme) Color(0xFF0E0E0F) else Color(0xFFE3E3E8)
            )

            Text(
                text = "\uDBC0\uDEE6",
                style = TextStyle(
                    fontSize = 12.sp,
                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                    color = batteryFillColor,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.scale(scaleAnim.value)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "$prefix $batteryPercentage%",
            color = batteryTextColor,
            style = TextStyle(
                fontSize = 16.sp,
                fontFamily = FontFamily(Font(R.font.sf_pro)),
                textAlign = TextAlign.Center
            ),
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BatteryIndicatorPreview() {
    val bg = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)
    Box(
        modifier = Modifier.background(bg)
    ) {
        BatteryIndicator(batteryPercentage = 24, charging = true, prefix = "\uDBC6\uDCE5", previousCharging = false)
    }
}
