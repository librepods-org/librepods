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

// import me.kavishdevar.librepods.utils.RadareOffsetFinder
import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.presentation.components.NavigationButton
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledDropdown
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.components.StyledSlider
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import kotlin.io.encoding.ExperimentalEncodingApi

private var phoneMediaDebounceJob: Job? = null
private var toneVolumeDebounceJob: Job? = null
//private const val TAG = "AccessibilitySettings"

@SuppressLint("DefaultLocale")
@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun AccessibilitySettingsScreen(viewModel: AirPodsViewModel, navController: NavController) {
    val state by viewModel.uiState.collectAsState()

    val isDarkTheme = isSystemInDarkTheme()
    val textColor = if (isDarkTheme) Color.White else Color.Black

    val hearingAidEnabled =
        state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.HEARING_AID]?.getOrNull(
            1
        )
            ?.toInt() == 1 && state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.HEARING_AID]?.getOrNull(
            0
        )?.toInt() == 1

    val backdrop = rememberLayerBackdrop()

    StyledScaffold(
        title = stringResource(R.string.accessibility)
    ) { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .layerBackdrop(backdrop)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            if (!state.isPremium) {
                StyledButton(
                    onClick = {
                        navController.navigate("purchase_screen")
                    },
                    backdrop = rememberLayerBackdrop(),
                    modifier = Modifier.fillMaxWidth(),
                    maxScale = 0.05f,
                    surfaceColor = if (isSystemInDarkTheme()) Color(0xFF916100) else Color(0xFFE59900)
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
            }

//            val phoneMediaEQ = remember { mutableStateOf(FloatArray(8) { 0.5f }) }
//            val phoneEQEnabled = remember { mutableStateOf(false) }
//            val mediaEQEnabled = remember { mutableStateOf(false) }

            val pressSpeedOptions = mapOf(
                0.toByte() to stringResource(R.string.default_option),
                1.toByte() to stringResource(R.string.slower),
                2.toByte() to stringResource(R.string.slowest)
            )

            val selectedPressSpeedValue =
                state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL]?.getOrNull(
                    0
                )
            var selectedPressSpeed by remember {
                mutableStateOf(
                    pressSpeedOptions[selectedPressSpeedValue] ?: pressSpeedOptions[0]
                )
            }

            val pressAndHoldDurationOptions = mapOf(
                0.toByte() to stringResource(R.string.default_option),
                1.toByte() to stringResource(R.string.slower),
                2.toByte() to stringResource(R.string.slowest)
            )

            val selectedPressAndHoldDurationValue =
                state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CLICK_HOLD_INTERVAL]?.getOrNull(
                    0
                )
            var selectedPressAndHoldDuration by remember {
                mutableStateOf(
                    pressAndHoldDurationOptions[selectedPressAndHoldDurationValue]
                        ?: pressAndHoldDurationOptions[0]
                )
            }

            val volumeSwipeSpeedOptions = mapOf(
                1.toByte() to stringResource(R.string.default_option),
                2.toByte() to stringResource(R.string.longer),
                3.toByte() to stringResource(R.string.longest)
            )
            val selectedVolumeSwipeSpeedValue =
                state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL]?.getOrNull(
                    0
                )
            var selectedVolumeSwipeSpeed by remember {
                mutableStateOf(
                    volumeSwipeSpeedOptions[selectedVolumeSwipeSpeedValue]
                        ?: volumeSwipeSpeedOptions[1]
                )
            }

            val phoneMediaEQ = remember { mutableStateOf(FloatArray(8) { 0.5f }) }
            val phoneEQEnabled = remember { mutableStateOf(false) }
            val mediaEQEnabled = remember { mutableStateOf(false) }

            LaunchedEffect(phoneMediaEQ.value, phoneEQEnabled.value, mediaEQEnabled.value) {
                phoneMediaDebounceJob?.cancel()
                phoneMediaDebounceJob = CoroutineScope(Dispatchers.IO).launch {
                    delay(150)
                    try {
                        val phoneByte = if (phoneEQEnabled.value) 0x01.toByte() else 0x02.toByte()
                        val mediaByte = if (mediaEQEnabled.value) 0x01.toByte() else 0x02.toByte()
                        Log.d(
                            "AccessibilitySettingsScreen",
                            "Sending phone/media EQ (phoneEnabled=${phoneEQEnabled.value}, mediaEnabled=${mediaEQEnabled.value})"
                        )
                        viewModel.sendPhoneMediaEQ(phoneMediaEQ.value, phoneByte, mediaByte)
                    } catch (e: Exception) {
                        Log.w(
                            "AccessibilitySettingsScreen",
                            "Error sending phone/media EQ: ${e.message}"
                        )
                    }
                }
            }
            Box(
                modifier = Modifier.then(
                    if (!state.isPremium) {
                Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            } else Modifier)) {
                DropdownMenuComponent(
                    label = stringResource(R.string.press_speed),
                    description = stringResource(R.string.press_speed_description),
                    options = pressSpeedOptions.values.toList(),
                    selectedOption = selectedPressSpeed ?: stringResource(R.string.default_option),
                    onOptionSelected = { newValue ->
                        selectedPressSpeed = newValue
                        viewModel.setControlCommandByte(
                            identifier = AACPManager.Companion.ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL,
                            value = pressSpeedOptions.filterValues { it == newValue }.keys.firstOrNull()
                                ?: 0.toByte()
                        )
                    },
                    textColor = textColor,
                    hazeState = hazeState,
                    independent = true
                )
            }

            Box(
                modifier = Modifier.then(
                if (!state.isPremium) {
                Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            } else Modifier)) {
                DropdownMenuComponent(
                    label = stringResource(R.string.press_and_hold_duration),
                    description = stringResource(R.string.press_and_hold_duration_description),
                    options = pressAndHoldDurationOptions.values.toList(),
                    selectedOption = selectedPressAndHoldDuration
                        ?: stringResource(R.string.default_option),
                    onOptionSelected = { newValue ->
                        selectedPressAndHoldDuration = newValue
                        viewModel.setControlCommandByte(
                            identifier = AACPManager.Companion.ControlCommandIdentifiers.CLICK_HOLD_INTERVAL,
                            value = pressAndHoldDurationOptions.filterValues { it == newValue }.keys.firstOrNull()
                                ?: 0.toByte()
                        )
                    },
                    textColor = textColor,
                    hazeState = hazeState,
                    independent = true
                )
            }
            StyledToggle(
                title = stringResource(R.string.noise_control),
                label = stringResource(R.string.noise_cancellation_single_airpod),
                description = stringResource(R.string.noise_cancellation_single_airpod_description),
                independent = true,
                checked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.ONE_BUD_ANC_MODE]?.getOrNull(
                    0
                ) == 0x01.toByte(),
                onCheckedChange = {
                    viewModel.setControlCommandBoolean(
                        AACPManager.Companion.ControlCommandIdentifiers.ONE_BUD_ANC_MODE, it
                    )
                },
                enabled = state.isPremium
            )

            if (state.capabilities.contains(Capability.LOUD_SOUND_REDUCTION) && state.vendorIdHook) {
                StyledToggle(
                    label = stringResource(R.string.loud_sound_reduction),
                    description = stringResource(R.string.loud_sound_reduction_description),
                    checked = state.loudSoundReductionEnabled,
                    onCheckedChange = {
                        viewModel.setATTCharacteristicValue(
                            ATTHandles.LOUD_SOUND_REDUCTION,
                            if (it) byteArrayOf(0x01) else byteArrayOf(0x00)
                        )
                    },
                    enabled = state.isPremium
                )
            }

            if (!hearingAidEnabled && state.vendorIdHook) {
                NavigationButton(
                    to = "transparency_customization",
                    name = stringResource(R.string.customize_transparency_mode),
                    navController = navController,
                    enabled = state.isPremium
                )
            }

            val toneVolumeValue =
                state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CHIME_VOLUME]?.getOrNull(
                    0
                )?.toFloat() ?: 75f
            StyledSlider(
                label = stringResource(R.string.tone_volume),
                description = stringResource(R.string.tone_volume_description),
                value = toneVolumeValue,
                onValueChange = {
                    viewModel.setControlCommandValue(
                        AACPManager.Companion.ControlCommandIdentifiers.CHIME_VOLUME,
                        byteArrayOf(it.toInt().toByte(), 0x50)
                    )
                },
                valueRange = 0f..100f,
                snapPoints = listOf(75f),
                startIcon = "\uDBC0\uDEA1",
                endIcon = "\uDBC0\uDEA9",
                independent = true,
                enabled = state.isPremium
            )

            if (state.capabilities.contains(Capability.SWIPE_FOR_VOLUME)) {
                val volumeSwipeEnabled =
                    state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.VOLUME_SWIPE_MODE]?.getOrNull(
                        0
                    )?.toInt() == 0x01
                StyledToggle(
                    label = stringResource(R.string.volume_control),
                    description = stringResource(R.string.volume_control_description),
                    checked = volumeSwipeEnabled,
                    onCheckedChange = {
                        viewModel.setControlCommandBoolean(
                            AACPManager.Companion.ControlCommandIdentifiers.VOLUME_SWIPE_MODE, it
                        )
                    },
                    enabled = state.isPremium
                )

                Box(
                    modifier = Modifier.then(
                        if (!state.isPremium) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                } else Modifier)) {
                    DropdownMenuComponent(
                        label = stringResource(R.string.volume_swipe_speed),
                        description = stringResource(R.string.volume_swipe_speed_description),
                        options = volumeSwipeSpeedOptions.values.toList(),
                        selectedOption = selectedVolumeSwipeSpeed
                            ?: stringResource(R.string.default_option),
                        onOptionSelected = { newValue ->
                            selectedVolumeSwipeSpeed = newValue
                            viewModel.setControlCommandByte(
                                identifier = AACPManager.Companion.ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL,
                                value = volumeSwipeSpeedOptions.filterValues { it == newValue }.keys.firstOrNull()
                                    ?: 1.toByte()
                            )
                        },
                        textColor = textColor,
                        hazeState = hazeState,
                        independent = true
                    )
                }
            }

//            if (!hearingAidEnabled && XposedState.isAvailable) {
//                Text(
//                    text = stringResource(R.string.apply_eq_to), style = TextStyle(
//                        fontSize = 14.sp,
//                        fontWeight = FontWeight.Bold,
//                        color = textColor.copy(alpha = 0.6f),
//                        fontFamily = FontFamily(Font(R.font.sf_pro))
//                    ), modifier = Modifier.padding(8.dp, bottom = 0.dp)
//                )
//                Column(
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .background(
//                            if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF),
//                            RoundedCornerShape(28.dp)
//                        )
//                        .padding(vertical = 0.dp)
//                ) {
//                    val darkModeLocal = isSystemInDarkTheme()
//
//                    val phoneShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
//                    var phoneBackgroundColor by remember {
//                        mutableStateOf(
//                            if (darkModeLocal) Color(
//                                0xFF1C1C1E
//                            ) else Color(0xFFFFFFFF)
//                        )
//                    }
//                    val phoneAnimatedBackgroundColor by animateColorAsState(
//                        targetValue = phoneBackgroundColor,
//                        animationSpec = tween(durationMillis = 500)
//                    )
//
//                    Row(
//                        modifier = Modifier
//                            .height(48.dp)
//                            .fillMaxWidth()
//                            .background(phoneAnimatedBackgroundColor, phoneShape)
//                            .pointerInput(Unit) {
//                                detectTapGestures(
//                                    onPress = {
//                                        phoneBackgroundColor =
//                                            if (darkModeLocal) Color(0x40888888) else Color(
//                                                0x40D9D9D9
//                                            )
//                                        tryAwaitRelease()
//                                        phoneBackgroundColor =
//                                            if (darkModeLocal) Color(0xFF1C1C1E) else Color(
//                                                0xFFFFFFFF
//                                            )
//                                        phoneEQEnabled.value = !phoneEQEnabled.value
//                                    })
//                            }
//                            .padding(horizontal = 16.dp),
//                        verticalAlignment = Alignment.CenterVertically) {
//                        Text(
//                            stringResource(R.string.phone),
//                            fontSize = 16.sp,
//                            color = textColor,
//                            fontFamily = FontFamily(Font(R.font.sf_pro)),
//                            modifier = Modifier.weight(1f)
//                        )
//                        Checkbox(
//                            checked = phoneEQEnabled.value,
//                            onCheckedChange = { phoneEQEnabled.value = it },
//                            colors = CheckboxDefaults.colors().copy(
//                                checkedCheckmarkColor = Color(0xFF007AFF),
//                                uncheckedCheckmarkColor = Color.Transparent,
//                                checkedBoxColor = Color.Transparent,
//                                uncheckedBoxColor = Color.Transparent,
//                                checkedBorderColor = Color.Transparent,
//                                uncheckedBorderColor = Color.Transparent
//                            ),
//                            modifier = Modifier
//                                .height(24.dp)
//                                .scale(1.5f)
//                        )
//                    }
//
//                    HorizontalDivider(
//                        thickness = 1.dp, color = Color(0x40888888)
//                    )
//
//                    val mediaShape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
//                    var mediaBackgroundColor by remember {
//                        mutableStateOf(
//                            if (darkModeLocal) Color(
//                                0xFF1C1C1E
//                            ) else Color(0xFFFFFFFF)
//                        )
//                    }
//                    val mediaAnimatedBackgroundColor by animateColorAsState(
//                        targetValue = mediaBackgroundColor,
//                        animationSpec = tween(durationMillis = 500)
//                    )
//
//                    Row(
//                        modifier = Modifier
//                            .height(48.dp)
//                            .fillMaxWidth()
//                            .background(mediaAnimatedBackgroundColor, mediaShape)
//                            .pointerInput(Unit) {
//                                detectTapGestures(
//                                    onPress = {
//                                        mediaBackgroundColor =
//                                            if (darkModeLocal) Color(0x40888888) else Color(
//                                                0x40D9D9D9
//                                            )
//                                        tryAwaitRelease()
//                                        mediaBackgroundColor =
//                                            if (darkModeLocal) Color(0xFF1C1C1E) else Color(
//                                                0xFFFFFFFF
//                                            )
//                                        mediaEQEnabled.value = !mediaEQEnabled.value
//                                    })
//                            }
//                            .padding(horizontal = 16.dp),
//                        verticalAlignment = Alignment.CenterVertically) {
//                        Text(
//                            stringResource(R.string.media),
//                            fontSize = 16.sp,
//                            color = textColor,
//                            fontFamily = FontFamily(Font(R.font.sf_pro)),
//                            modifier = Modifier.weight(1f)
//                        )
//                        Checkbox(
//                            checked = mediaEQEnabled.value,
//                            onCheckedChange = { mediaEQEnabled.value = it },
//                            colors = CheckboxDefaults.colors().copy(
//                                checkedCheckmarkColor = Color(0xFF007AFF),
//                                uncheckedCheckmarkColor = Color.Transparent,
//                                checkedBoxColor = Color.Transparent,
//                                uncheckedBoxColor = Color.Transparent,
//                                checkedBorderColor = Color.Transparent,
//                                uncheckedBorderColor = Color.Transparent
//                            ),
//                            modifier = Modifier
//                                .height(24.dp)
//                                .scale(1.5f)
//                        )
//                    }
//                }
//
////                 EQ Settings. Don't seem to have an effect?
//                Column(
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .background(
//                            if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF),
//                            RoundedCornerShape(28.dp)
//                        )
//                        .padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally
//                ) {
//                    val trackColor = if (isDarkTheme) Color(0xFFB3B3B3) else Color(0xFF929491)
//                    val activeTrackColor = if (isDarkTheme) Color(0xFF007AFF) else Color(0xFF3C6DF5)
//                    val thumbColor = if (isDarkTheme) Color(0xFFFFFFFF) else Color(0xFFFFFFFF)
//
//                    for (i in 0 until 8) {
//                        val eqPhoneValue =
//                            remember(phoneMediaEQ.value[i]) { mutableFloatStateOf(phoneMediaEQ.value[i]) }
//                        Row(
//                            horizontalArrangement = Arrangement.SpaceBetween,
//                            verticalAlignment = Alignment.CenterVertically,
//                            modifier = Modifier
//                                .fillMaxWidth()
//                                .height(38.dp)
//                        ) {
//                            Text(
//                                text = String.format("%.2f", eqPhoneValue.floatValue),
//                                fontSize = 12.sp,
//                                color = textColor,
//                                modifier = Modifier.padding(bottom = 4.dp)
//                            )
//
//                            Slider(
//                                value = eqPhoneValue.floatValue,
//                                onValueChange = { newVal ->
//                                    eqPhoneValue.floatValue = newVal
//                                    val newEQ = phoneMediaEQ.value.copyOf()
//                                    newEQ[i] = eqPhoneValue.floatValue
//                                    phoneMediaEQ.value = newEQ
//                                },
//                                valueRange = 0f..100f,
//                                modifier = Modifier
//                                    .fillMaxWidth(0.9f)
//                                    .height(36.dp),
//                                colors = SliderDefaults.colors(
//                                    thumbColor = thumbColor,
//                                    activeTrackColor = activeTrackColor,
//                                    inactiveTrackColor = trackColor
//                                ),
//                                thumb = {
//                                    Box(
//                                        modifier = Modifier
//                                            .size(24.dp)
//                                            .shadow(4.dp, CircleShape)
//                                            .background(thumbColor, CircleShape)
//                                    )
//                                },
//                                track = {
//                                    Box(
//                                        modifier = Modifier
//                                            .fillMaxWidth()
//                                            .height(12.dp),
//                                        contentAlignment = Alignment.CenterStart
//                                    ) {
//                                        Box(
//                                            modifier = Modifier
//                                                .fillMaxWidth()
//                                                .height(4.dp)
//                                                .background(trackColor, RoundedCornerShape(4.dp))
//                                        )
//                                        Box(
//                                            modifier = Modifier
//                                                .fillMaxWidth(eqPhoneValue.floatValue / 100f)
//                                                .height(4.dp)
//                                                .background(
//                                                    activeTrackColor, RoundedCornerShape(4.dp)
//                                                )
//                                        )
//                                    }
//                                })
//
//                            Text(
//                                text = stringResource(R.string.band_label, i + 1),
//                                fontSize = 12.sp,
//                                color = textColor,
//                                modifier = Modifier.padding(top = 4.dp)
//                            )
//                        }
//                    }
//                }
//            }
            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

@ExperimentalHazeMaterialsApi
@Composable
private fun DropdownMenuComponent(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    textColor: Color,
    hazeState: HazeState,
    description: String? = null,
    independent: Boolean = true
) {
    val density = LocalDensity.current
    val itemHeightPx = with(density) { 48.dp.toPx() }

    var expanded by remember { mutableStateOf(false) }
    var touchOffset by remember { mutableStateOf<Offset?>(null) }
    var boxPosition by remember { mutableStateOf(Offset.Zero) }
    var lastDismissTime by remember { mutableLongStateOf(0L) }
    var parentHoveredIndex by remember { mutableStateOf<Int?>(null) }
    var parentDragActive by remember { mutableStateOf(false) }
    var previousIdx by remember { mutableStateOf<Int?>(null) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (independent) {
                        if (description != null) {
                            Modifier.padding(top = 8.dp, bottom = 4.dp)
                        } else {
                            Modifier.padding(vertical = 8.dp)
                        }
                    } else Modifier
                )
                .background(
                    if (independent) (if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color(
                        0xFFFFFFFF
                    )) else Color.Transparent,
                    if (independent) RoundedCornerShape(28.dp) else RoundedCornerShape(0.dp)
                ) then (if (independent) Modifier.padding(horizontal = 4.dp) else Modifier).clip(
                if (independent) RoundedCornerShape(28.dp) else RoundedCornerShape(0.dp)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp)
                    .height(58.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val now = System.currentTimeMillis()
                            if (expanded) {
                                expanded = false
                                lastDismissTime = now
                            } else {
                                if (now - lastDismissTime > 250L) {
                                    touchOffset = offset
                                    expanded = true
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(onDragStart = { offset ->
                            val now = System.currentTimeMillis()
                            touchOffset = offset
                            if (!expanded && now - lastDismissTime > 250L) {
                                expanded = true
                            }
                            lastDismissTime = now
                            parentDragActive = true
                            parentHoveredIndex = 0
                        }, onDrag = { change, _ ->
                            val current = change.position
                            val touch = touchOffset ?: current
                            val posInPopupY = current.y - touch.y
                            val idx = (posInPopupY / itemHeightPx).toInt()
                            if (idx != previousIdx) {
                                scope.launch {
                                    haptics.performHapticFeedback(
                                        HapticFeedbackType.SegmentTick
                                    )
                                }
                            }
                            parentHoveredIndex = idx
                            previousIdx = idx
                        }, onDragEnd = {
                            parentDragActive = false
                            parentHoveredIndex?.let { idx ->
                                if (idx in options.indices) {
                                    onOptionSelected(options[idx])
                                    expanded = false
                                    lastDismissTime = System.currentTimeMillis()
                                }
                            }
                            if (parentHoveredIndex != null && parentHoveredIndex in options.indices) {
                                scope.launch {
                                    haptics.performHapticFeedback(
                                        HapticFeedbackType.GestureEnd
                                    )
                                }
                            }
                            parentHoveredIndex = null
                        }, onDragCancel = {
                            parentDragActive = false
                            parentHoveredIndex = null
                        })
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = label,
                        fontSize = 16.sp,
                        color = textColor,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    if (!independent && description != null) {
                        Text(
                            text = description, style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Light,
                                color = textColor.copy(alpha = 0.6f),
                                fontFamily = FontFamily(Font(R.font.sf_pro))
                            ), modifier = Modifier.padding(16.dp, top = 0.dp, bottom = 2.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        boxPosition = coordinates.positionInParent()
                    }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedOption, style = TextStyle(
                                fontSize = 16.sp,
                                color = textColor.copy(alpha = 0.8f),
                                fontFamily = FontFamily(Font(R.font.sf_pro))
                            )
                        )
                        Text(
                            text = "􀆏", style = TextStyle(
                                fontSize = 16.sp,
                                color = textColor.copy(alpha = 0.6f),
                                fontFamily = FontFamily(Font(R.font.sf_pro))
                            ), modifier = Modifier.padding(start = 6.dp)
                        )
                    }

                    StyledDropdown(
                        expanded = expanded,
                        onDismissRequest = {
                            expanded = false
                            lastDismissTime = System.currentTimeMillis()
                        },
                        options = options,
                        selectedOption = selectedOption,
                        touchOffset = touchOffset,
                        boxPosition = boxPosition,
                        externalHoveredIndex = parentHoveredIndex,
                        externalDragActive = parentDragActive,
                        onOptionSelected = { option ->
                            onOptionSelected(option)
                            expanded = false
                        },
                        hazeState = hazeState
                    )
                }
            }
        }
        if (independent && description != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(
                        if (isSystemInDarkTheme()) Color(0xFF000000) else Color(0xFFF2F2F7)
                    )
            ) {
                Text(
                    text = description, style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Light,
                        color = (if (isSystemInDarkTheme()) Color.White else Color.Black).copy(
                            alpha = 0.6f
                        ),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
            }
        }
    }
}
