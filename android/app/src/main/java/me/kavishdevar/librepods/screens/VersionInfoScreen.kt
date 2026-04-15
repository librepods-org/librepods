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

package me.kavishdevar.librepods.screens

import androidx.compose.foundation.background
import android.annotation.SuppressLint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.Job
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.services.ServiceManager
import kotlin.io.encoding.ExperimentalEncodingApi

private var debounceJob: Job? = null

@SuppressLint("DefaultLocale")
@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun VersionScreen(navController: NavController) {
    val isDarkTheme = isSystemInDarkTheme()
    val service = ServiceManager.getService()
    if (service == null) return
    val airpodsInstance = service.airpodsInstance
    if (airpodsInstance == null) return

    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isDarkTheme) Color.White else Color.Black

    val backdrop = rememberLayerBackdrop()

    StyledScaffold(
        title = stringResource(R.string.customize_adaptive_audio)
    ) { spacerHeight ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(spacerHeight))
            Box(
                modifier = Modifier
                    .background(if (isDarkTheme) Color(0xFF000000) else Color(0xFFF2F2F7))
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ){
                Text(
                    text = stringResource(R.string.version),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor.copy(alpha = 0.6f)
                    )
                )
            }

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
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.version) + " 1",
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = textColor,
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    )
                    Text(
                        text = airpodsInstance.version1 ?: "N/A",
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = textColor.copy(0.8f),
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
                        text = stringResource(R.string.version) + " 2",
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = textColor,
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    )
                    Text(
                        text = airpodsInstance.version2 ?: "N/A",
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = textColor.copy(0.8f),
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
                        text = stringResource(R.string.version) + " 3",
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = textColor,
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    )
                    Text(
                        text = airpodsInstance.version3 ?: "N/A",
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = textColor.copy(0.8f),
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    )
                }
            }
        }
    }
}