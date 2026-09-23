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
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.common.AppInfoCard
import me.kavishdevar.librepods.presentation.components.common.DeviceInfoCard
import me.kavishdevar.librepods.presentation.components.primitives.StyledBottomSheet
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledIconButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledInputField
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.NightTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.utils.XposedState
import java.util.concurrent.TimeUnit

@Composable
fun AppSettingsScreen(
    viewModel: AppSettingsViewModel = viewModel(),
    navigateBack: (() -> Unit)?,
    navigateToPurchase: () -> Unit,
    navigateToOpenSourceLicenses: () -> Unit,
    navigateToReleaseNotesScreen: () -> Unit,
    navigateToBleSettingsScreen: () -> Unit
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

    StyledScaffold(
        title = stringResource(R.string.settings),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .layerBackdrop(backdrop)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            if (!state.isPremium && state.state.hasConnectedToAACP) {
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

            if (state.state.timeUntilFOSSPremiumExpiry > 0L) {
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
                            R.string.play_foss_premium_banner, maxOf(1, TimeUnit.MILLISECONDS.toDays(state.state.timeUntilFOSSPremiumExpiry).toInt())
                        ),
                        modifier = Modifier
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyMediumEmphasized,
                        color = Color.White
                    )
                }
            }

            if (state.state.hasConnectedToAACP) {
                StyledList(title = stringResource(R.string.appearance)) {
                    StyledListItem(
                        contentText = stringResource(R.string.light),
                        selected = state.settings.nightMode == NightTheme.Light,
                        onClick = { viewModel.updateSettings { it.copy(nightMode = NightTheme.Light) } },
                        enabled = state.isPremium
                    )

                    StyledListItem(
                        contentText = stringResource(R.string.system),
                        selected = state.settings.nightMode == NightTheme.System,
                        onClick = { viewModel.updateSettings { it.copy(nightMode = NightTheme.System) } },
                        enabled = state.isPremium
                    )

                    StyledListItem(
                        contentText = stringResource(R.string.dark),
                        selected = state.settings.nightMode == NightTheme.Dark,
                        onClick = { viewModel.updateSettings { it.copy(nightMode = NightTheme.Dark) } },
                        enabled = state.isPremium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                StyledList(title = stringResource(R.string.design_system)) {
                    StyledListItem(
                        contentText = stringResource(R.string.apple),
                        selected = state.settings.designSystem == DesignSystem.Apple,
                        onClick = { viewModel.updateSettings { it.copy(designSystem = DesignSystem.Apple) } },
                        enabled = state.isPremium
                    )

                    StyledListItem(
                        contentText = stringResource(R.string.material3e),
                        selected = state.settings.designSystem == DesignSystem.Material,
                        onClick = { viewModel.updateSettings { it.copy(designSystem = DesignSystem.Material) } },
                        enabled = state.isPremium
                    )

                    StyledListItem(
                        contentText = stringResource(R.string.miuix),
                        selected = state.settings.designSystem == DesignSystem.Miuix,
                        onClick = { viewModel.updateSettings { it.copy(designSystem = DesignSystem.Miuix) } },
                        enabled = state.isPremium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                StyledList(title = stringResource(R.string.interaction)) {
                    StyledToggle(
                        label = stringResource(R.string.swipe_anywhere_to_go_back),
                        checked = state.settings.swipeAnywhereForBack,
                        onCheckedChange = { checked ->
                            viewModel.updateSettings {
                                it.copy(swipeAnywhereForBack = checked)
                            }
                        },
                    )

                    StyledToggle(
                        label = stringResource(R.string.use_highest_refresh_rate),
                        checked = state.settings.useHighestRefreshRate,
                        onCheckedChange = { checked ->
                            viewModel.updateSettings {
                                it.copy(useHighestRefreshRate = checked)
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            StyledList(
                title = stringResource(R.string.advanced_options),
                description = stringResource(R.string.do_not_change)
            ) {
                if (XposedState.isAvailable && XposedState.bluetoothScopeEnabled) {
                    val restartBluetoothText =
                        stringResource(R.string.found_offset_restart_bluetooth)
                    StyledToggle(
                        label = stringResource(R.string.act_as_an_apple_device) + " (${
                            stringResource(
                                R.string.requires_xposed
                            )
                        })",
                        description = stringResource(R.string.act_as_an_apple_device_description),
                        checked = state.vendorIdHook,
                        onCheckedChange = { checked ->
                            Toast.makeText(context, restartBluetoothText, Toast.LENGTH_SHORT).show()
                            viewModel.setVendorIdHook(checked)
                        }
                    )
                }

                StyledToggle(
                    label = stringResource(R.string.enable_debug_mode),
                    description = stringResource(R.string.debug_mode_description),
                    checked = state.settings.debugMode,
                    onCheckedChange = { checked ->
                        viewModel.updateSettings {
                            it.copy(debugMode = checked)
                        }
                    }
                )

                StyledListItem(
                    contentText = stringResource(R.string.ble_settings),
                    orientation = StyledListItemOrientation.Vertical,
                    onClick = navigateToBleSettingsScreen
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StyledList(title = stringResource(R.string.contact)) {
                StyledListItem(
                    contentText =  stringResource(R.string.email),
                    supportingText = stringResource(R.string.contact_email_supporting_text),
                    orientation = StyledListItemOrientation.Vertical,
                    onClick = { contactBottomSheet.value = true },
                )

                val errorOpeningDiscordInviteText = stringResource(R.string.error_opening_discord_invite)

                StyledListItem(
                    contentText =  stringResource(R.string.discord),
                    supportingText = stringResource(R.string.contact_discord_supporting_text),
                    orientation = StyledListItemOrientation.Vertical,
                    onClick = {
                        try {
                            val intent =
                                Intent(Intent.ACTION_VIEW, "https://discord.gg/Ts4wupXcmc".toUri())
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(
                                context,
                                errorOpeningDiscordInviteText,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                )

                val errorOpeningGitHubLink = stringResource(R.string.error_opening_github_link)

                StyledListItem(
                    contentText =  stringResource(R.string.github_issues),
                    supportingText = stringResource(R.string.contact_github_supporting_text),
                    orientation = StyledListItemOrientation.Vertical,
                    onClick = {
                        try {
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
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(
                                context,
                                errorOpeningGitHubLink,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            DeviceInfoCard()

            Spacer(modifier = Modifier.height(16.dp))

            AppInfoCard(navigateToReleaseNotesScreen)

            Spacer(modifier = Modifier.height(16.dp))

            StyledListItem(
                contentText =  stringResource(R.string.open_source_licenses),
                onClick = navigateToOpenSourceLicenses,
            )

            Spacer(modifier = Modifier.height(bottomPadding))

//        if (state.showCameraDialog) {
//            AlertDialog(onDismissRequest = { viewModel.setShowCameraDialog(false) }, title = {
//                Text(
//                    stringResource(R.string.set_custom_camera_package),
//                    style = MaterialTheme.typography.titleSmall,
//                )
//            }, text = {
//                Column {
//                    Text(
//                        stringResource(R.string.enter_custom_camera_package),
//                        style = MaterialTheme.typography.bodyMedium,
//                        modifier = Modifier.padding(bottom = 8.dp)
//                    )
//
//                    OutlinedTextField(
//                        value = state.cameraPackageValue,
//                        onValueChange = {
//                            viewModel.setCameraPackageValue(it)
//                            viewModel.setCameraPackageError(null)
//                        },
//                        modifier = Modifier.fillMaxWidth(),
//                        isError = state.cameraPackageError != null,
//                        keyboardOptions = KeyboardOptions(
//                            keyboardType = KeyboardType.Ascii,
//                            capitalization = KeyboardCapitalization.None
//                        ),
//                        colors = OutlinedTextFieldDefaults.colors(
//                            focusedBorderColor = if (isDarkTheme) Color(0xFF007AFF) else Color(
//                                0xFF3C6DF5
//                            ),
//                            unfocusedBorderColor = if (isDarkTheme) Color.Gray else Color.LightGray
//                        ),
//                        supportingText = {
//                            if (state.cameraPackageError != null) {
//                                Text(
//                                    state.cameraPackageError ?: "",
//                                    color = MaterialTheme.colorScheme.error
//                                )
//                            }
//                        },
//                        label = { Text(stringResource(R.string.custom_camera_package)) })
//                }
//            }, confirmButton = {
//                val successText = stringResource(R.string.custom_camera_package_set_success)
//                TextButton(
//                    onClick = {
//                        viewModel.saveCameraPackage()
//                        Toast.makeText(context, successText, Toast.LENGTH_SHORT).show()
//                    }) {
//                    Text(
//                        "Save",
//                        style = MaterialTheme.typography.labelMedium
//                    )
//                }
//            }, dismissButton = {
//                TextButton(
//                    onClick = { viewModel.setShowCameraDialog(false) }) {
//                    Text(
//                        "Cancel",
//                        style = MaterialTheme.typography.labelMedium
//                    )
//                }
//            })
//        }
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
                   backdrop = innerBackdrop,
                   onClick = { contactBottomSheet.value = false }
               ) {
                   Icon(
                       imageVector = LocalIcons.current.Close,
                       contentDescription = "Close",
                       tint = MaterialTheme.colorScheme.onBackground
                   )
               }
               Text (
                   text = stringResource(R.string.describe_your_issue),
                   style = MaterialTheme.typography.labelLargeEmphasized,
                   textAlign = TextAlign.Center,
                   color = MaterialTheme.colorScheme.onBackground
               )
               StyledIconButton(
                   backdrop = innerBackdrop,
                   surfaceColor = MaterialTheme.colorScheme.primary,
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
               ) {
                   Icon(
                       imageVector = LocalIcons.current.Send,
                       contentDescription = "Send",
                       tint = if (subjectState.text.isNotEmpty() && descriptionState.text.isNotEmpty()) Color.White else Color.Gray
                   )
               }
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
