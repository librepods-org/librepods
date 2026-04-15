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

package me.kavishdevar.librepods.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.AboutCard
import me.kavishdevar.librepods.composables.AudioSettings
import me.kavishdevar.librepods.composables.BatteryView
import me.kavishdevar.librepods.composables.CallControlSettings
import me.kavishdevar.librepods.composables.ConfirmationDialog
import me.kavishdevar.librepods.composables.ConnectionSettings
import me.kavishdevar.librepods.composables.HearingHealthSettings
import me.kavishdevar.librepods.composables.MicrophoneSettings
import me.kavishdevar.librepods.composables.NavigationButton
import me.kavishdevar.librepods.composables.NoiseControlSettings
import me.kavishdevar.librepods.composables.PressAndHoldSettings
import me.kavishdevar.librepods.composables.StyledButton
import me.kavishdevar.librepods.composables.StyledIconButton
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.composables.StyledToggle
import me.kavishdevar.librepods.constants.AirPodsNotifications
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.ui.theme.LibrePodsTheme
import me.kavishdevar.librepods.utils.AACPManager
import me.kavishdevar.librepods.utils.Capability
import me.kavishdevar.librepods.utils.RadareOffsetFinder
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
@Composable
fun AirPodsSettingsScreen(dev: BluetoothDevice?, service: AirPodsService,
                          navController: NavController, isConnected: Boolean, isRemotelyConnected: Boolean) {
    var isLocallyConnected by remember { mutableStateOf(isConnected) }
    var isRemotelyConnected by remember { mutableStateOf(isRemotelyConnected) }
    val sharedPreferences = LocalContext.current.getSharedPreferences("settings", MODE_PRIVATE)
    var device by remember { mutableStateOf(dev) }
    var deviceName by remember {
        mutableStateOf(
            TextFieldValue(
                sharedPreferences.getString("name", device?.name ?: "AirPods Pro").toString()
            )
        )
    }

    LaunchedEffect(service) {
        isLocallyConnected = service.isConnectedLocally
    }

    val nameChangeListener = remember {
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "name") {
                deviceName = TextFieldValue(sharedPreferences.getString("name", "AirPods Pro").toString())
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
    val coroutineScope = rememberCoroutineScope()

    fun handleRemoteConnection(connected: Boolean) {
        isRemotelyConnected = connected
    }

    val context = LocalContext.current

    val connectionReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "me.kavishdevar.librepods.AIRPODS_CONNECTED_REMOTELY" -> {
                        coroutineScope.launch {
                            handleRemoteConnection(true)
                        }
                    }
                    "me.kavishdevar.librepods.AIRPODS_DISCONNECTED_REMOTELY" -> {
                        coroutineScope.launch {
                            handleRemoteConnection(false)
                        }
                    }
                    AirPodsNotifications.AIRPODS_CONNECTED -> {
                        coroutineScope.launch {
                            isLocallyConnected = true
                        }
                    }
                    AirPodsNotifications.AIRPODS_DISCONNECTED -> {
                        coroutineScope.launch {
                            isLocallyConnected = false
                        }
                    }
                    AirPodsNotifications.DISCONNECT_RECEIVERS -> {
                        try {
                            context?.unregisterReceiver(this)
                        } catch (e: IllegalArgumentException) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val filter = IntentFilter().apply {
            addAction("me.kavishdevar.librepods.AIRPODS_CONNECTED_REMOTELY")
            addAction("me.kavishdevar.librepods.AIRPODS_DISCONNECTED_REMOTELY")
            addAction(AirPodsNotifications.AIRPODS_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
            addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(connectionReceiver, filter, RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(connectionReceiver, filter)
        }
        onDispose {
            try {
                context.unregisterReceiver(connectionReceiver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(service) {
        service.let {
            it.sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
                putParcelableArrayListExtra("data", ArrayList(it.getBattery()))
            })
            it.sendBroadcast(Intent(AirPodsNotifications.ANC_DATA).apply {
                putExtra("data", it.getANC())
            })
        }
    }

    val darkMode = isSystemInDarkTheme()
    val hazeStateS = remember { mutableStateOf(HazeState()) }

    // val showDialog = remember { mutableStateOf(!sharedPreferences.getBoolean("donationDialogShown", false)) }

    val showDialog = remember { mutableStateOf(false) }

    StyledScaffold(
        title = deviceName.text,
        actionButtons = listOf(
            {scaffoldBackdrop ->
                StyledIconButton(
                    onClick = { navController.navigate("app_settings") },
                    icon = "􀍟",
                    darkMode = darkMode,
                    backdrop = scaffoldBackdrop
                )
            }
        ),
        snackbarHostState = snackbarHostState
    ) { spacerHeight, hazeState ->
        hazeStateS.value = hazeState
        if (isLocallyConnected || isRemotelyConnected) {
            val instance = service.airpodsInstance
            if (instance == null) {
                Text("Error: AirPods instance is null")
                return@StyledScaffold
            }
            val capabilities = instance.model.capabilities
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .padding(horizontal = 16.dp)
            ) {
                item(key = "spacer_top") { Spacer(modifier = Modifier.height(spacerHeight)) }
                item(key = "battery") {
                    BatteryView(service = service)
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
                val actAsAppleDeviceHookEnabled = RadareOffsetFinder.isSdpOffsetAvailable()
                if (actAsAppleDeviceHookEnabled) {
                    item(key = "spacer_hearing_health") { Spacer(modifier = Modifier.height(32.dp)) }
                    item(key = "hearing_health") {
                        HearingHealthSettings(navController = navController)
                    }
                }

                if (capabilities.contains(Capability.LISTENING_MODE)) {
                    item(key = "spacer_noise") { Spacer(modifier = Modifier.height(16.dp)) }
                    item(key = "noise_control") { NoiseControlSettings(service = service) }
                }

                if (capabilities.contains(Capability.STEM_CONFIG)) {
                    item(key = "spacer_press_hold") { Spacer(modifier = Modifier.height(16.dp)) }
                    item(key = "press_hold") { PressAndHoldSettings(navController = navController) }
                }

                item(key = "spacer_call") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "call_control") { CallControlSettings(hazeState = hazeState) }

                if (capabilities.contains(Capability.STEM_CONFIG)) {
                    item(key = "spacer_camera") { Spacer(modifier = Modifier.height(16.dp)) }
                    item(key = "camera_control") { NavigationButton(to = "camera_control", name = stringResource(R.string.camera_remote), description = stringResource(R.string.camera_control_description), title = stringResource(R.string.camera_control), navController = navController) }
                }

                item(key = "spacer_audio") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "audio") { AudioSettings(navController = navController) }

                item(key = "spacer_connection") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "connection") { ConnectionSettings() }

                item(key = "spacer_microphone") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "microphone") { MicrophoneSettings(hazeState) }

                if (capabilities.contains(Capability.SLEEP_DETECTION)) {
                    item(key = "spacer_sleep") { Spacer(modifier = Modifier.height(16.dp)) }
                    item(key = "sleep_detection") {
                        StyledToggle(
                            label = stringResource(R.string.sleep_detection),
                            controlCommandIdentifier = AACPManager.Companion.ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG
                        )
                    }
                }

                if (capabilities.contains(Capability.HEAD_GESTURES)) {
                    item(key = "spacer_head_tracking") { Spacer(modifier = Modifier.height(16.dp)) }
                    item(key = "head_tracking") { NavigationButton(to = "head_tracking", name = stringResource(R.string.head_gestures), navController = navController, currentState = if (sharedPreferences.getBoolean("head_gestures", false)) stringResource(R.string.on) else stringResource(R.string.off)) }
                }

                item(key = "spacer_accessibility") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "accessibility") { NavigationButton(to = "accessibility", name = stringResource(R.string.accessibility), navController = navController) }

                if (capabilities.contains(Capability.LOUD_SOUND_REDUCTION)){
                    item(key = "spacer_off_listening") { Spacer(modifier = Modifier.height(16.dp)) }
                    item(key = "off_listening") {
                        StyledToggle(
                            label = stringResource(R.string.off_listening_mode),
                            controlCommandIdentifier = AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION,
                            description = stringResource(R.string.off_listening_mode_description)
                        )
                    }
                }

                item(key = "spacer_about") { Spacer(modifier = Modifier.height(32.dp)) }
                item(key = "about") { AboutCard(navController = navController) }

                item(key = "spacer_debug") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "debug") { NavigationButton("debug", "Debug", navController) }
                item(key = "spacer_bottom") { Spacer(Modifier.height(24.dp)) }
            }
        }
        else {
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
                        }
                    )
                    .hazeSource(hazeState)
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.airpods_not_connected),
                    style = TextStyle(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isSystemInDarkTheme()) Color.White else Color.Black,
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
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
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(32.dp))
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
                StyledButton(
                    onClick = {
                        service.reconnectFromSavedMac()
                    },
                    backdrop = backdrop,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                ) {
                    Text(
                        text = stringResource(R.string.reconnect_to_last_device),
                        style = TextStyle(
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
    ConfirmationDialog(
        showDialog = showDialog,
        title = stringResource(R.string.support_librepods),
        message = stringResource(R.string.support_dialog_description),
        confirmText = stringResource(R.string.support_me) + " \uDBC0\uDEB5",
        dismissText = stringResource(R.string.never_show_again),
        onConfirm = {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                "https://github.com/sponsors/kavishdevar".toUri()
            )
            context.startActivity(browserIntent)
            sharedPreferences.edit { putBoolean("donationDialogShown", true) }
        },
        onDismiss = {
            sharedPreferences.edit { putBoolean("donationDialogShown", true) }
        },
        hazeState = hazeStateS.value,
    )
}

@Preview
@Composable
fun AirPodsSettingsScreenPreview() {
    Column (
        modifier = Modifier.height(2000.dp)
    ) {
        LibrePodsTheme (
            darkTheme = true
        ) {
            AirPodsSettingsScreen(dev = null, service = AirPodsService(), navController = rememberNavController(), isConnected = true, isRemotelyConnected = false)
        }
    }
}
