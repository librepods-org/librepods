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

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.NavigationButton
import me.kavishdevar.librepods.composables.StyledButton
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.composables.StyledSlider
import me.kavishdevar.librepods.composables.StyledToggle
import me.kavishdevar.librepods.viewmodel.AppSettingsViewModel

@Composable
fun AppSettingsScreen(
    navController: NavController,
    viewModel: AppSettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val uiState by viewModel.uiState.collectAsState()

    val backdrop = rememberLayerBackdrop()

    StyledScaffold(
        title = stringResource(R.string.app_settings)
    ) { spacerHeight, hazeState ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .hazeSource(state = hazeState)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(spacerHeight))

            val isDarkTheme = isSystemInDarkTheme()
            val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
            val textColor = if (isDarkTheme) Color.White else Color.Black

            if (!uiState.isPremium) {
                StyledButton(
                    onClick = {
                        viewModel.purchase(context)
                    },
                    backdrop = rememberLayerBackdrop(),
                    modifier = Modifier.fillMaxWidth(),
                    maxScale = 0.05f,
                    tint = Color(0xFF916100)
                ) {
                    Text(
                        stringResource(R.string.unlock_all_features),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            color = textColor
                        ),
                    )
                }
            }

            StyledToggle(
                title = stringResource(R.string.widget),
                label = stringResource(R.string.show_phone_battery_in_widget),
                description = stringResource(R.string.show_phone_battery_in_widget_description),
                checked = uiState.showPhoneBatteryInWidget,
                onCheckedChange = viewModel::setShowPhoneBatteryInWidget,
                enabled = uiState.isPremium
            )

            Text(
                text = stringResource(R.string.conversational_awareness), style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ), modifier = Modifier.padding(16.dp, bottom = 2.dp, top = 24.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundColor, RoundedCornerShape(28.dp)
                    )
                    .padding(vertical = 4.dp)
            ) {
                StyledToggle(
                    label = stringResource(R.string.conversational_awareness_pause_music),
                    description = stringResource(R.string.conversational_awareness_pause_music_description),
                    checked = uiState.conversationalAwarenessPauseMusicEnabled,
                    onCheckedChange = viewModel::setConversationalAwarenessPauseMusicEnabled,
                    independent = false,
                    enabled = uiState.isPremium
                )

                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                StyledToggle(
                    label = stringResource(R.string.relative_conversational_awareness_volume),
                    description = stringResource(R.string.relative_conversational_awareness_volume_description),
                    checked = uiState.relativeConversationalAwarenessVolumeEnabled,
                    onCheckedChange = viewModel::setRelativeConversationalAwarenessVolumeEnabled,
                    independent = false,
                    enabled = uiState.isPremium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val conversationalAwarenessVolume = uiState.conversationalAwarenessVolume
            LaunchedEffect(conversationalAwarenessVolume) {
                viewModel.setConversationalAwarenessVolume(conversationalAwarenessVolume)
            }

            StyledSlider(
                label = stringResource(R.string.conversational_awareness_volume),
                value = conversationalAwarenessVolume,
                valueRange = 10f..85f,
                startLabel = "10%",
                endLabel = "85%",
                onValueChange = { newValue -> viewModel.setConversationalAwarenessVolume(newValue) },
                independent = true,
                enabled = uiState.isPremium
            )

            Spacer(modifier = Modifier.height(16.dp))

            NavigationButton(
                to = "",
                title = stringResource(R.string.camera_control),
                name = stringResource(R.string.set_custom_camera_package),
                navController = navController,
                onClick = {
                    if (uiState.isPremium) viewModel.setShowCameraDialog(true)
                },
                independent = true,
                description = stringResource(R.string.camera_control_app_description)
            )

            Spacer(modifier = Modifier.height(16.dp))
            if (BuildConfig.FLAVOR == "xposed") {
                StyledToggle(
                    title = stringResource(R.string.ear_detection),
                    label = stringResource(R.string.disconnect_when_not_wearing),
                    description = stringResource(R.string.disconnect_when_not_wearing_description),
                    checked = uiState.disconnectWhenNotWearing,
                    onCheckedChange = viewModel::setDisconnectWhenNotWearing,
                    enabled = uiState.isPremium
                )
            }

            Text(
                text = stringResource(R.string.takeover_airpods_state), style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ), modifier = Modifier.padding(16.dp, bottom = 2.dp, top = 24.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundColor, RoundedCornerShape(28.dp)
                    )
                    .padding(vertical = 4.dp)
            ) {
                StyledToggle(
                    label = stringResource(R.string.takeover_disconnected),
                    description = stringResource(R.string.takeover_disconnected_desc),
                    checked = uiState.takeoverWhenDisconnected,
                    onCheckedChange = viewModel::setTakeoverWhenDisconnected,
                    independent = false,
                    enabled = uiState.isPremium
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                StyledToggle(
                    label = stringResource(R.string.takeover_idle),
                    description = stringResource(R.string.takeover_idle_desc),
                    checked = uiState.takeoverWhenIdle,
                    onCheckedChange = viewModel::setTakeoverWhenIdle,
                    independent = false,
                    enabled = uiState.isPremium
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                StyledToggle(
                    label = stringResource(R.string.takeover_music),
                    description = stringResource(R.string.takeover_music_desc),
                    checked = uiState.takeoverWhenMusic,
                    onCheckedChange = viewModel::setTakeoverWhenMusic,
                    independent = false,
                    enabled = uiState.isPremium
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                StyledToggle(
                    label = stringResource(R.string.takeover_call),
                    description = stringResource(R.string.takeover_call_desc),
                    checked = uiState.takeoverWhenCall,
                    onCheckedChange = viewModel::setTakeoverWhenCall,
                    independent = false,
                    enabled = uiState.isPremium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.takeover_phone_state), style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ), modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundColor, RoundedCornerShape(28.dp)
                    )
                    .padding(vertical = 4.dp)
            ) {
                StyledToggle(
                    label = stringResource(R.string.takeover_ringing_call),
                    description = stringResource(R.string.takeover_ringing_call_desc),
                    checked = uiState.takeoverWhenRingingCall,
                    onCheckedChange = viewModel::setTakeoverWhenRingingCall,
                    independent = false,
                    enabled = uiState.isPremium
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                StyledToggle(
                    label = stringResource(R.string.takeover_media_start),
                    description = stringResource(R.string.takeover_media_start_desc),
                    checked = uiState.takeoverWhenMediaStart,
                    onCheckedChange = viewModel::setTakeoverWhenMediaStart,
                    independent = false,
                    enabled = uiState.isPremium
                )
            }

            Text(
                text = stringResource(R.string.advanced_options), style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ), modifier = Modifier.padding(16.dp, bottom = 2.dp, top = 24.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            StyledToggle(
                label = stringResource(R.string.use_alternate_head_tracking_packets),
                description = stringResource(R.string.use_alternate_head_tracking_packets_description),
                checked = uiState.useAlternateHeadTrackingPackets,
                onCheckedChange = viewModel::setUseAlternateHeadTrackingPackets,
                independent = true,
                enabled = uiState.isPremium
            )

            Spacer(modifier = Modifier.height(16.dp))

//            NavigationButton(
//                to = "troubleshooting",
//                name = stringResource(R.string.troubleshooting),
//                navController = navController,
//                independent = true,
//                description = stringResource(R.string.troubleshooting_description)
//            )

            Spacer(modifier = Modifier.height(16.dp))

            NavigationButton(
                to = "open_source_licenses",
                name = stringResource(R.string.open_source_licenses),
                navController = navController,
                independent = true
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (uiState.showCameraDialog) {
                AlertDialog(onDismissRequest = { viewModel.setShowCameraDialog(false) }, title = {
                    Text(
                        stringResource(R.string.set_custom_camera_package),
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        fontWeight = FontWeight.Medium
                    )
                }, text = {
                    Column {
                        Text(
                            stringResource(R.string.enter_custom_camera_package),
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        OutlinedTextField(
                            value = uiState.cameraPackageValue,
                            onValueChange = {
                                viewModel.setCameraPackageValue(it)
                                viewModel.setCameraPackageError(null)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            isError = uiState.cameraPackageError != null,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                capitalization = KeyboardCapitalization.None
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (isDarkTheme) Color(0xFF007AFF) else Color(
                                    0xFF3C6DF5
                                ),
                                unfocusedBorderColor = if (isDarkTheme) Color.Gray else Color.LightGray
                            ),
                            supportingText = {
                                if (uiState.cameraPackageError != null) {
                                    Text(
                                        uiState.cameraPackageError ?: "",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            label = { Text(stringResource(R.string.custom_camera_package)) })
                    }
                }, confirmButton = {
                    val successText = stringResource(R.string.custom_camera_package_set_success)
                    TextButton(
                        onClick = {
                            viewModel.saveCameraPackage()
                            Toast.makeText(context, successText, Toast.LENGTH_SHORT).show()
                        }) {
                        Text(
                            "Save",
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }, dismissButton = {
                    TextButton(
                        onClick = { viewModel.setShowCameraDialog(false) }) {
                        Text(
                            "Cancel",
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            fontWeight = FontWeight.Medium
                        )
                    }
                })
            }
        }
    }
}
