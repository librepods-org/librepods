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

import android.content.Context.MODE_PRIVATE
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.utils.AACPManager
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun ConnectionSettings() {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(28.dp))
            .padding(top = 2.dp)
    ) {
        StyledToggle(
            label = stringResource(R.string.ear_detection),
            controlCommandIdentifier = AACPManager.Companion.ControlCommandIdentifiers.EAR_DETECTION_CONFIG,
            sharedPreferenceKey = "automatic_ear_detection",
            sharedPreferences = LocalContext.current.getSharedPreferences("settings", MODE_PRIVATE),
            independent = false
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = Color(0x40888888),
            modifier = Modifier
                .padding(horizontal = 12.dp)
        )

        StyledToggle(
            label = stringResource(R.string.automatically_connect),
            description = stringResource(R.string.automatically_connect_description),
            controlCommandIdentifier = AACPManager.Companion.ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG,
            sharedPreferenceKey = "automatic_connection_ctrl_cmd",
            sharedPreferences = LocalContext.current.getSharedPreferences("settings", MODE_PRIVATE),
            independent = false
        )
    }
}

@Preview
@Composable
fun ConnectionSettingsPreview() {
    ConnectionSettings()
}
