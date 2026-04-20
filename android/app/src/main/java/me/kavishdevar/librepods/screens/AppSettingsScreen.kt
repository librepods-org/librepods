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

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
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
import androidx.core.content.edit
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.NavigationButton
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.composables.StyledSlider
import me.kavishdevar.librepods.composables.StyledToggle
import me.kavishdevar.librepods.utils.AACPManager
import me.kavishdevar.librepods.utils.RadareOffsetFinder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class, ExperimentalEncodingApi::class)
@Composable
fun AppSettingsScreen(navController: NavController) {
    val sharedPreferences = LocalContext.current.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val showResetDialog = remember { mutableStateOf(false) }
    val showIrkDialog = remember { mutableStateOf(false) }
    val showEncKeyDialog = remember { mutableStateOf(false) }
    val showCameraDialog = remember { mutableStateOf(false) }
    val irkValue = remember { mutableStateOf("") }
    val encKeyValue = remember { mutableStateOf("") }
    val cameraPackageValue = remember { mutableStateOf("") }
    val irkError = remember { mutableStateOf<String?>(null) }
    val encKeyError = remember { mutableStateOf<String?>(null) }
    val cameraPackageError = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val savedIrk = sharedPreferences.getString(AACPManager.Companion.ProximityKeyType.IRK.name, null)
        val savedEncKey = sharedPreferences.getString(AACPManager.Companion.ProximityKeyType.ENC_KEY.name, null)
        val savedCameraPackage = sharedPreferences.getString("custom_camera_package", null)

        if (savedIrk != null) {
            try {
                val decoded = Base64.decode(savedIrk)
                irkValue.value = decoded.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                irkValue.value = ""
                e.printStackTrace()
            }
        }

        if (savedEncKey != null) {
            try {
                val decoded = Base64.decode(savedEncKey)
                encKeyValue.value = decoded.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                encKeyValue.value = ""
                e.printStackTrace()
            }
        }
        if (savedCameraPackage != null) {
            cameraPackageValue.value = savedCameraPackage
        }
    }

    val showPhoneBatteryInWidget = remember {
        mutableStateOf(sharedPreferences.getBoolean("show_phone_battery_in_widget", true))
    }
    val conversationalAwarenessPauseMusicEnabled = remember {
        mutableStateOf(sharedPreferences.getBoolean("conversational_awareness_pause_music", false))
    }
    val relativeConversationalAwarenessVolumeEnabled = remember {
        mutableStateOf(sharedPreferences.getBoolean("relative_conversational_awareness_volume", true))
    }
    val openDialogForControlling = remember {
        mutableStateOf(sharedPreferences.getString("qs_click_behavior", "dialog") == "dialog")
    }
    val disconnectWhenNotWearing = remember {
        mutableStateOf(sharedPreferences.getBoolean("disconnect_when_not_wearing", false))
    }

    val takeoverWhenDisconnected = remember {
        mutableStateOf(sharedPreferences.getBoolean("takeover_when_disconnected", true))
    }
    val takeoverWhenIdle = remember {
        mutableStateOf(sharedPreferences.getBoolean("takeover_when_idle", true))
    }
    val takeoverWhenMusic = remember {
        mutableStateOf(sharedPreferences.getBoolean("takeover_when_music", false))
    }
    val takeoverWhenCall = remember {
        mutableStateOf(sharedPreferences.getBoolean("takeover_when_call", true))
    }

    val takeoverWhenRingingCall = remember {
        mutableStateOf(sharedPreferences.getBoolean("takeover_when_ringing_call", true))
    }
    val takeoverWhenMediaStart = remember {
        mutableStateOf(sharedPreferences.getBoolean("takeover_when_media_start", true))
    }

    val useAlternateHeadTrackingPackets = remember {
        mutableStateOf(sharedPreferences.getBoolean("use_alternate_head_tracking_packets", false))
    }

    fun validateHexInput(input: String): Boolean {
        val hexPattern = Regex("^[0-9a-fA-F]{32}$")
        return hexPattern.matches(input)
    }

    val isProcessingSdp = remember { mutableStateOf(false) }
    val actAsAppleDevice = remember { mutableStateOf(false) }

    BackHandler(enabled = isProcessingSdp.value) {}

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

            StyledToggle(
                title = stringResource(R.string.widget),
                label = stringResource(R.string.show_phone_battery_in_widget),
                description = stringResource(R.string.show_phone_battery_in_widget_description),
                checkedState = showPhoneBatteryInWidget,
                sharedPreferenceKey = "show_phone_battery_in_widget",
                sharedPreferences = sharedPreferences,
            )

            Text(
                text = stringResource(R.string.conversational_awareness),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(16.dp, bottom = 2.dp, top = 24.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            Column (
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundColor,
                        RoundedCornerShape(28.dp)
                    )
                    .padding(vertical = 4.dp)
            ) {
                fun updateConversationalAwarenessPauseMusic(enabled: Boolean) {
                    conversationalAwarenessPauseMusicEnabled.value = enabled
                    sharedPreferences.edit { putBoolean("conversational_awareness_pause_music", enabled)}
                }

                fun updateRelativeConversationalAwarenessVolume(enabled: Boolean) {
                    relativeConversationalAwarenessVolumeEnabled.value = enabled
                    sharedPreferences.edit { putBoolean("relative_conversational_awareness_volume", enabled)}
                }

                StyledToggle(
                    label = stringResource(R.string.conversational_awareness_pause_music),
                    description = stringResource(R.string.conversational_awareness_pause_music_description),
                    checkedState = conversationalAwarenessPauseMusicEnabled,
                    onCheckedChange = { updateConversationalAwarenessPauseMusic(it) },
                    independent = false
                )

                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                )

                StyledToggle(
                    label = stringResource(R.string.relative_conversational_awareness_volume),
                    description = stringResource(R.string.relative_conversational_awareness_volume_description),
                    checkedState = relativeConversationalAwarenessVolumeEnabled,
                    onCheckedChange = { updateRelativeConversationalAwarenessVolume(it) },
                    independent = false
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val conversationalAwarenessVolume = remember { mutableFloatStateOf(sharedPreferences.getInt("conversational_awareness_volume", 43).toFloat()) }
            LaunchedEffect(conversationalAwarenessVolume.floatValue) {
                sharedPreferences.edit { putInt("conversational_awareness_volume", conversationalAwarenessVolume.floatValue.roundToInt()) }
            }

            StyledSlider(
                label = stringResource(R.string.conversational_awareness_volume),
                mutableFloatState = conversationalAwarenessVolume,
                valueRange = 10f..85f,
                startLabel = "10%",
                endLabel = "85%",
                onValueChange = { newValue -> conversationalAwarenessVolume.floatValue = newValue },
                independent = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            NavigationButton(
                to = "",
                title = stringResource(R.string.camera_control),
                name = stringResource(R.string.set_custom_camera_package),
                navController = navController,
                onClick = { showCameraDialog.value = true },
                independent = true,
                description = stringResource(R.string.camera_control_app_description)
            )

            Spacer(modifier = Modifier.height(16.dp))

            StyledToggle(
                title = stringResource(R.string.quick_settings_tile),
                label = stringResource(R.string.open_dialog_for_controlling),
                description = stringResource(R.string.open_dialog_for_controlling_description),
                checkedState = openDialogForControlling,
                onCheckedChange = {
                    openDialogForControlling.value = it
                    sharedPreferences.edit { putString("qs_click_behavior", if (it) "dialog" else "activity") }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            StyledToggle(
                title = stringResource(R.string.ear_detection),
                label = stringResource(R.string.disconnect_when_not_wearing),
                description = stringResource(R.string.disconnect_when_not_wearing_description),
                checkedState = disconnectWhenNotWearing,
                sharedPreferenceKey = "disconnect_when_not_wearing",
                sharedPreferences = sharedPreferences,
            )

            Text(
                text = stringResource(R.string.takeover_airpods_state),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(16.dp, bottom = 2.dp, top = 24.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundColor,
                        RoundedCornerShape(28.dp)
                    )
                    .padding(vertical = 4.dp)
            ) {
                StyledToggle(
                    label = stringResource(R.string.takeover_disconnected),
                    description = stringResource(R.string.takeover_disconnected_desc),
                    checkedState = takeoverWhenDisconnected,
                    onCheckedChange = {
                        takeoverWhenDisconnected.value = it
                        sharedPreferences.edit { putBoolean("takeover_when_disconnected", it)}
                    },
                    independent = false
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                )

                StyledToggle(
                    label = stringResource(R.string.takeover_idle),
                    description = stringResource(R.string.takeover_idle_desc),
                    checkedState = takeoverWhenIdle,
                    onCheckedChange = {
                        takeoverWhenIdle.value = it
                        sharedPreferences.edit { putBoolean("takeover_when_idle", it)}
                    },
                    independent = false
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                )

                StyledToggle(
                    label = stringResource(R.string.takeover_music),
                    description = stringResource(R.string.takeover_music_desc),
                    checkedState = takeoverWhenMusic,
                    onCheckedChange = {
                        takeoverWhenMusic.value = it
                        sharedPreferences.edit { putBoolean("takeover_when_music", it)}
                    },
                    independent = false
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                )

                StyledToggle(
                    label = stringResource(R.string.takeover_call),
                    description = stringResource(R.string.takeover_call_desc),
                    checkedState = takeoverWhenCall,
                    onCheckedChange = {
                        takeoverWhenCall.value = it
                        sharedPreferences.edit { putBoolean("takeover_when_call", it)}
                    },
                    independent = false
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.takeover_phone_state),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundColor,
                        RoundedCornerShape(28.dp)
                    )
                    .padding(vertical = 4.dp)
            ){
                StyledToggle(
                    label = stringResource(R.string.takeover_ringing_call),
                    description = stringResource(R.string.takeover_ringing_call_desc),
                    checkedState = takeoverWhenRingingCall,
                    onCheckedChange = {
                        takeoverWhenRingingCall.value = it
                        sharedPreferences.edit { putBoolean("takeover_when_ringing_call", it)}
                    },
                    independent = false
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                )

                StyledToggle(
                    label = stringResource(R.string.takeover_media_start),
                    description = stringResource(R.string.takeover_media_start_desc),
                    checkedState = takeoverWhenMediaStart,
                    onCheckedChange = {
                        takeoverWhenMediaStart.value = it
                        sharedPreferences.edit { putBoolean("takeover_when_media_start", it)}
                    },
                    independent = false
                )
            }

            Text(
                text = stringResource(R.string.advanced_options),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(16.dp, bottom = 2.dp, top = 24.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundColor,
                        RoundedCornerShape(28.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable (
                            onClick = { showIrkDialog.value = true },
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                            .padding(end = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.set_identity_resolving_key),
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.set_identity_resolving_key_description),
                            fontSize = 14.sp,
                            color = textColor.copy(0.6f),
                            lineHeight = 16.sp,
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable (
                            onClick = { showEncKeyDialog.value = true },
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                            .padding(end = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.set_encryption_key),
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.set_encryption_key_description),
                            fontSize = 14.sp,
                            color = textColor.copy(0.6f),
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            StyledToggle(
                label = stringResource(R.string.use_alternate_head_tracking_packets),
                description = stringResource(R.string.use_alternate_head_tracking_packets_description),
                checkedState = useAlternateHeadTrackingPackets,
                onCheckedChange = {
                    useAlternateHeadTrackingPackets.value = it
                    sharedPreferences.edit { putBoolean("use_alternate_head_tracking_packets", it)}
                },
                independent = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            NavigationButton(
                to = "troubleshooting",
                name = stringResource(R.string.troubleshooting),
                navController = navController,
                independent = true,
                description = stringResource(R.string.troubleshooting_description)
            )

            LaunchedEffect(Unit) {
                actAsAppleDevice.value = RadareOffsetFinder.isSdpOffsetAvailable()
            }
            val restartBluetoothText = stringResource(R.string.found_offset_restart_bluetooth)

            StyledToggle(
                label = stringResource(R.string.act_as_an_apple_device),
                description = stringResource(R.string.act_as_an_apple_device_description),
                checkedState = actAsAppleDevice,
                onCheckedChange = {
                    actAsAppleDevice.value = it
                    isProcessingSdp.value = true
                    coroutineScope.launch {
                        if (it) {
                            val radareOffsetFinder = RadareOffsetFinder(context)
                            val success = radareOffsetFinder.findSdpOffset()
                            if (success) {
                                Toast.makeText(context, restartBluetoothText, Toast.LENGTH_LONG).show()
                            }
                        } else {
                            RadareOffsetFinder.clearSdpOffset()
                        }
                        isProcessingSdp.value = false
                    }
                },
                independent = true,
                enabled = !isProcessingSdp.value
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { showResetDialog.value = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(28.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.reset_hook_offset),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            NavigationButton(
                to = "open_source_licenses",
                name = stringResource(R.string.open_source_licenses),
                navController = navController,
                independent = true
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (showResetDialog.value) {
                AlertDialog(
                    onDismissRequest = { showResetDialog.value = false },
                    title = {
                        Text(
                            "Reset Hook Offset",
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            fontWeight = FontWeight.Medium
                        )
                    },
                    text = {
                        Text(
                            stringResource(R.string.reset_hook_offset_description),
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    },
                    confirmButton = {
                        val successText = stringResource(R.string.hook_offset_reset_success)
                        val failureText = stringResource(R.string.hook_offset_reset_failure)
                        TextButton(
                            onClick = {
                                if (RadareOffsetFinder.clearHookOffsets()) {
                                    Toast.makeText(
                                        context,
                                        successText,
                                        Toast.LENGTH_LONG
                                    ).show()

                                    navController.navigate("onboarding") {
                                        popUpTo("settings") { inclusive = true }
                                    }
                                } else {
                                    Toast.makeText(
                                        context,
                                        failureText,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                showResetDialog.value = false
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(
                                stringResource(R.string.reset),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showResetDialog.value = false }
                        ) {
                            Text(
                                "Cancel",
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                )
            }

            if (showIrkDialog.value) {
                AlertDialog(
                    onDismissRequest = { showIrkDialog.value = false },
                    title = {
                        Text(
                            stringResource(R.string.set_identity_resolving_key),
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            fontWeight = FontWeight.Medium
                        )
                    },
                    text = {
                        Column {
                            Text(
                                stringResource(R.string.enter_irk_hex),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            OutlinedTextField(
                                value = irkValue.value,
                                onValueChange = {
                                    irkValue.value = it.lowercase().filter { char -> char.isDigit() || char in 'a'..'f' }
                                    irkError.value = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                isError = irkError.value != null,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii,
                                    capitalization = KeyboardCapitalization.None
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = if (isDarkTheme) Color(0xFF007AFF) else Color(0xFF3C6DF5),
                                    unfocusedBorderColor = if (isDarkTheme) Color.Gray else Color.LightGray
                                ),
                                supportingText = {
                                    if (irkError.value != null) {
                                        Text(stringResource(R.string.must_be_32_hex_chars), color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                label = { Text(stringResource(R.string.irk_hex_value)) }
                            )
                        }
                    },
                    confirmButton = {
                        val successText = stringResource(R.string.irk_set_success)
                        val errorText = stringResource(R.string.error_converting_hex)
                        TextButton(
                            onClick = {
                                if (!validateHexInput(irkValue.value)) {
                                    irkError.value = "Must be exactly 32 hex characters"
                                    return@TextButton
                                }

                                try {
                                    val hexBytes = ByteArray(16)
                                    for (i in 0 until 16) {
                                        val hexByte = irkValue.value.substring(i * 2, i * 2 + 2)
                                        hexBytes[i] = hexByte.toInt(16).toByte()
                                    }

                                    val base64Value = Base64.encode(hexBytes)
                                    sharedPreferences.edit { putString(AACPManager.Companion.ProximityKeyType.IRK.name, base64Value)}

                                    Toast.makeText(context, successText, Toast.LENGTH_SHORT).show()
                                    showIrkDialog.value = false
                                } catch (e: Exception) {
                                    irkError.value = errorText + " " + (e.message ?: "Unknown error")
                                }
                            }
                        ) {
                            Text(
                                "Save",
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showIrkDialog.value = false }
                        ) {
                            Text(
                                "Cancel",
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                )
            }

            if (showEncKeyDialog.value) {
                AlertDialog(
                    onDismissRequest = { showEncKeyDialog.value = false },
                    title = {
                        Text(
                            stringResource(R.string.set_encryption_key),
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            fontWeight = FontWeight.Medium
                        )
                    },
                    text = {
                        Column {
                            Text(
                                stringResource(R.string.enter_enc_key_hex),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            OutlinedTextField(
                                value = encKeyValue.value,
                                onValueChange = {
                                    encKeyValue.value = it.lowercase().filter { char -> char.isDigit() || char in 'a'..'f' }
                                    encKeyError.value = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                isError = encKeyError.value != null,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii,
                                    capitalization = KeyboardCapitalization.None
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = if (isDarkTheme) Color(0xFF007AFF) else Color(0xFF3C6DF5),
                                    unfocusedBorderColor = if (isDarkTheme) Color.Gray else Color.LightGray
                                ),
                                supportingText = {
                                    if (encKeyError.value != null) {
                                        Text(stringResource(R.string.must_be_32_hex_chars), color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                label = { Text(stringResource(R.string.enc_key_hex_value)) }
                            )
                        }
                    },
                    confirmButton = {
                        val successText = stringResource(R.string.encryption_key_set_success)
                        val errorText = stringResource(R.string.error_converting_hex)
                        TextButton(
                            onClick = {
                                if (!validateHexInput(encKeyValue.value)) {
                                    encKeyError.value = "Must be exactly 32 hex characters"
                                    return@TextButton
                                }

                                try {
                                    val hexBytes = ByteArray(16)
                                    for (i in 0 until 16) {
                                        val hexByte = encKeyValue.value.substring(i * 2, i * 2 + 2)
                                        hexBytes[i] = hexByte.toInt(16).toByte()
                                    }

                                    val base64Value = Base64.encode(hexBytes)
                                    sharedPreferences.edit { putString(AACPManager.Companion.ProximityKeyType.ENC_KEY.name, base64Value)}

                                    Toast.makeText(context, successText, Toast.LENGTH_SHORT).show()
                                    showEncKeyDialog.value = false
                                } catch (e: Exception) {
                                    encKeyError.value = errorText + " " + (e.message ?: "Unknown error")
                                }
                            }
                        ) {
                            Text(
                                "Save",
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showEncKeyDialog.value = false }
                        ) {
                            Text(
                                "Cancel",
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                )
            }

            if (showCameraDialog.value) {
                AlertDialog(
                    onDismissRequest = { showCameraDialog.value = false },
                    title = {
                        Text(
                            stringResource(R.string.set_custom_camera_package),
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            fontWeight = FontWeight.Medium
                        )
                    },
                    text = {
                        Column {
                            Text(
                                stringResource(R.string.enter_custom_camera_package),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            OutlinedTextField(
                                value = cameraPackageValue.value,
                                onValueChange = {
                                    cameraPackageValue.value = it
                                    cameraPackageError.value = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                isError = cameraPackageError.value != null,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii,
                                    capitalization = KeyboardCapitalization.None
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = if (isDarkTheme) Color(0xFF007AFF) else Color(0xFF3C6DF5),
                                    unfocusedBorderColor = if (isDarkTheme) Color.Gray else Color.LightGray
                                ),
                                supportingText = {
                                    if (cameraPackageError.value != null) {
                                        Text(cameraPackageError.value!!, color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                label = { Text(stringResource(R.string.custom_camera_package)) }
                            )
                        }
                    },
                    confirmButton = {
                        val successText = stringResource(R.string.custom_camera_package_set_success)
                        TextButton(
                            onClick = {
                                if (cameraPackageValue.value.isBlank()) {
                                    sharedPreferences.edit { remove("custom_camera_package") }
                                    Toast.makeText(context, successText, Toast.LENGTH_SHORT).show()
                                    showCameraDialog.value = false
                                    return@TextButton
                                }

                                sharedPreferences.edit { putString("custom_camera_package", cameraPackageValue.value) }
                                Toast.makeText(context, successText, Toast.LENGTH_SHORT).show()
                                showCameraDialog.value = false
                            }
                        ) {
                            Text(
                                "Save",
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showCameraDialog.value = false }
                        ) {
                            Text(
                                "Cancel",
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                )
            }
        }
    }
}
