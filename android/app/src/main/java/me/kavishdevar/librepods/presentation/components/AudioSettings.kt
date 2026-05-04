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

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import me.kavishdevar.librepods.R
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun AudioSettings(
    navController: NavController,
    adaptiveVolumeCapability: Boolean,
    conversationalAwarenessCapability: Boolean,
    loudSoundReductionCapability: Boolean,
    adaptiveAudioCapability: Boolean,

    adaptiveVolumeChecked: Boolean,
    onAdaptiveVolumeCheckedChange: (Boolean) -> Unit,

    conversationalAwarenessChecked: Boolean,
    onConversationalAwarenessCheckedChange: (Boolean) -> Unit,

    loudSoundReductionChecked: Boolean,
    onLoudSoundReductionCheckedChange: (Boolean) -> Unit,

    vendorIdHook: Boolean,
    isPremium: Boolean
) {
    val isDarkTheme = isSystemInDarkTheme()
    val textColor = if (isDarkTheme) Color.White else Color.Black

    if (!adaptiveVolumeCapability && !conversationalAwarenessCapability && !loudSoundReductionCapability && !adaptiveAudioCapability) {
        return
    }
    Box(
        modifier = Modifier
            .background(if (isDarkTheme) Color(0xFF000000) else Color(0xFFF2F2F7))
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ){
        Text(
            text = stringResource(R.string.audio),
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textColor.copy(alpha = 0.6f),
                fontFamily = FontFamily(Font(R.font.sf_pro))
            )
        )
    }

    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(28.dp))
            .padding(top = 2.dp)
    ) {

        if (adaptiveVolumeCapability) {
            StyledToggle(
                label = stringResource(R.string.personalized_volume),
                description = stringResource(R.string.personalized_volume_description),
                independent = false,
                checked = adaptiveVolumeChecked,
                onCheckedChange = onAdaptiveVolumeCheckedChange,
                enabled = isPremium
            )

            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0x40888888),
                modifier = Modifier
                    .padding(horizontal = 12.dp)
            )
        }

        if (conversationalAwarenessCapability) {
            StyledToggle(
                label = stringResource(R.string.conversational_awareness),
                description = stringResource(R.string.conversational_awareness_description),
                independent = false,
                checked = conversationalAwarenessChecked,
                onCheckedChange = onConversationalAwarenessCheckedChange,
                enabled = isPremium
            )
            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0x40888888),
                modifier = Modifier
                    .padding(horizontal = 12.dp)
            )
        }

        if (loudSoundReductionCapability && vendorIdHook){
            StyledToggle(
                label = stringResource(R.string.loud_sound_reduction),
                description = stringResource(R.string.loud_sound_reduction_description),
                independent = false,
                checked = loudSoundReductionChecked,
                onCheckedChange = onLoudSoundReductionCheckedChange,
                enabled = isPremium
            )
            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0x40888888),
                modifier = Modifier
                    .padding(horizontal = 12.dp)
            )
        }

        if (adaptiveAudioCapability) {
            NavigationButton(
                to = "adaptive_strength",
                name = stringResource(R.string.adaptive_audio),
                navController = navController,
                independent = false
            )
        }
    }
}

@Preview
@Composable
fun AudioSettingsPreview() {
    AudioSettings(
        navController = rememberNavController(),
        adaptiveVolumeCapability = true,
        conversationalAwarenessCapability = true,
        loudSoundReductionCapability = true,
        adaptiveAudioCapability = true,
        adaptiveVolumeChecked = true,
        onAdaptiveVolumeCheckedChange = { },
        conversationalAwarenessChecked = true,
        onConversationalAwarenessCheckedChange = { },
        loudSoundReductionChecked = true,
        onLoudSoundReductionCheckedChange = { },
        vendorIdHook = true,
        isPremium = true
    )
}
