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

package me.kavishdevar.librepods.presentation.screens

// import me.kavishdevar.librepods.utils.RadareOffsetFinder
import android.annotation.SuppressLint
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.data.AirPodsPro3
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.presentation.components.AboutCard
import me.kavishdevar.librepods.presentation.components.AudioSettings
import me.kavishdevar.librepods.presentation.components.BatteryView
import me.kavishdevar.librepods.presentation.components.CallControlSettings
import me.kavishdevar.librepods.presentation.components.ConnectionSettings
import me.kavishdevar.librepods.presentation.components.HearingHealthSettings
import me.kavishdevar.librepods.presentation.components.MicrophoneSettings
import me.kavishdevar.librepods.presentation.components.NavigationButton
import me.kavishdevar.librepods.presentation.components.NoiseControlSettings
import me.kavishdevar.librepods.presentation.components.PressAndHoldSettings
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledIconButton
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
@Composable
fun AirPodsSettingsScreen(viewModel: AirPodsViewModel, navController: NavController) {
    val state by viewModel.uiState.collectAsState()
    val sharedPreferences = LocalContext.current.getSharedPreferences("settings", MODE_PRIVATE)
    var deviceName by remember {
        mutableStateOf(
            TextFieldValue(
                sharedPreferences.getString("name", state.deviceName).toString()
            )
        )
    }

    val nameChangeListener = remember {
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "name") {
                deviceName =
                    TextFieldValue(sharedPreferences.getString("name", "AirPods Pro").toString())
            }
        }
    }

    DisposableEffect(Unit) {
        sharedPreferences.registerOnSharedPreferenceChangeListener(nameChangeListener)
        onDispose {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(nameChangeListener)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.refreshInitialData()
    }

    isSystemInDarkTheme()
    val hazeStateS = remember { mutableStateOf(HazeState()) }

    StyledScaffold(
        title = deviceName.text, actionButtons = listOf(
            { scaffoldBackdrop ->
                StyledIconButton(
                    onClick = { navController.navigate("app_settings") },
                    icon = "􀍟",
                    backdrop = scaffoldBackdrop
                )
            }), snackbarHostState = snackbarHostState
    ) { topPadding, hazeState, bottomPadding ->
        hazeStateS.value = hazeState
        var blockTouches by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            viewModel.demoActivated.collect {
                blockTouches = true
                delay(1000)
                blockTouches = false
            }
        }

        if (state.isLocallyConnected) {
            val capabilities = state.capabilities
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .padding(horizontal = 16.dp)
                    .then(if (blockTouches) Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } else Modifier)) {
                item(key = "spacer_top") { Spacer(modifier = Modifier.height(topPadding)) }
                item(key = "battery") {
                    BatteryView(
                        batteryList = state.battery,
                        budsRes = state.instance?.model?.budsRes ?: R.drawable.airpods_pro_2_case,
                        caseRes = state.instance?.model?.caseRes ?: R.drawable.airpods_pro_2_case
                    )
                }
                item(key = "spacer_battery") { Spacer(modifier = Modifier.height(32.dp)) }

                item(key = "name") {
                    NavigationButton(
                        to = "rename",
                        name = stringResource(R.string.name),
                        currentState = deviceName.text,
                        navController = navController,
                        independent = true
                    )
                }

                val hasHearingAidCapability =
                    state.instance?.model?.capabilities?.contains(Capability.HEARING_AID) == true
                val hasPPECapability =
                    state.instance?.model?.capabilities?.contains(Capability.PPE) == true

                if (hasHearingAidCapability || hasPPECapability) {
                    if (hasPPECapability || (state.vendorIdHook && hasHearingAidCapability)) item(
                        key = "spacer_hearing_health"
                    ) { Spacer(modifier = Modifier.height(24.dp)) }
                    item(key = "hearing_health") {
                        HearingHealthSettings(
                            navController = navController,
                            hasPPECapability = hasPPECapability,
                            hasHearingAidCapability = hasHearingAidCapability,
                            vendorIdHook = state.vendorIdHook
                        )
                    }
                }

                if (capabilities.contains(Capability.LISTENING_MODE)) {
                    item(key = "spacer_noise") { Spacer(modifier = Modifier.height(16.dp)) }
                    item(key = "noise_control") {
                        NoiseControlSettings(
                            showOffListeningMode = state.offListeningMode,
                            noiseControlModeValue = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE]?.getOrNull(
                                0
                            )?.toInt() ?: 3,
                            onNoiseControlModeChanged = {
                                viewModel.setControlCommandInt(
                                    AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE,
                                    it
                                )
                            },
                        )
                    }
                }

                if (capabilities.contains(Capability.STEM_CONFIG)) {
                    item(key = "spacer_press_hold") { Spacer(modifier = Modifier.height(16.dp)) }
                    item(key = "press_hold") {
                        PressAndHoldSettings(
                            navController = navController,
                            leftAction = state.leftAction,
                            rightAction = state.rightAction
                        )
                    }
                }

                item(key = "spacer_call") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "call_control") {
                    val flipped =
                        state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG]?.take(
                            2
                        )?.equals(byteArrayOf(0x00.toByte(), 0x02.toByte()))
                    CallControlSettings(
                        hazeState = hazeState,
                        flipped = flipped == true,
                        onCallControlValueChanged = {
                            viewModel.setControlCommandValue(
                                AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
                                if (it) byteArrayOf(0x00, 0x02) else byteArrayOf(0x00, 0x03)
                            )
                        })
                }

//                if (capabilities.contains(Capability.STEM_CONFIG) && !BuildConfig.PLAY_BUILD) {
//                    item(key = "spacer_camera") { Spacer(modifier = Modifier.height(16.dp)) }
//                    item(key = "camera_control") {
//                        NavigationButton(
//                            to = "camera_control",
//                            name = stringResource(R.string.camera_remote),
//                            description = stringResource(R.string.camera_control_description),
//                            title = stringResource(R.string.camera_control),
//                            navController = navController
//                        )
//                    }
//                }

                item(key = "upgrade_button") {
                    if (!state.isPremium) {
                        Spacer(modifier = Modifier.height(28.dp))
                        StyledButton(
                            onClick = {
                                navController.navigate("purchase_screen")
                            },
                            backdrop = rememberLayerBackdrop(),
                            modifier = Modifier.fillMaxWidth(),
                            maxScale = 0.05f,
                            tint = if (isSystemInDarkTheme()) Color(0xFF916100) else Color(
                                0xFFE59900
                            )
                        ) {
                            Text(
                                stringResource(R.string.unlock_advanced_features),
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                                    color = Color.White
                                ),
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                item(key = "spacer_audio") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "audio") {
                    val model = state.instance?.model ?: AirPodsPro3()
                    val adaptiveVolumeCapability =
                        model.capabilities.contains(Capability.ADAPTIVE_VOLUME)
                    val conversationalAwarenessCapability =
                        model.capabilities.contains(Capability.CONVERSATION_AWARENESS)
                    val loudSoundReductionCapability =
                        model.capabilities.contains(Capability.LOUD_SOUND_REDUCTION)
                    val adaptiveAudioCapability =
                        model.capabilities.contains(Capability.ADAPTIVE_VOLUME)

                    val adaptiveVolumeChecked =
                        state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG]?.getOrNull(
                            0
                        ) == 0x01.toByte()
                    val conversationalAwarenessChecked =
                        state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG]?.getOrNull(
                            0
                        ) == 0x01.toByte()

                    AudioSettings(
                        navController = navController,
                        adaptiveVolumeCapability = adaptiveVolumeCapability,
                        conversationalAwarenessCapability = conversationalAwarenessCapability,
                        loudSoundReductionCapability = loudSoundReductionCapability,
                        adaptiveAudioCapability = adaptiveAudioCapability,
                        adaptiveVolumeChecked = adaptiveVolumeChecked,
                        onAdaptiveVolumeCheckedChange = { checked ->
                            viewModel.setControlCommandBoolean(
                                AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG,
                                checked
                            )
                        },
                        conversationalAwarenessChecked = conversationalAwarenessChecked && state.isPremium,
                        onConversationalAwarenessCheckedChange = { checked ->
                            viewModel.setControlCommandBoolean(
                                AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
                                checked
                            )
                        },
                        loudSoundReductionChecked = state.loudSoundReductionEnabled,
                        onLoudSoundReductionCheckedChange = {
                            viewModel.setATTCharacteristicValue(
                                ATTHandles.LOUD_SOUND_REDUCTION,
                                byteArrayOf(if (it) 0x01.toByte() else 0x00.toByte())
                            )
                        },
                        vendorIdHook = state.vendorIdHook,
                        isPremium = state.isPremium
                    )
                }

                item(key = "spacer_connection") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "connection") {
                    ConnectionSettings(
                        automaticEarDetectionEnabled = state.automaticEarDetectionEnabled,
                        onAutomaticEarDetectionChanged = {
                            viewModel.setAutomaticEarDetectionEnabled(it)
                        },
                        automaticConnectionEnabled = state.automaticConnectionEnabled,
                        onAutomaticConnectionChanged = { viewModel.setAutomaticConnectionEnabled(it) })
                }

                item(key = "spacer_microphone") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "microphone") {
                    val id = AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE
                    MicrophoneSettings(
                        hazeState = hazeState,
                        micModeValue = state.controlStates[id]?.getOrNull(0) ?: 0x00.toByte(),
                        onMicModeValueChanged = { viewModel.setControlCommandByte(id, it) })
                }

                if (capabilities.contains(Capability.SLEEP_DETECTION)) {
                    item(key = "spacer_sleep") { Spacer(modifier = Modifier.height(16.dp)) }
                    item(key = "sleep_detection") {
                        val id =
                            AACPManager.Companion.ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG
                        StyledToggle(
                            label = stringResource(R.string.sleep_detection),
                            checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(),
                            onCheckedChange = {
                                viewModel.setControlCommandBoolean(id, it)
                            },
                            enabled = state.isPremium
                        )
                    }
                }

                if (capabilities.contains(Capability.HEAD_GESTURES)) {
                    item(key = "spacer_head_tracking") { Spacer(modifier = Modifier.height(16.dp)) }
                    item(key = "head_tracking") {
                        NavigationButton(
                            to = "head_tracking",
                            name = stringResource(R.string.head_gestures),
                            navController = navController,
                            currentState = if (sharedPreferences.getBoolean(
                                    "head_gestures", false
                                )
                            ) stringResource(R.string.on) else stringResource(R.string.off)
                        )
                    }
                }

                item(key = "spacer_accessibility") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "accessibility") {
                    NavigationButton(
                        to = "accessibility",
                        name = stringResource(R.string.accessibility),
                        navController = navController
                    )
                }

                if (capabilities.contains(Capability.LOUD_SOUND_REDUCTION)) {
                    item(key = "spacer_off_listening") { Spacer(modifier = Modifier.height(16.dp)) }
                    item(key = "off_listening") {
                        val id = AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION
                        StyledToggle(
                            label = stringResource(R.string.off_listening_mode),
                            description = stringResource(R.string.off_listening_mode_description),
                            checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(),
                            onCheckedChange = viewModel::setOffListeningMode
                        )
                    }
                }

                item(key = "spacer_about") { Spacer(modifier = Modifier.height(32.dp)) }
                item(key = "about") {
                    AboutCard(
                        navController = navController,
                        modelName = state.modelName,
                        actualModel = state.actualModel,
                        serialNumbers = state.serialNumbers,
                        version = state.version3,
                    )
                }

//                item(key = "spacer_debug") { Spacer(modifier = Modifier.height(16.dp)) }
//                item(key = "debug") { NavigationButton("debug", "Debug", navController) }
                item(key = "spacer_bottom") { Spacer(Modifier.height(bottomPadding)) }
            }
        } else {
            val backdrop = rememberLayerBackdrop()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBackdrop(
                        backdrop = rememberLayerBackdrop(),
                        exportedBackdrop = backdrop,
                        shape = { RoundedCornerShape(0.dp) },
                        highlight = {
                            Highlight.Ambient.copy(alpha = 0f)
                        },
                        effects = {})
                    .hazeSource(hazeState)
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val tapCount = remember { mutableIntStateOf(0) }
                val lastTapTime = remember { mutableLongStateOf(0L) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    val now = System.currentTimeMillis()

                                    if (now - lastTapTime.longValue > 400) {
                                        tapCount.intValue = 0
                                    }

                                    tapCount.intValue++
                                    lastTapTime.longValue = now

                                    if (tapCount.intValue >= 5) {
                                        tapCount.intValue = 0
                                        viewModel.activateDemoMode()
                                    }
                                })
                        }) {
                    Text(
                        text = stringResource(R.string.airpods_not_connected), style = TextStyle(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isSystemInDarkTheme()) Color.White else Color.Black,
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        ), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.airpods_not_connected_description),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Light,
                            color = if (isSystemInDarkTheme()) Color.White else Color.Black,
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(32.dp))
                if (!BuildConfig.PLAY_BUILD) {
                    StyledButton(
                        onClick = { navController.navigate("troubleshooting") },
                        backdrop = backdrop,
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                    ) {
                        Text(
                            text = stringResource(R.string.troubleshooting),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = if (isSystemInDarkTheme()) Color.White else Color.Black
                            )
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                StyledButton(
                    onClick = {
                        viewModel.reconnectFromSavedMac()
                    }, backdrop = backdrop, modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Text(
                        text = stringResource(R.string.reconnect_to_last_device), style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            color = if (isSystemInDarkTheme()) Color.White else Color.Black
                        )
                    )
                }
            }
        }
    }
}
