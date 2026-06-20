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

package me.kavishdevar.librepods.presentation.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.AppInfoCard
import me.kavishdevar.librepods.presentation.components.DeviceInfoCard
import me.kavishdevar.librepods.presentation.components.StyledBottomSheet
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledIconButton
import me.kavishdevar.librepods.presentation.components.StyledInputField
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.components.StyledSlider
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.theme.MaterialTypography
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.utils.XposedState
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    viewModel: AppSettingsViewModel = viewModel(),
    navigateToPurchase: () -> Unit,
    navigateToTroubleshooting: () -> Unit,
    navigateToOpenSourceLicenses: () -> Unit,
    navigateToReleaseNotesScreen: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val state by viewModel.uiState.collectAsState()

    val backdrop = rememberLayerBackdrop()

    val contactBottomSheet = remember { mutableStateOf(false) }
    val subjectState = remember { TextFieldState() }
    val descriptionState = remember { TextFieldState() }
    val subjectFocusRequester = remember { FocusRequester() }
    val descriptionFocusRequester = remember { FocusRequester() }

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 16.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .layerBackdrop(backdrop)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        val isDarkTheme = isSystemInDarkTheme()

        if (!state.isPremium && state.connectionSuccessful) {
            StyledButton(
                onClick = navigateToPurchase,
                backdrop = rememberLayerBackdrop(),
                modifier = Modifier.fillMaxWidth(),
                maxScale = 0.05f,
                surfaceColor = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    stringResource(R.string.unlock_advanced_features),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        if (state.timeUntilFOSSPremiumExpiry > 0L) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF32829B), RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .clickable {
                        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                            data = "mailto:".toUri()
                            putExtra(Intent.EXTRA_EMAIL, arrayOf("billing@kavish.xyz"))
                            putExtra(Intent.EXTRA_SUBJECT, "LibrePods Play billing error")
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Please enter your GitHub username to restore your premium access:\n\nGitHub username: "
                            )
                        }
                        context.startActivity(emailIntent)
                    }
            ) {
                Text(
                    text = stringResource(
                        R.string.play_foss_premium_banner, maxOf(1, TimeUnit.MILLISECONDS.toDays(state.timeUntilFOSSPremiumExpiry).toInt())
                    ),
                    modifier = Modifier
                        .padding(16.dp),
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
            }
        }

        StyledToggle(
            title = stringResource(R.string.appearance),
            label = stringResource(R.string.use_material3e),
            checked = state.m3eEnabled,
            onCheckedChange = viewModel::setm3eEnabled,
//                enabled = state.isPremium
        )

        if (state.connectionSuccessful) {
            StyledToggle(
                title = stringResource(R.string.widget),
                label = stringResource(R.string.show_phone_battery_in_widget),
                description = stringResource(R.string.show_phone_battery_in_widget_description),
                checked = state.showPhoneBatteryInWidget,
                onCheckedChange = viewModel::setShowPhoneBatteryInWidget,
                enabled = state.isPremium
            )

            StyledList(title = stringResource(R.string.popup_animations)) {
                StyledToggle(
                    label = stringResource(R.string.show_bottom_sheet_popup),
                    description = stringResource(R.string.show_bottom_sheet_popup_description),
                    checked = state.showBottomSheetPopup,
                    onCheckedChange = viewModel::setShowBottomSheetPopup,
                )

                StyledToggle(
                    label = stringResource(R.string.show_island_popup),
                    description = stringResource(R.string.show_island_popup_description),
                    checked = state.showIslandPopup,
                    onCheckedChange = viewModel::setShowIslandPopup,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StyledList (title = stringResource(R.string.conversational_awareness)) {
                StyledToggle(
                    label = stringResource(R.string.conversational_awareness_pause_music),
                    description = stringResource(R.string.conversational_awareness_pause_music_description),
                    checked = state.conversationalAwarenessPauseMusicEnabled,
                    onCheckedChange = viewModel::setConversationalAwarenessPauseMusicEnabled,
                    enabled = state.isPremium
                )

                StyledToggle(
                    label = stringResource(R.string.relative_conversational_awareness_volume),
                    description = stringResource(R.string.relative_conversational_awareness_volume_description),
                    checked = state.relativeConversationalAwarenessVolumeEnabled,
                    onCheckedChange = viewModel::setRelativeConversationalAwarenessVolumeEnabled,
                    enabled = state.isPremium,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val conversationalAwarenessVolume = state.conversationalAwarenessVolume
            LaunchedEffect(conversationalAwarenessVolume) {
                viewModel.setConversationalAwarenessVolume(conversationalAwarenessVolume)
            }

            StyledSlider(
                label = stringResource(R.string.conversational_awareness_volume),
                value = conversationalAwarenessVolume,
                valueRange = 10f..85f,
                snapPoints = listOf(44f),
                startLabel = "10%",
                endLabel = "85%",
                onValueChange = { newValue ->
                    viewModel.setConversationalAwarenessVolume(
                        newValue
                    )
                },
                independent = true,
                enabled = state.isPremium
            )

//            if (!BuildConfig.PLAY_BUILD) {
//                Spacer(modifier = Modifier.height(16.dp))
//
//                StyledListItem(
//                    to = "",
//                    titleRes = stringResource(R.string.camera_control),
//                    name = stringResource(R.string.set_custom_camera_package),
//                    navController = navController,
//                    onClick = {
//                        if (state.isPremium) viewModel.setShowCameraDialog(true)
//                    },
//                    independent = true,
//                    descriptionRes = stringResource(R.string.camera_control_app_description)
//                )
//            }

            Spacer(modifier = Modifier.height(16.dp))
            if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
                StyledToggle(
                    title = stringResource(R.string.ear_detection),
                    label = stringResource(R.string.disconnect_when_not_wearing),
                    description = stringResource(R.string.disconnect_when_not_wearing_description),
                    checked = state.disconnectWhenNotWearing,
                    onCheckedChange = viewModel::setDisconnectWhenNotWearing,
                    enabled = state.isPremium
                )
            }

            StyledList(title = stringResource(R.string.takeover_airpods_state)) {
                StyledToggle(
                    label = stringResource(R.string.takeover_disconnected),
                    description = stringResource(R.string.takeover_disconnected_desc),
                    checked = state.takeoverWhenDisconnected,
                    onCheckedChange = viewModel::setTakeoverWhenDisconnected,
                    enabled = state.isPremium
                )
                StyledToggle(
                    label = stringResource(R.string.takeover_idle),
                    description = stringResource(R.string.takeover_idle_desc),
                    checked = state.takeoverWhenIdle,
                    onCheckedChange = viewModel::setTakeoverWhenIdle,
                    enabled = state.isPremium
                )
                StyledToggle(
                    label = stringResource(R.string.takeover_music),
                    description = stringResource(R.string.takeover_music_desc),
                    checked = state.takeoverWhenMusic,
                    onCheckedChange = viewModel::setTakeoverWhenMusic,
                    enabled = state.isPremium
                )

                StyledToggle(
                    label = stringResource(R.string.takeover_call),
                    description = stringResource(R.string.takeover_call_desc),
                    checked = state.takeoverWhenCall,
                    onCheckedChange = viewModel::setTakeoverWhenCall,
                    enabled = state.isPremium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StyledList(title = stringResource(R.string.takeover_phone_state)) {
                StyledToggle(
                    label = stringResource(R.string.takeover_ringing_call),
                    description = stringResource(R.string.takeover_ringing_call_desc),
                    checked = state.takeoverWhenRingingCall,
                    onCheckedChange = viewModel::setTakeoverWhenRingingCall,
                    enabled = state.isPremium
                )
                StyledToggle(
                    label = stringResource(R.string.takeover_media_start),
                    description = stringResource(R.string.takeover_media_start_desc),
                    checked = state.takeoverWhenMediaStart,
                    onCheckedChange = viewModel::setTakeoverWhenMediaStart,
                    enabled = state.isPremium
                )
            }

            StyledToggle(
                title = stringResource(R.string.advanced_options), // shouldn't be here, but okay
                label = stringResource(R.string.use_alternate_head_tracking_packets),
                description = stringResource(R.string.use_alternate_head_tracking_packets_description),
                checked = state.useAlternateHeadTrackingPackets,
                onCheckedChange = viewModel::setUseAlternateHeadTrackingPackets,
                enabled = state.isPremium
            )
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 2.dp)
            ) {
                Text(
                    text = stringResource(R.string.customizations_unavailable),
                    style = MaterialTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                )
            }
        }

        if (XposedState.isAvailable && XposedState.bluetoothScopeEnabled) {
            val restartBluetoothText = stringResource(R.string.found_offset_restart_bluetooth)
            StyledToggle(
                label = stringResource(R.string.act_as_an_apple_device) + " (${
                    stringResource(
                        R.string.requires_xposed
                    )
                })",
                description = stringResource(R.string.act_as_an_apple_device_description),
                checked = state.vendorIdHook,
                onCheckedChange = { enabled ->
                    Toast.makeText(context, restartBluetoothText, Toast.LENGTH_SHORT).show()
                    viewModel.setVendorIdHook(enabled)
                }
            )
        }

        if (!BuildConfig.PLAY_BUILD) {
            Spacer(modifier = Modifier.height(16.dp))
            StyledList {
                StyledListItem(
                    name = stringResource(R.string.troubleshooting),
                    onClick = navigateToTroubleshooting,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        StyledList(title = stringResource(R.string.contact)) {
            StyledListItem(
                name = stringResource(R.string.email),
                onClick = { contactBottomSheet.value = true },
            )

            StyledListItem(
                name = stringResource(R.string.discord),
                onClick = {
                    val intent =
                        Intent(Intent.ACTION_VIEW, "https://discord.gg/Ts4wupXcmc".toUri())
                    context.startActivity(intent)
                },
            )

            StyledListItem(
                name = stringResource(R.string.github_issues),
                onClick = {
                    val appVersion =
                        Uri.encode("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    val device = Uri.encode("${Build.MANUFACTURER} ${Build.MODEL}")
                    val androidVersion = Uri.encode("${Build.ID} (${Build.DISPLAY})")
                    val appSource = Uri.encode(
                        when {
                            BuildConfig.PLAY_BUILD -> "Play"
                            else -> "GitHub"
                        }
                    )
                    val url = "https://github.com/kavishdevar/librepods/issues/new" +
                        "?template=01-bug-report-android.yml" +
                        "&app-source=$appSource" +
                        "&app-version=$appVersion" +
                        "&device=$device" +
                        "&android-version=$androidVersion"

                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                    context.startActivity(intent)
                },
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        DeviceInfoCard()
        Spacer(modifier = Modifier.height(16.dp))
        AppInfoCard(navigateToReleaseNotesScreen)

        Spacer(modifier = Modifier.height(16.dp))

        StyledListItem(
            name = stringResource(R.string.open_source_licenses),
            onClick = navigateToOpenSourceLicenses,
        )

        Spacer(modifier = Modifier.height(bottomPadding))

        if (state.showCameraDialog) {
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
                        value = state.cameraPackageValue,
                        onValueChange = {
                            viewModel.setCameraPackageValue(it)
                            viewModel.setCameraPackageError(null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        isError = state.cameraPackageError != null,
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
                            if (state.cameraPackageError != null) {
                                Text(
                                    state.cameraPackageError ?: "",
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

    StyledBottomSheet(
        visible = contactBottomSheet.value,
        onDismiss = { contactBottomSheet.value = false },
        backdrop = backdrop
    ) { innerBackdrop, progress ->
        val animatedPadding = lerp(16.dp, 2.dp, progress)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = animatedPadding)
                .padding(bottom = 16.dp),
        ) {
           Row(
               modifier = Modifier
                   .fillMaxWidth()
                   .padding(bottom = 16.dp),
               horizontalArrangement = Arrangement.SpaceBetween,
               verticalAlignment = Alignment.CenterVertically
           ) {
               StyledIconButton(
                   icon = "\uDBC0\uDD84",
                   backdrop = innerBackdrop,
                   onClick = { contactBottomSheet.value = false }
               )
               Text (
                   text = stringResource(R.string.describe_your_issue),
                   style = TextStyle(
                       fontSize = 18.sp,
                       fontFamily = FontFamily(Font(R.font.sf_pro)),
                       fontWeight = FontWeight.Bold,
                       textAlign = TextAlign.Center,
                       color = if (isSystemInDarkTheme()) Color.White else Color.Black
                   )
               )
               StyledIconButton(
                   icon = "\uDBC0\uDE1F",
                   backdrop = innerBackdrop,
                   surfaceColor = if (isSystemInDarkTheme()) Color(0xFF0091FF) else Color(0xFF0088FF),
                   iconTint = if (subjectState.text.isNotEmpty() && descriptionState.text.isNotEmpty()) Color.White else Color.Gray,
                   enabled = subjectState.text.isNotEmpty() && descriptionState.text.isNotEmpty(),
                   onClick = {
                       contactBottomSheet.value = false
                       val intent = Intent(Intent.ACTION_SENDTO).apply {
                           data = "mailto:".toUri()
                           putExtra(Intent.EXTRA_EMAIL, arrayOf("contact@kavish.xyz"))
                           putExtra(Intent.EXTRA_SUBJECT, "LibrePods: ${subjectState.text}")
                           putExtra(
                               Intent.EXTRA_TEXT,
                               "${descriptionState.text}" +
                                   "\n\n----------" +
                                   "\nPhone details:" +
                                   "\nMANUFACTURER: ${Build.MANUFACTURER}" +
                                   "\nMODEL: ${Build.MODEL} (${Build.PRODUCT})" +
                                   "\nDISPLAY_VERSION: ${Build.DISPLAY}" +
                                   "\nID: ${Build.ID} (SDK ${Build.VERSION.SDK_INT_FULL})" +
                                   "\nXposed enabled/active: ${XposedState.isAvailable}/${XposedState.bluetoothScopeEnabled}" +
                                   "\n\nApp details:" +
                                   "\nVERSION: ${BuildConfig.VERSION_NAME}" +
                                   "\nVERSION_CODE: ${BuildConfig.VERSION_CODE}" +
                                   "\nFLAVOR: ${BuildConfig.FLAVOR}" +
                                   "\nBUILD_TYPE: ${BuildConfig.BUILD_TYPE}"
                           )
                       }
                       context.startActivity(intent)
                       subjectState.clearText()
                       descriptionState.clearText()
                   }
               )
           }

           Spacer(modifier = Modifier.height(8.dp))

           StyledInputField(
               inputState = subjectState,
               focusRequester = subjectFocusRequester,
               placeholder = stringResource(R.string.subject),
               forceApple = true
           )

           Spacer(modifier = Modifier.height(12.dp))

           StyledInputField(
               inputState = descriptionState,
               focusRequester = descriptionFocusRequester,
               placeholder = stringResource(R.string.describe_your_issue),
               singleLine = false,
               forceApple = true
           )
        }
    }
}
