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

package me.kavishdevar.librepods.presentation.screens.apple

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.debounce
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.data.apple.BuddyState
import me.kavishdevar.librepods.devices.AirPodsSpecs
import me.kavishdevar.librepods.devices.AppleSettings
import me.kavishdevar.librepods.devices.BaseCapability
import me.kavishdevar.librepods.presentation.components.apple.AboutCard
import me.kavishdevar.librepods.presentation.components.apple.AudioSettings
import me.kavishdevar.librepods.presentation.components.apple.BatteryView
import me.kavishdevar.librepods.presentation.components.apple.CallControlSettings
import me.kavishdevar.librepods.presentation.components.apple.ConnectionSettings
import me.kavishdevar.librepods.presentation.components.apple.HearingHealthSettings
import me.kavishdevar.librepods.presentation.components.apple.NoiseControlSettings
import me.kavishdevar.librepods.presentation.components.apple.PressAndHoldSettings
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun AppleSettingsRoute(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?,
    navigateToRename: () -> Unit,
    navigateToHearingProtection: () -> Unit,
    navigateToHearingAid: () -> Unit,
    navigateToLeftLongPress: () -> Unit,
    navigateToRightLongPress: () -> Unit,
    navigateToPurchase: () -> Unit,
    navigateToEqualizer: () -> Unit,
    navigateToHeadTracking: () -> Unit,
    navigateToAccessibility: () -> Unit,
    navigateToVersion: () -> Unit,
    navigateToCallControlScreen: (action: String) -> Unit,
    navigateToMicrophoneSettings: () -> Unit,
    navigateToRecordingScreen: () -> Unit,
    navigateToDebugScreen: () -> Unit,
    navigateToHeartRateScreen: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    AppleSettingsScreen(
        uiState = uiState,

        setControlCommandInt = { id, value -> viewModel.setControlCommand(id, value) },
        setControlCommandBoolean = { id, value -> viewModel.setControlCommand(id, value) },
//            setControlCommandByte = { id, value -> viewModel.setControlCommand(id, value) },
//            setControlCommandValue = { id, value -> viewModel.setControlCommand(id, value) },

        writeATTCharacteristic = viewModel::writeATTCharacteristic,

        updateSettings = viewModel::updateSettings,

//            onAutomaticConnectionChanged = viewModel::setAutomaticConnectionEnabled,
        disconnect = viewModel::disconnect,

        navigateBack = navigateBack,
        navigateToRename = navigateToRename,
        navigateToHearingProtection = navigateToHearingProtection,
        navigateToHearingAid = navigateToHearingAid,
        navigateToLeftLongPress = navigateToLeftLongPress,
        navigateToRightLongPress = navigateToRightLongPress,
        navigateToPurchase = navigateToPurchase,
        navigateToEqualizer = navigateToEqualizer,
        navigateToHeadTracking = navigateToHeadTracking,
        navigateToAccessibility = navigateToAccessibility,
        navigateToVersion = navigateToVersion,
        navigateToCallControlScreen = navigateToCallControlScreen,
        navigateToMicrophoneSettings = navigateToMicrophoneSettings,
        navigateToRecordingScreen = navigateToRecordingScreen,
        navigateToHeartRateScreen = navigateToHeartRateScreen,
        navigateToDebugScreen = navigateToDebugScreen
    )
}

@SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
@Composable
fun AppleSettingsScreen(
    uiState: AppleUiState,

    setControlCommandInt: (ControlCommandIdentifier, Int) -> Unit,
    setControlCommandBoolean: (ControlCommandIdentifier, Boolean) -> Unit,
//        setControlCommandByte: (ControlCommandIdentifier, Byte) -> Unit,
//        setControlCommandValue: (ControlCommandIdentifier, ByteArray) -> Unit,

    writeATTCharacteristic: (ATTHandle, ByteArray) -> Unit,

    updateSettings: (transform: (AppleSettings) -> AppleSettings) -> Unit,

//    onAutomaticEarDetectionChanged: (Boolean) -> Unit,
//    onAutomaticConnectionChanged: (Boolean) -> Unit,

    disconnect: () -> Unit,

    navigateBack: (() -> Unit)?,
    navigateToRename: () -> Unit,
    navigateToHearingProtection: () -> Unit,
    navigateToHearingAid: () -> Unit,
    navigateToLeftLongPress: () -> Unit,
    navigateToRightLongPress: () -> Unit,
    navigateToPurchase: () -> Unit,
    navigateToEqualizer: () -> Unit,
    navigateToHeadTracking: () -> Unit,
    navigateToAccessibility: () -> Unit,
    navigateToVersion: () -> Unit,
    navigateToCallControlScreen: (action: String) -> Unit,
    navigateToMicrophoneSettings: () -> Unit,
    navigateToRecordingScreen: () -> Unit,
    navigateToHeartRateScreen: () -> Unit,
    navigateToDebugScreen: () -> Unit
) {
    val state = uiState.state
    val settings = uiState.settings
    val metadata = uiState.metadata

    val spec = AirPodsSpecs.getSpec(metadata.model)

    val baseCapabilities = spec.baseCapabilities
    val designSystem = LocalDesignSystem.current

    // HyperOS puts the listening-mode control directly under the battery card, before
    // the device name, so under Miuix this block moves up there. Everywhere else it
    // keeps its place between the hearing-health and the recording groups.
    val listeningModeItems: LazyListScope.() -> Unit = {
        if (baseCapabilities.contains(BaseCapability.LISTENING_MODE)) {
            if (designSystem != DesignSystem.Miuix) {
                item(key = "spacer_noise") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            item(key = "noise_control") {
                NoiseControlSettings(
                    showOffListeningMode = state.controlStates[ControlCommandIdentifier.ALLOW_OFF_OPTION]?.getOrNull(0)?.toInt() == 1,
                    noiseControlModeValue = state.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 3,
                    onNoiseControlModeChanged = {
                        setControlCommandInt(
                            ControlCommandIdentifier.LISTENING_MODE, it
                        )
                    },
                )
            }

            if (baseCapabilities.contains(BaseCapability.ADAPTIVE_AUDIO)) {
                item(key = "adaptive_strength") {
                    AnimatedVisibility(
                        visible = state.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.getOrNull(0)?.toInt() == 4,
                        enter = remember {
                            fadeIn() + slideInVertically()
                        },
                        exit = remember {
                            fadeOut() + slideOutVertically()
                        }
                    ) {
                        val sliderValue = remember {
                            mutableFloatStateOf(
                                100f - (state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.getOrNull(
                                    0
                                )?.toFloat() ?: 50f)
                            )
                        }

                        LaunchedEffect(sliderValue) {
                            snapshotFlow { sliderValue.floatValue }
                                .debounce(100.milliseconds)
                                .collect { value ->
                                    setControlCommandInt(
                                        ControlCommandIdentifier.AUTO_ANC_STRENGTH,
                                        (100 - value).toInt()
                                    )
                                }
                        }

                        StyledSlider(
                            value = sliderValue.floatValue,
                            onValueChange = { sliderValue.floatValue = it },
                            valueRange = 0f..100f,
                            snapPoints = listOf(0f, 50f, 100f),
                            startImageVector = LocalIcons.current.SpeakerMin,
                            endImageVector = LocalIcons.current.SpeakerMax,
                            independent = true,
                            description = stringResource(R.string.adaptive_audio_description),
                            enabled = uiState.isPremium
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    StyledScaffold(
        title = uiState.metadata.name,
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            item(key = "top_padding") { Spacer(modifier = Modifier.height(topPadding)) }

            item(key = "battery") {
                BatteryView(
                    batteryList = state.battery,
                    primaryImageRes = spec.primaryImageRes,
                    caseImageRes = spec.caseImageRes ?: R.drawable.img_airpods_pro_2_case // TODO
                )
            }
            if (designSystem == DesignSystem.Miuix) listeningModeItems()
            item(key = "spacer_battery") {
                Spacer(modifier = Modifier.height(32.dp))
            }

            item(key = "name") {
                StyledListItem(
                    contentText = stringResource(R.string.name),
                    supportingText = metadata.name,
                    onClick = navigateToRename,
                )
            }

            val hasHearingAidCapability = baseCapabilities.contains(BaseCapability.HEARING_AID)
            val hasPPECapability = baseCapabilities.contains(BaseCapability.PPE)

            if (hasHearingAidCapability || hasPPECapability) {
                if (hasPPECapability || uiState.vendorIdHook) {
                    item(key = "spacer_hearing_health") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
                item(key = "hearing_health") {
                    HearingHealthSettings(
                        hasPPECapability = hasPPECapability,
                        hasHearingAidCapability = hasHearingAidCapability,
                        vendorIdHook = uiState.vendorIdHook,
                        navigateToHearingProtection = navigateToHearingProtection,
                        navigateToHearingAid = navigateToHearingAid
                    )
                }
            }
            if (designSystem != DesignSystem.Miuix) listeningModeItems()

            if (metadata.version3.isNotBlank() && metadata.version3.first().digitToInt() >= 8) {
                item(key = "spacer_recording") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item(key = "recording") {
                    StyledListItem(
                        contentText = stringResource(R.string.recorder),
                        supportingText = stringResource(R.string.recorder_description),
                        onClick = navigateToRecordingScreen,
                        orientation = StyledListItemOrientation.Vertical
                    )
                }
            }

            if (baseCapabilities.contains(BaseCapability.HRM)) {
                val showAlertDisabledMessage = state.hrmState != BuddyState.ACTIVE && settings.hrmAlertEnabled
                item(key = "spacer_heart_rate") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item(key = "heart_rate") {
                    StyledListItem(
                        contentText = stringResource(R.string.heart_rate),
                        onClick = navigateToHeartRateScreen,
                        supportingText = if (showAlertDisabledMessage) stringResource(R.string.heart_rate_alerts_disabled_warning) else state.currentHeartRate?.let { "${it.bpm} bpm" },
                        orientation = if (showAlertDisabledMessage) StyledListItemOrientation.Vertical else StyledListItemOrientation.Horizontal
                    )
                }
            }

            if (baseCapabilities.contains(BaseCapability.STEM_CONFIG)) {
                item(key = "spacer_press_hold") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item(key = "press_hold") {
                    PressAndHoldSettings(
                        leftAction = settings.leftLongPressAction,
                        rightAction = settings.rightLongPressAction,
                        navigateToLeftLongPress = navigateToLeftLongPress,
                        navigateToRightLongPress = navigateToRightLongPress
                    )
                }
            }

            item(key = "spacer_call") {
                Spacer(modifier = Modifier.height(16.dp))
            }
            item(key = "call_control") {
                val bytes =
                    state.controlStates[ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG]?.take(2)?.toByteArray() ?: byteArrayOf(0x00, 0x00)
                val flipped = try {
                    bytes[1] == 0x02.toByte()
                } catch (_: Exception) {
                    false
                }
                CallControlSettings(
                    flipped = flipped,
                    navigateToCallControlScreen = navigateToCallControlScreen
                )
            }

//                if (baseCapabilities.contains(BaseCapability.RAW_GESTURES_CONFIG) && !BuildConfig.PLAY_BUILD) {
//                    item(key = "spacer_camera") { Spacer(modifier = Modifier.height(16.dp)) }
//                    item(key = "camera_control") {
//                        StyledListItem(
//                            to = "camera_control",
//                            contentText = stringResource(R.string.camera_remote),
//                            descriptionRes = stringResource(R.string.camera_control_description),
//                            titleRes = stringResource(R.string.camera_control),
//                            navController = navController
//                        )
//                    }
//                }

            item(key = "upgrade_button") {
                if (!uiState.isPremium) {
                    Spacer(modifier = Modifier.height(28.dp))
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
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            item(key = "spacer_audio") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "audio") {
                val adaptiveVolumeCapability = baseCapabilities.contains(BaseCapability.ADAPTIVE_VOLUME)
                val conversationalAwarenessCapability = baseCapabilities.contains(BaseCapability.CONVERSATION_AWARENESS)
                val loudSoundReductionCapability = baseCapabilities.contains(BaseCapability.LOUD_SOUND_REDUCTION)

                val adaptiveVolumeChecked = state.controlStates[ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG]?.getOrNull(0) == 0x01.toByte()
                val conversationalAwarenessChecked = state.controlStates[ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG]?.getOrNull(0) == 0x01.toByte()

                AudioSettings(
                    adaptiveVolumeCapability = adaptiveVolumeCapability,
                    conversationalAwarenessCapability = conversationalAwarenessCapability,
                    loudSoundReductionCapability = loudSoundReductionCapability,
                    customEqCapability = metadata.version3.isNotBlank() && metadata.version3.first().digitToInt() >= 9,
                    adaptiveVolumeChecked = adaptiveVolumeChecked,
                    onAdaptiveVolumeCheckedChange = { checked ->
                        setControlCommandBoolean(
                            ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG,
                            checked
                        )
                    },
                    conversationalAwarenessChecked = conversationalAwarenessChecked && uiState.isPremium,
                    onConversationalAwarenessCheckedChange = { checked ->
                        setControlCommandBoolean(
                            ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG,
                            checked
                        )
                    },
                    loudSoundReductionChecked = state.loudSoundReductionEnabled,
                    onLoudSoundReductionCheckedChange = { checked ->
                        writeATTCharacteristic(
                            ATTHandle.LOUD_SOUND_REDUCTION,
                            byteArrayOf(if (checked) 0x01.toByte() else 0x00.toByte())
                        )
                    },
                    navigateToEqualizer = navigateToEqualizer,
                    vendorIdHook = uiState.vendorIdHook,
                    isPremium = uiState.isPremium
                )
            }

            item(key = "spacer_connection") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "connection") {
                ConnectionSettings(
                    automaticEarDetectionEnabled = state.controlStates[ControlCommandIdentifier.EAR_DETECTION_CONFIG]?.getOrNull(0) == 0x01.toByte() || settings.earDetectionEnabled,
                    onAutomaticEarDetectionChanged = { enabled -> setControlCommandBoolean(ControlCommandIdentifier.EAR_DETECTION_CONFIG, enabled); updateSettings { it.copy(earDetectionEnabled = enabled) } },
                    automaticConnectionEnabled = state.controlStates[ControlCommandIdentifier.SMART_ROUTING_MODE]?.getOrNull(0) == 0x01.toByte(),
                    onAutomaticConnectionChanged = { setControlCommandBoolean(ControlCommandIdentifier.SMART_ROUTING_MODE, it) },
                    disconnectWhenNotWearing = settings.disconnectWhenNotWearing,
                    onDisconnectWhenNotWearingChanged = { enabled -> updateSettings { it.copy(disconnectWhenNotWearing = enabled) } },

                    takeoverWhenDisconnected = settings.takeoverWhenDisconnected,
                    onTakeoverWhenDisconnectedChanged = { enabled -> updateSettings { it.copy(takeoverWhenDisconnected = enabled) } },
                    takeoverWhenIdle = settings.takeoverWhenIdle,
                    onTakeoverWhenIdleChanged = { enabled -> updateSettings { it.copy(takeoverWhenIdle = enabled) } },
                    takeoverWhenMusic = settings.takeoverWhenMusic,
                    onTakeoverWhenMusicChanged = { enabled -> updateSettings { it.copy(takeoverWhenMusic = enabled) } },
                    takeoverWhenCall = settings.takeoverWhenCall,
                    onTakeoverWhenCallChanged = { enabled -> updateSettings { it.copy(takeoverWhenCall = enabled) } },

                    takeoverWhenRingingCall = settings.takeoverWhenRingingCall,
                    onTakeoverWhenRingingCallChanged = { enabled -> updateSettings { it.copy(takeoverWhenRingingCall = enabled) } },
                    takeoverWhenMediaStart = settings.takeoverWhenMediaStart,
                    onTakeoverWhenMediaStartChanged = { enabled -> updateSettings { it.copy(takeoverWhenMediaStart = enabled) } },

                    isPremium = uiState.isPremium
                )
            }

            item(key = "spacer_microphone") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "microphoneState") {
                val id = ControlCommandIdentifier.MIC_MODE

                val selectedModeText =
                    when (state.controlStates[id]?.getOrNull(0) ?: 0x00.toByte()) {
                        0x00.toByte() -> stringResource(R.string.microphone_automatic)
                        0x01.toByte() -> stringResource(R.string.microphone_always_right)
                        0x02.toByte() -> stringResource(R.string.microphone_always_left)
                        else -> stringResource(R.string.microphone_automatic)
                    }

                StyledListItem(
                    contentText = stringResource(R.string.microphone_mode),
                    supportingText = selectedModeText,
                    onClick = navigateToMicrophoneSettings
                )
            }

            if (baseCapabilities.contains(BaseCapability.SLEEP_DETECTION)) {
                item(key = "spacer_sleep") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "sleep_detection") {
                    val id = ControlCommandIdentifier.SLEEP_DETECTION_CONFIG
                    StyledToggle(
                        label = stringResource(R.string.sleep_detection),
                        checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(),
                        onCheckedChange = { setControlCommandBoolean(id, it) },
                        enabled = uiState.isPremium
                    )
                }
            }

            if (baseCapabilities.contains(BaseCapability.HEAD_GESTURES)) {
                item(key = "spacer_head_tracking") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "head_tracking") {
                    StyledListItem(
                        contentText = stringResource(R.string.head_gestures),
                        supportingText = if (settings.headGesturesEnabled) stringResource(R.string.on) else stringResource(R.string.off),
                        onClick = navigateToHeadTracking
                    )
                }
            }

            item(key = "spacer_dynamic_end_of_charge") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "dynamic_end_of_charge") {
                StyledToggle(
                    label = stringResource(R.string.optimized_charging),
                    description = stringResource(R.string.optimized_charging_description),
                    checked = state.controlStates[ControlCommandIdentifier.DYNAMIC_END_OF_CHARGE]?.getOrNull(0) == 0x01.toByte(),
                    onCheckedChange = { setControlCommandBoolean(ControlCommandIdentifier.DYNAMIC_END_OF_CHARGE, it) }
                )
            }

            item(key = "spacer_accessibility") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "accessibility") {
                StyledListItem(
                    contentText = stringResource(R.string.accessibility), onClick = navigateToAccessibility
                )
            }

            if (baseCapabilities.contains(BaseCapability.LOUD_SOUND_REDUCTION) && (metadata.version3.isNotBlank() && metadata.version3.first().digitToInt() >= 8)) {
                item(key = "spacer_off_listening") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "off_listening") {
                    val id = ControlCommandIdentifier.ALLOW_OFF_OPTION
                    StyledToggle(
                        label = stringResource(R.string.off_listening_mode),
                        description = stringResource(R.string.off_listening_mode_description),
                        checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(),
                        onCheckedChange = { setControlCommandBoolean(id, it) }
                    )
                }
            }

            item(key = "spacer_about") { Spacer(modifier = Modifier.height(32.dp)) }
            item(key = "about") {
                AboutCard(
                    modelName = metadata.modelName,
                    actualModel = metadata.modelNumber,
                    serialNumbers = listOf(metadata.serialNumber, metadata.leftSerialNumber, metadata.rightSerialNumber),
                    version = metadata.version3,
                    navigateToVersion = navigateToVersion
                )
            }

            item(key = "spacer_disconnect") { Spacer(modifier = Modifier.height(28.dp)) }
            item(key = "disconnect_button") {
                StyledButton(
                    onClick = disconnect,
                    backdrop = rememberLayerBackdrop(),
                    isInteractive = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                ) {
                    Text(
                        text = stringResource(R.string.disconnect),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item(key = "spacer_debug") { Spacer(modifier = Modifier.height(16.dp)) }

            if (uiState.appSettings.debugMode) {
                item(key = "show_cached_battery") {
                    StyledToggle(
                        label = "show cached battery",
                        checked = settings.cacheDisconnectedComponentBattery,
                        onCheckedChange = { enabled ->
                            updateSettings { it.copy(cacheDisconnectedComponentBattery = enabled) }
                        }
                    )
                }
                item(key = "debug_button") {
                    StyledListItem(
                        contentText = "debug",
                        onClick = navigateToDebugScreen
                    )
                }
            }

            item(key = "bottom_padding") { Spacer(modifier = Modifier.height(bottomPadding)) }
        }
    }
}

@Preview(name = "Apple")
@Composable
fun AppleSettingsScreenPreviewApple() {
    LibrePodsTheme(
        designSystem = DesignSystem.Apple
    ) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            AppleSettingsScreen(
                uiState = AppleUiState(),

                setControlCommandInt = { _, _ -> },
                setControlCommandBoolean = { _, _ -> },
                writeATTCharacteristic = { _, _ -> },

                updateSettings = { _ -> },

                disconnect = {},

                navigateBack = null,
                navigateToRename = {},
                navigateToHearingProtection = {},
                navigateToHearingAid = {},
                navigateToLeftLongPress = {},
                navigateToRightLongPress = {},
                navigateToPurchase = {},
                navigateToEqualizer = {},
                navigateToHeadTracking = {},
                navigateToAccessibility = {},
                navigateToVersion = {},
                navigateToCallControlScreen = {},
                navigateToMicrophoneSettings = {},
                navigateToRecordingScreen = {},
                navigateToHeartRateScreen = {},
                navigateToDebugScreen = {}
            )
        }
    }
}


@Preview(name = "Material")
@Composable
fun AppleSettingsScreenPreviewMaterial() {
    LibrePodsTheme(
        designSystem = DesignSystem.Material
    ) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            AppleSettingsScreen(
                uiState = AppleUiState(),

                setControlCommandInt = { _, _ -> },
                setControlCommandBoolean = { _, _ -> },
                writeATTCharacteristic = { _, _ -> },

                updateSettings = { _ -> },

                disconnect = {},

                navigateBack = null,
                navigateToRename = {},
                navigateToHearingProtection = {},
                navigateToHearingAid = {},
                navigateToLeftLongPress = {},
                navigateToRightLongPress = {},
                navigateToPurchase = {},
                navigateToEqualizer = {},
                navigateToHeadTracking = {},
                navigateToAccessibility = {},
                navigateToVersion = {},
                navigateToCallControlScreen = {},
                navigateToMicrophoneSettings = {},
                navigateToRecordingScreen = {},
                navigateToHeartRateScreen = {},
                navigateToDebugScreen = {}
            )
        }
    }
}
