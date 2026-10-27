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

package me.kavishdevar.librepods.presentation.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
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
import dev.chrisbanes.haze.HazeInput
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.common.AppInfoCard
import me.kavishdevar.librepods.presentation.components.common.DeviceInfoCard
import me.kavishdevar.librepods.presentation.components.primitives.StyledBottomSheet
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledConfirmationDialog
import me.kavishdevar.librepods.presentation.components.primitives.StyledIconButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledInputField
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.utils.XposedState
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

@Composable
fun AppSettingsScreen(
    viewModel: AppSettingsViewModel = viewModel(),
    navigateBack: (() -> Unit)?,
    navigateToPurchase: () -> Unit,
    navigateToOpenSourceLicenses: () -> Unit,
    navigateToReleaseNotesScreen: () -> Unit,
    navigateToBleSettingsScreen: () -> Unit,
    navigateToAppAppearanceScreen: () -> Unit,
    navigateToAppAccessibilityScreen: () -> Unit,
    navigateToIslandSettingsScreen: () -> Unit,
    navigateToProxCardSizeAndPosition: () -> Unit,
    navigateToProxCardCustomize: () -> Unit
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
                .layerBackdrop(backdrop)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            Spacer(modifier = Modifier.height(20.dp))

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
                StyledListItem(
                    contentText = stringResource(R.string.appearance),
                    onClick = navigateToAppAppearanceScreen
                )

                Spacer(modifier = Modifier.height(16.dp))

                StyledList(title = stringResource(R.string.interaction)) {
                    styledToggle(
                        label = stringResource(R.string.swipe_anywhere_to_go_back),
                        checked = state.settings.swipeAnywhereForBack,
                        onCheckedChange = { checked ->
                            viewModel.updateSettings {
                                it.copy(swipeAnywhereForBack = checked)
                            }
                        },
                    )

                    styledToggle(
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

            StyledListItem(
                contentText = stringResource(R.string.accessibility),
                onClick = navigateToAppAccessibilityScreen
            )

            if (state.state.hasConnectedToAACP) {
                Spacer(modifier = Modifier.height(16.dp))

                StyledListItem(
                    contentText = stringResource(R.string.island_settings),
                    onClick = navigateToIslandSettingsScreen
                )

                Spacer(modifier = Modifier.height(16.dp))

                StyledList(
                    title = stringResource(R.string.proximity_card),
                    description = stringResource(R.string.proximity_card_description)
                ) {
                    styledListItem(
                        contentText = stringResource(R.string.size_and_position),
                        onClick = navigateToProxCardSizeAndPosition
                    )
                    styledListItem(
                        contentText = stringResource(R.string.customize),
                        onClick = navigateToProxCardCustomize
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
                    styledToggle(
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

                styledToggle(
                    label = stringResource(R.string.enable_debug_mode),
                    description = stringResource(R.string.debug_mode_description),
                    checked = state.settings.debugMode,
                    onCheckedChange = { checked ->
                        viewModel.updateSettings {
                            it.copy(debugMode = checked)
                        }
                    }
                )

                styledListItem(
                    contentText = stringResource(R.string.ble_settings),
                    orientation = StyledListItemOrientation.Vertical,
                    onClick = navigateToBleSettingsScreen
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StyledList(title = stringResource(R.string.contact)) {
                styledListItem(
                    contentText =  stringResource(R.string.email),
                    supportingText = stringResource(R.string.contact_email_supporting_text),
                    orientation = StyledListItemOrientation.Vertical,
                    onClick = { contactBottomSheet.value = true },
                )

                val errorOpeningDiscordInviteText = stringResource(R.string.error_opening_discord_invite)

                styledListItem(
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

                styledListItem(
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

            Spacer(modifier = Modifier.height(16.dp))

            StyledListItem(
                contentText =  stringResource(R.string.reset_app_settings),
                onClick = viewModel::showResetDialog,
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

    val showResetDialog = remember(state.showResetDialog) { mutableStateOf(state.showResetDialog) }

    StyledConfirmationDialog(
        showDialog = showResetDialog,
        title = stringResource(R.string.reset_app_settings),
        message = stringResource(R.string.reset_app_settings_description),
        confirmText = stringResource(R.string.reset),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            viewModel.resetAppSettings {
                Process.killProcess(Process.myPid())
                exitProcess(0)
            }
        },
        onDismiss = viewModel::resetDialogDismissed,
        backdrop = backdrop
    )

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
                   hazeInput = HazeInput.Content, // TODO: migrate to haze
                   onClick = { contactBottomSheet.value = false }
               ) {
                   Icon(
                       imageVector = LocalIcons.current.Close,
                       contentDescription = stringResource(R.string.close),
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
                   hazeInput = HazeInput.Content, // TODO: migrate to haze
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
                       contentDescription = stringResource(R.string.send),
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
