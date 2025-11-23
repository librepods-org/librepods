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
@file:Suppress("DEPRECATION")

package me.kavishdevar.librepods.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.UserHandle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.constants.AirPodsNotifications
import me.kavishdevar.librepods.constants.Battery
import me.kavishdevar.librepods.constants.BatteryComponent
import me.kavishdevar.librepods.constants.BatteryStatus
import me.kavishdevar.librepods.constants.StemAction
import me.kavishdevar.librepods.constants.isHeadTrackingData
import me.kavishdevar.librepods.utils.AACPManager
import me.kavishdevar.librepods.utils.AACPManager.Companion.StemPressType
import me.kavishdevar.librepods.utils.ATTManager
import me.kavishdevar.librepods.utils.AirPodsInstance
import me.kavishdevar.librepods.utils.AirPodsModels
import me.kavishdevar.librepods.utils.BLEManager
import me.kavishdevar.librepods.utils.BluetoothConnectionManager
//import me.kavishdevar.librepods.utils.CrossDevice
//import me.kavishdevar.librepods.utils.CrossDevicePackets
import me.kavishdevar.librepods.utils.GestureDetector
import me.kavishdevar.librepods.utils.HeadTracking
import me.kavishdevar.librepods.utils.IslandType
import me.kavishdevar.librepods.utils.IslandWindow
import me.kavishdevar.librepods.utils.MediaController
import me.kavishdevar.librepods.utils.PopupWindow
import me.kavishdevar.librepods.utils.SystemApisUtils
import me.kavishdevar.librepods.utils.SystemApisUtils.DEVICE_TYPE_UNTETHERED_HEADSET
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_COMPANION_APP
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_DEVICE_TYPE
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MAIN_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MANUFACTURER_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MODEL_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.widgets.BatteryWidget
import me.kavishdevar.librepods.widgets.NoiseControlWidget
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "AirPodsService"

object ServiceManager {
    @ExperimentalEncodingApi
    private var service: AirPodsService? = null

    @ExperimentalEncodingApi
    @Synchronized
    fun getService(): AirPodsService? {
        return service
    }

    @ExperimentalEncodingApi
    @Synchronized
    fun setService(service: AirPodsService?) {
        this.service = service
    }
}

// @Suppress("unused")
@ExperimentalEncodingApi
class AirPodsService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    var macAddress = ""
    var localMac = ""
    lateinit var aacpManager: AACPManager
    var attManager: ATTManager? = null
    var airpodsInstance: AirPodsInstance? = null
    var cameraActive = false
    private var disconnectedBecauseReversed = false
    private var otherDeviceTookOver = false
    data class ServiceConfig(
        var deviceName: String = "AirPods",
        var earDetectionEnabled: Boolean = true,
        var conversationalAwarenessPauseMusic: Boolean = false,
        var showPhoneBatteryInWidget: Boolean = true,
        var relativeConversationalAwarenessVolume: Boolean = true,
        var headGestures: Boolean = true,
        var disconnectWhenNotWearing: Boolean = false,
        var conversationalAwarenessVolume: Int = 43,
        var qsClickBehavior: String = "cycle",
        var bleOnlyMode: Boolean = false,

        // AirPods state-based takeover
        var takeoverWhenDisconnected: Boolean = true,
        var takeoverWhenIdle: Boolean = true,
        var takeoverWhenMusic: Boolean = false,
        var takeoverWhenCall: Boolean = true,

        // Phone state-based takeover
        var takeoverWhenRingingCall: Boolean = true,
        var takeoverWhenMediaStart: Boolean = true,

        var leftSinglePressAction: StemAction = StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,
        var rightSinglePressAction: StemAction = StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,

        var leftDoublePressAction: StemAction = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,
        var rightDoublePressAction: StemAction = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,

        var leftTriplePressAction: StemAction = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,
        var rightTriplePressAction: StemAction = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,

        var leftLongPressAction: StemAction = StemAction.defaultActions[StemPressType.LONG_PRESS]!!,
        var rightLongPressAction: StemAction = StemAction.defaultActions[StemPressType.LONG_PRESS]!!,

        var cameraAction: StemPressType? = null,

        // AirPods device information
        var airpodsName: String = "",
        var airpodsModelNumber: String = "",
        var airpodsManufacturer: String = "",
        var airpodsSerialNumber: String = "",
        var airpodsLeftSerialNumber: String = "",
        var airpodsRightSerialNumber: String = "",
        var airpodsVersion1: String = "",
        var airpodsVersion2: String = "",
        var airpodsVersion3: String = "",
        var airpodsHardwareRevision: String = "",
        var airpodsUpdaterIdentifier: String = "",

        // phone's mac, needed for tipi
        var selfMacAddress: String = ""
    )

    private lateinit var config: ServiceConfig

    inner class LocalBinder : Binder() {
        fun getService(): AirPodsService = this@AirPodsService
    }

    private lateinit var sharedPreferencesLogs: SharedPreferences
    private lateinit var sharedPreferences: SharedPreferences
    private val packetLogKey = "packet_log"
    private val _packetLogsFlow = MutableStateFlow<Set<String>>(emptySet())
    val packetLogsFlow: StateFlow<Set<String>> get() = _packetLogsFlow

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var phoneStateListener: PhoneStateListener
    private val maxLogEntries = 1000
    private val inMemoryLogs = mutableSetOf<String>()

    private var handleIncomingCallOnceConnected = false

    lateinit var bleManager: BLEManager

    private lateinit var socket: BluetoothSocket

    private val bleStatusListener = object : BLEManager.AirPodsStatusListener {
        @SuppressLint("NewApi")
        override fun onDeviceStatusChanged(
            device: BLEManager.AirPodsStatus,
            previousStatus: BLEManager.AirPodsStatus?
        ) {
            // Store MAC address for BLE-only mode if not already stored
            if (config.bleOnlyMode && macAddress.isEmpty()) {
                macAddress = device.address
                sharedPreferences.edit {
                    putString("mac_address", macAddress)
                }
                Log.d(TAG, "BLE-only mode: stored MAC address ${device.address}")
            }

            if (device.connectionState == "Disconnected" && !config.bleOnlyMode) {
                Log.d(TAG, "Seems no device has taken over, we will.")
                val bluetoothManager = getSystemService(BluetoothManager::class.java)
                val bluetoothDevice = bluetoothManager.adapter.getRemoteDevice(sharedPreferences.getString(
                    "mac_address", "") ?: "")
                connectToSocket(bluetoothDevice)
            }
            Log.d(TAG, "Device status changed")
            if (isConnectedLocally) return
            val leftLevel = bleManager.getMostRecentStatus()?.leftBattery?: 0
            val rightLevel = bleManager.getMostRecentStatus()?.rightBattery?: 0
            val caseLevel = bleManager.getMostRecentStatus()?.caseBattery?: 0
            val leftCharging = bleManager.getMostRecentStatus()?.isLeftCharging
            val rightCharging = bleManager.getMostRecentStatus()?.isRightCharging
            val caseCharging = bleManager.getMostRecentStatus()?.isCaseCharging

            batteryNotification.setBatteryDirect(
                leftLevel = leftLevel,
                leftCharging = leftCharging == true,
                rightLevel = rightLevel,
                rightCharging = rightCharging == true,
                caseLevel = caseLevel,
                caseCharging = caseCharging == true
            )
            updateBattery()
        }

        override fun onBroadcastFromNewAddress(device: BLEManager.AirPodsStatus) {
            Log.d(TAG, "New address detected")
        }

        override fun onLidStateChanged(
            lidOpen: Boolean,
        ) {
            if (lidOpen) {
                Log.d(TAG, "Lid opened")
                showPopup(
                    this@AirPodsService,
                    getSharedPreferences("settings", MODE_PRIVATE).getString("name", "AirPods Pro") ?: "AirPods"
                )
                if (isConnectedLocally) return
                val leftLevel = bleManager.getMostRecentStatus()?.leftBattery?: 0
                val rightLevel = bleManager.getMostRecentStatus()?.rightBattery?: 0
                val caseLevel = bleManager.getMostRecentStatus()?.caseBattery?: 0
                val leftCharging = bleManager.getMostRecentStatus()?.isLeftCharging
                val rightCharging = bleManager.getMostRecentStatus()?.isRightCharging
                val caseCharging = bleManager.getMostRecentStatus()?.isCaseCharging

                batteryNotification.setBatteryDirect(
                    leftLevel = leftLevel,
                    leftCharging = leftCharging == true,
                    rightLevel = rightLevel,
                    rightCharging = rightCharging == true,
                    caseLevel = caseLevel,
                    caseCharging = caseCharging == true
                )
                sendBatteryBroadcast()
            } else {
                Log.d(TAG, "Lid closed")
            }
        }

        override fun onEarStateChanged(
            device: BLEManager.AirPodsStatus,
            leftInEar: Boolean,
            rightInEar: Boolean
        ) {
            Log.d(TAG, "Ear state changed - Left: $leftInEar, Right: $rightInEar")

            // In BLE-only mode, ear detection is purely based on BLE data
            if (config.bleOnlyMode) {
                Log.d(TAG, "BLE-only mode: ear detection from BLE data")
            }
        }

        override fun onBatteryChanged(device: BLEManager.AirPodsStatus) {
            if (isConnectedLocally) return
            val leftLevel = bleManager.getMostRecentStatus()?.leftBattery?: 0
            val rightLevel = bleManager.getMostRecentStatus()?.rightBattery?: 0
            val caseLevel = bleManager.getMostRecentStatus()?.caseBattery?: 0
            val leftCharging = bleManager.getMostRecentStatus()?.isLeftCharging
            val rightCharging = bleManager.getMostRecentStatus()?.isRightCharging
            val caseCharging = bleManager.getMostRecentStatus()?.isCaseCharging

            batteryNotification.setBatteryDirect(
                leftLevel = leftLevel,
                leftCharging = leftCharging == true,
                rightLevel = rightLevel,
                rightCharging = rightCharging == true,
                caseLevel = caseLevel,
                caseCharging = caseCharging == true
            )
            updateBattery()
            Log.d(TAG, "Battery changed")
        }

        override fun onDeviceDisappeared() {
            Log.d(TAG, "All disappeared")
            updateNotificationContent(
                false
            )
        }
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()

        sharedPreferencesLogs = getSharedPreferences("packet_logs", MODE_PRIVATE)

        inMemoryLogs.addAll(sharedPreferencesLogs.getStringSet(packetLogKey, emptySet()) ?: emptySet())
        _packetLogsFlow.value = inMemoryLogs.toSet()

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        initializeConfig()

        aacpManager = AACPManager()
        initializeAACPManagerCallback()

        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        localMac = config.selfMacAddress
        if (localMac.isEmpty()) {
            localMac = try {
                val process = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "settings get secure bluetooth_address")
                )

                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    process.inputStream.bufferedReader().use { it.readLine()?.trim().orEmpty() }
                } else {
                    ""
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving local MAC address: ${e.message}. We probably aren't rooted.")
                ""
            }
            config.selfMacAddress = localMac
            sharedPreferences.edit {
                putString("self_mac_address", localMac)
            }
        }

        ServiceManager.setService(this)
        startForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            initGestureDetector()
        } else {
            gestureDetector = null
            config.headGestures = false
            sharedPreferences.edit { putBoolean("head_gestures", false) }
            Log.d(TAG, "Head gestures disabled as device is running Android 9 or below")
        }

        bleManager = BLEManager(this)
        bleManager.setAirPodsStatusListener(bleStatusListener)

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)

        with(sharedPreferences) {
            edit {
                if (!contains("conversational_awareness_pause_music")) putBoolean(
                    "conversational_awareness_pause_music",
                    false
                )
                if (!contains("personalized_volume")) putBoolean("personalized_volume", false)
                if (!contains("automatic_ear_detection")) putBoolean(
                    "automatic_ear_detection",
                    true
                )
                if (!contains("long_press_nc")) putBoolean("long_press_nc", true)
                if (!contains("show_phone_battery_in_widget")) putBoolean(
                    "show_phone_battery_in_widget",
                    true
                )
                if (!contains("single_anc")) putBoolean("single_anc", true)
                if (!contains("long_press_transparency")) putBoolean(
                    "long_press_transparency",
                    true
                )
                if (!contains("conversational_awareness")) putBoolean(
                    "conversational_awareness",
                    true
                )
                if (!contains("relative_conversational_awareness_volume")) putBoolean(
                    "relative_conversational_awareness_volume",
                    true
                )
                if (!contains("long_press_adaptive")) putBoolean("long_press_adaptive", true)
                if (!contains("loud_sound_reduction")) putBoolean("loud_sound_reduction", true)
                if (!contains("long_press_off")) putBoolean("long_press_off", false)
                if (!contains("volume_control")) putBoolean("volume_control", true)
                if (!contains("head_gestures")) putBoolean("head_gestures", true)
                if (!contains("disconnect_when_not_wearing")) putBoolean(
                    "disconnect_when_not_wearing",
                    false
                )

                // AirPods state-based takeover
                if (!contains("takeover_when_disconnected")) putBoolean(
                    "takeover_when_disconnected",
                    true
                )
                if (!contains("takeover_when_idle")) putBoolean("takeover_when_idle", true)
                if (!contains("takeover_when_music")) putBoolean("takeover_when_music", false)
                if (!contains("takeover_when_call")) putBoolean("takeover_when_call", true)

                // Phone state-based takeover
                if (!contains("takeover_when_ringing_call")) putBoolean(
                    "takeover_when_ringing_call",
                    true
                )
                if (!contains("takeover_when_media_start")) putBoolean(
                    "takeover_when_media_start",
                    true
                )

                if (!contains("adaptive_strength")) putInt("adaptive_strength", 51)
                if (!contains("tone_volume")) putInt("tone_volume", 75)
                if (!contains("conversational_awareness_volume")) putInt(
                    "conversational_awareness_volume",
                    43
                )

                if (!contains("qs_click_behavior")) putString("qs_click_behavior", "cycle")
                if (!contains("name")) putString("name", "AirPods")

                if (!contains("left_single_press_action")) putString(
                    "left_single_press_action",
                    StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!.name
                )
                if (!contains("right_single_press_action")) putString(
                    "right_single_press_action",
                    StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!.name
                )
                if (!contains("left_double_press_action")) putString(
                    "left_double_press_action",
                    StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!.name
                )
                if (!contains("right_double_press_action")) putString(
                    "right_double_press_action",
                    StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!.name
                )
                if (!contains("left_triple_press_action")) putString(
                    "left_triple_press_action",
                    StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!.name
                )
                if (!contains("right_triple_press_action")) putString(
                    "right_triple_press_action",
                    StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!.name
                )
                if (!contains("left_long_press_action")) putString(
                    "left_long_press_action",
                    StemAction.defaultActions[StemPressType.LONG_PRESS]!!.name
                )
                if (!contains("right_long_press_action")) putString(
                    "right_long_press_action",
                    StemAction.defaultActions[StemPressType.LONG_PRESS]!!.name
                )
                if (!contains("camera_action")) putString("camera_action", "SINGLE_PRESS")

            }
        }

        initializeConfig()

        ancModeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "me.kavishdevar.librepods.SET_ANC_MODE") {
                    if (intent.hasExtra("mode")) {
                        val mode = intent.getIntExtra("mode", -1)
                        if (mode in 1..4) {
                            aacpManager.sendControlCommand(
                                AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
                                mode
                            )
                        }
                    } else {
                        val currentMode = ancNotification.status
                        val allowOffModeValue = aacpManager.controlCommandStatusList.find { it.identifier == AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION }
                        val allowOffMode = allowOffModeValue?.value?.takeIf { it.isNotEmpty() }?.get(0) == 0x01.toByte()

                        val nextMode = if (allowOffMode) {
                            when (currentMode) {
                                1 -> 2
                                2 -> 3
                                3 -> 4
                                4 -> 1
                                else -> 1
                            }
                        } else {
                            when (currentMode) {
                                1 -> 2
                                2 -> 3
                                3 -> 4
                                4 -> 2
                                else -> 2
                            }
                        }

                        aacpManager.sendControlCommand(
                            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
                            nextMode
                        )
                        Log.d(TAG, "Cycling ANC mode from $currentMode to $nextMode (offListeningMode: $allowOffMode)")
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ancModeReceiver, ancModeFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(ancModeReceiver, ancModeFilter)
        }
        val audioManager =
            this@AirPodsService.getSystemService(AUDIO_SERVICE) as AudioManager
        MediaController.initialize(
            audioManager,
            this@AirPodsService.getSharedPreferences(
                "settings",
                MODE_PRIVATE
            )
        )
//        Log.d(TAG, "Initializing CrossDevice")
//        CoroutineScope(Dispatchers.IO).launch {
//            CrossDevice.init(this@AirPodsService)
//            Log.d(TAG, "CrossDevice initialized")
//        }

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        macAddress = sharedPreferences.getString("mac_address", "") ?: ""

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            @SuppressLint("SwitchIntDef", "NewApi")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        val leAvailableForAudio = bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true
//                        if ((CrossDevice.isAvailable && !isConnectedLocally && earDetectionNotification.status.contains(0x00)) || leAvailableForAudio) CoroutineScope(Dispatchers.IO).launch {
                        if (leAvailableForAudio) runBlocking {
                            takeOver("call")
                        }
                        if (config.headGestures) {
                            callNumber = phoneNumber
                            handleIncomingCall()
                        }
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        val leAvailableForAudio = bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true
//                        if ((CrossDevice.isAvailable && !isConnectedLocally && earDetectionNotification.status.contains(0x00)) || leAvailableForAudio) CoroutineScope(
                        if (leAvailableForAudio) CoroutineScope(
                            Dispatchers.IO).launch {
                                takeOver("call")
                        }
                        isInCall = true
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        isInCall = false
                        callNumber = null
                        gestureDetector?.stopDetection()
                    }
                }
            }
        }
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)

        if (config.showPhoneBatteryInWidget) {
            widgetMobileBatteryEnabled = true
            val batteryChangedIntentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            batteryChangedIntentFilter.addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    BatteryChangedIntentReceiver,
                    batteryChangedIntentFilter,
                    RECEIVER_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(BatteryChangedIntentReceiver, batteryChangedIntentFilter)
            }
        }
        val serviceIntentFilter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.device.action.BOND_STATE_CHANGED")
            addAction("android.bluetooth.device.action.NAME_CHANGED")
            addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED")
        }

        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AirPodsNotifications.AIRPODS_CONNECTION_DETECTED) {
                    device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("device", BluetoothDevice::class.java)!!
                    } else {
                        intent.getParcelableExtra("device") as BluetoothDevice?
                    }

                    if (config.deviceName == "AirPods" && device?.name != null) {
                        config.deviceName = device?.name ?: "AirPods"
                        sharedPreferences.edit { putString("name", config.deviceName) }
                    }

//                    Log.d("AirPodsCrossDevice", CrossDevice.isAvailable.toString())
//                    if (!CrossDevice.isAvailable) {
                        Log.d(TAG, "${config.deviceName} connected")
                        CoroutineScope(Dispatchers.IO).launch {
                            connectToSocket(device!!)
                        }
                        Log.d(TAG, "Setting metadata")
                        setMetadatas(device!!)
                        isConnectedLocally = true
                        macAddress = device!!.address
                        sharedPreferences.edit {
                            putString("mac_address", macAddress)
                        }
//                    }

                } else if (intent?.action == AirPodsNotifications.AIRPODS_DISCONNECTED) {
                    device = null
                    isConnectedLocally = false
                    popupShown = false
                    updateNotificationContent(false)
                    attManager?.disconnect()
                    attManager = null
                }
            }
        }
         val showIslandReceiver = object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "me.kavishdevar.librepods.cross_device_island") {
                    showIsland(this@AirPodsService, batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.level!!.coerceAtMost(batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.level!!))
                } else if (intent?.action == AirPodsNotifications.DISCONNECT_RECEIVERS) {
                    try {
                        context?.unregisterReceiver(this)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        val showIslandIntentFilter = IntentFilter().apply {
            addAction("me.kavishdevar.librepods.cross_device_island")
            addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(showIslandReceiver, showIslandIntentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(showIslandReceiver, showIslandIntentFilter)
        }

        val deviceIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, deviceIntentFilter, RECEIVER_EXPORTED)
            registerReceiver(bluetoothReceiver, serviceIntentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(connectionReceiver, deviceIntentFilter)
            registerReceiver(bluetoothReceiver, serviceIntentFilter)
        }

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter.bondedDevices.forEach { device ->
            device.fetchUuidsWithSdp()
            if (device.uuids != null) {
                if (device.uuids.contains(ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"))) {
                    bluetoothAdapter.getProfileProxy(
                        this,
                        object : BluetoothProfile.ServiceListener {
                            @SuppressLint("NewApi")
                            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                                if (profile == BluetoothProfile.A2DP) {
                                    val connectedDevices = proxy.connectedDevices
                                    if (connectedDevices.isNotEmpty()) {
//                                        if (!CrossDevice.isAvailable) {
                                            CoroutineScope(Dispatchers.IO).launch {
                                                connectToSocket(device)
                                            }
                                            setMetadatas(device)
                                            macAddress = device.address
                                            sharedPreferences.edit {
                                                putString("mac_address", macAddress)
                                            }
//                                        }
                                        this@AirPodsService.sendBroadcast(
                                            Intent(AirPodsNotifications.AIRPODS_CONNECTED)
                                        )
                                    }
                                }
                                bluetoothAdapter.closeProfileProxy(profile, proxy)
                            }

                            override fun onServiceDisconnected(profile: Int) {}
                        },
                        BluetoothProfile.A2DP
                    )
                }
            }
        }

//        if (!isConnectedLocally && !CrossDevice.isAvailable) {
//            clearPacketLogs()
//        }

        CoroutineScope(Dispatchers.IO).launch {
            bleManager.startScanning()
        }
    }

    @Suppress("unused")
    fun cameraOpened() {
        Log.d(TAG, "Camera opened, gonna handle stem presses and take action if enabled")
        cameraActive = true
        setupStemActions()
    }

    @Suppress("unused")
    fun cameraClosed() {
        cameraActive = false
        setupStemActions()
    }

    fun isCustomAction(
        action: StemAction?,
        default: StemAction?
    ): Boolean {
        return action != default
    }

    fun setupStemActions() {
        val singlePressDefault = StemAction.defaultActions[StemPressType.SINGLE_PRESS]
        val doublePressDefault = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]
        val triplePressDefault = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]
        val longPressDefault   = StemAction.defaultActions[StemPressType.LONG_PRESS]

        val singlePressCustomized = isCustomAction(config.leftSinglePressAction, singlePressDefault) ||
            isCustomAction(config.rightSinglePressAction, singlePressDefault) ||
            (cameraActive && config.cameraAction == StemPressType.SINGLE_PRESS)
        val doublePressCustomized = isCustomAction(config.leftDoublePressAction, doublePressDefault) ||
            isCustomAction(config.rightDoublePressAction, doublePressDefault)
        val triplePressCustomized = isCustomAction(config.leftTriplePressAction, triplePressDefault) ||
            isCustomAction(config.rightTriplePressAction, triplePressDefault)
        val longPressCustomized = isCustomAction(config.leftLongPressAction, longPressDefault) ||
            isCustomAction(config.rightLongPressAction, longPressDefault) ||
            (cameraActive && config.cameraAction == StemPressType.LONG_PRESS)
        Log.d(TAG, "Setting up stem actions: " +
            "Single Press Customized: $singlePressCustomized, " +
            "Double Press Customized: $doublePressCustomized, " +
            "Triple Press Customized: $triplePressCustomized, " +
            "Long Press Customized: $longPressCustomized")
        aacpManager.sendStemConfigPacket(
            singlePressCustomized,
            doublePressCustomized,
            triplePressCustomized,
            longPressCustomized,
        )
    }

    @ExperimentalEncodingApi
    private fun initializeAACPManagerCallback() {
        aacpManager.setPacketCallback(object : AACPManager.PacketCallback {
            @SuppressLint("MissingPermission")
            override fun onBatteryInfoReceived(batteryInfo: ByteArray) {
                batteryNotification.setBattery(batteryInfo)
                sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
                    putParcelableArrayListExtra("data", ArrayList(batteryNotification.getBattery()))
                })
                updateBattery()
                updateNotificationContent(
                    true,
                    this@AirPodsService.getSharedPreferences("settings", MODE_PRIVATE)
                        .getString("name", device?.name),
                    batteryNotification.getBattery()
                )
//                CrossDevice.sendRemotePacket(batteryInfo)
//                CrossDevice.batteryBytes = batteryInfo

                for (battery in batteryNotification.getBattery()) {
                    Log.d(
                        "AirPodsParser",
                        "${battery.getComponentName()}: ${battery.getStatusName()} at ${battery.level}% "
                    )
                }

                if (batteryNotification.getBattery()[0].status == BatteryStatus.CHARGING && batteryNotification.getBattery()[1].status == BatteryStatus.CHARGING) {
                    disconnectAudio(this@AirPodsService, device)
                } else {
                    connectAudio(this@AirPodsService, device)
                }
            }

            override fun onEarDetectionReceived(earDetection: ByteArray) {
                sendBroadcast(Intent(AirPodsNotifications.EAR_DETECTION_DATA).apply {
                    val list = earDetectionNotification.status
                    val bytes = ByteArray(2)
                    bytes[0] = list[0]
                    bytes[1] = list[1]
                    putExtra("data", bytes)
                })
                Log.d(
                    "AirPodsParser",
                    "Ear Detection: ${earDetectionNotification.status[0]} ${earDetectionNotification.status[1]}"
                )
                processEarDetectionChange(earDetection)
            }

            override fun onConversationAwarenessReceived(conversationAwareness: ByteArray) {
                conversationAwarenessNotification.setData(conversationAwareness)
                sendBroadcast(Intent(AirPodsNotifications.CA_DATA).apply {
                    putExtra("data", conversationAwarenessNotification.status)
                })

                if (conversationAwarenessNotification.status == 1.toByte() || conversationAwarenessNotification.status == 2.toByte()) {
                    MediaController.startSpeaking()
                } else if (conversationAwarenessNotification.status == 8.toByte() || conversationAwarenessNotification.status == 9.toByte()) {
                    MediaController.stopSpeaking()
                }

                Log.d(
                    "AirPodsParser",
                    "Conversation Awareness: ${conversationAwarenessNotification.status}"
                )
            }

            override fun onControlCommandReceived(controlCommand: ByteArray) {
                val command = AACPManager.ControlCommand.fromByteArray(controlCommand)
                if (command.identifier == AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value) {
                    ancNotification.setStatus(byteArrayOf(command.value.takeIf { it.isNotEmpty() }?.get(0) ?: 0x00.toByte()))
                    sendANCBroadcast()
                    updateNoiseControlWidget()
                }
            }

            override fun onOwnershipChangeReceived(owns: Boolean) {
                if (!owns) {
                    MediaController.recentlyLostOwnership = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        MediaController.recentlyLostOwnership = false
                    }, 3000)
                    Log.d(TAG, "ownership lost")
                    MediaController.sendPause()
                    MediaController.pausedForOtherDevice = true
                    otherDeviceTookOver = true
                    disconnectAudio(
                        this@AirPodsService,
                        device
                    )
                }
            }

            override fun onOwnershipToFalseRequest(sender: String, reasonReverseTapped: Boolean) {
                // TODO: Show a reverse button, but that's a lot of effort -- i'd have to change the UI too, which i hate doing, and handle other device's reverses too, and disconnect audio etc... so for now, just pause the audio and show the island without asking to reverse.
                // handling reverse is a problem because we'd have to disconnect the audio, but there's no option connect audio again natively, so notification would have to be changed. I wish there was a way to just "change the audio output device".
                // (20 minutes later) i've done it nonetheless :]
                val senderName = aacpManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
                Log.d(TAG, "other device has hijacked the connection, reasonReverseTapped: $reasonReverseTapped")
                aacpManager.sendControlCommand(
                    AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                    byteArrayOf(0x00)
                )
                otherDeviceTookOver = true
                disconnectAudio(
                    this@AirPodsService,
                    device
                )
                if (reasonReverseTapped) {
                    Log.d(TAG, "reverse tapped, disconnecting audio")
                    disconnectedBecauseReversed = true
                    disconnectAudio(this@AirPodsService, device)
                    showIsland(
                        this@AirPodsService,
                        (batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.level?: 0).coerceAtMost(batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.level?: 0),
                        IslandType.MOVED_TO_OTHER_DEVICE,
                        reversed = true,
                        otherDeviceName = senderName
                    )
                }
                if (!aacpManager.owns) {
                    showIsland(
                        this@AirPodsService,
                        (batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.level?: 0).coerceAtMost(batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.level?: 0),
                        IslandType.MOVED_TO_OTHER_DEVICE,
                        reversed = reasonReverseTapped,
                        otherDeviceName = senderName
                    )
                }
                MediaController.sendPause()
            }

            override fun onShowNearbyUI(sender: String) {
                val senderName = aacpManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
                showIsland(
                    this@AirPodsService,
                    (batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.level?: 0).coerceAtMost(batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.level?: 0),
                    IslandType.MOVED_TO_OTHER_DEVICE,
                    reversed = false,
                    otherDeviceName = senderName
                )
            }

            override fun onDeviceInformationReceived(deviceInformation: AACPManager.Companion.AirPodsInformation) {
                Log.d(
                    "AirPodsParser",
                    "Device Information: name: ${deviceInformation.name}, modelNumber: ${deviceInformation.modelNumber}, manufacturer: ${deviceInformation.manufacturer}, serialNumber: ${deviceInformation.serialNumber}, version1: ${deviceInformation.version1}, version2: ${deviceInformation.version2}, hardwareRevision: ${deviceInformation.hardwareRevision}, updaterIdentifier: ${deviceInformation.updaterIdentifier}, leftSerialNumber: ${deviceInformation.leftSerialNumber}, rightSerialNumber: ${deviceInformation.rightSerialNumber}, version3: ${deviceInformation.version3}"
                )
                // Store in SharedPreferences
                sharedPreferences.edit {
                    putString("airpods_name", deviceInformation.name)
                    putString("airpods_model_number", deviceInformation.modelNumber)
                    putString("airpods_manufacturer", deviceInformation.manufacturer)
                    putString("airpods_serial_number", deviceInformation.serialNumber)
                    putString("airpods_left_serial_number", deviceInformation.leftSerialNumber)
                    putString("airpods_right_serial_number", deviceInformation.rightSerialNumber)
                    putString("airpods_version1", deviceInformation.version1)
                    putString("airpods_version2", deviceInformation.version2)
                    putString("airpods_version3", deviceInformation.version3)
                    putString("airpods_hardware_revision", deviceInformation.hardwareRevision)
                    putString("airpods_updater_identifier", deviceInformation.updaterIdentifier)
                }
                // Update config
                config.airpodsName = deviceInformation.name
                config.airpodsModelNumber = deviceInformation.modelNumber
                config.airpodsManufacturer = deviceInformation.manufacturer
                config.airpodsSerialNumber = deviceInformation.serialNumber
                config.airpodsLeftSerialNumber = deviceInformation.leftSerialNumber
                config.airpodsRightSerialNumber = deviceInformation.rightSerialNumber
                config.airpodsVersion1 = deviceInformation.version1
                config.airpodsVersion2 = deviceInformation.version2
                config.airpodsVersion3 = deviceInformation.version3
                config.airpodsHardwareRevision = deviceInformation.hardwareRevision
                config.airpodsUpdaterIdentifier = deviceInformation.updaterIdentifier

                val model = AirPodsModels.getModelByModelNumber(config.airpodsModelNumber)
                if (model != null) {
                    airpodsInstance = AirPodsInstance(
                        name = config.airpodsName,
                        model = model,
                        actualModelNumber = config.airpodsModelNumber,
                        serialNumber = config.airpodsSerialNumber,
                        leftSerialNumber = config.airpodsLeftSerialNumber,
                        rightSerialNumber = config.airpodsRightSerialNumber,
                        version1 = config.airpodsVersion1,
                        version2 = config.airpodsVersion2,
                        version3 = config.airpodsVersion3,
                        aacpManager = aacpManager,
                        attManager = attManager
                    )
                }
            }

            @SuppressLint("NewApi")
            override fun onHeadTrackingReceived(headTracking: ByteArray) {
                if (isHeadTrackingActive) {
                    HeadTracking.processPacket(headTracking)
                    processHeadTrackingData(headTracking)
                }
            }

            override fun onProximityKeysReceived(proximityKeys: ByteArray) {
                val keys = aacpManager.parseProximityKeysResponse(proximityKeys)
                Log.d("AirPodsParser", "Proximity keys: $keys")
                sharedPreferences.edit {
                    for (key in keys) {
                        Log.d("AirPodsParser", "Proximity key: ${key.key.name} = ${key.value}")
                        putString(key.key.name, Base64.encode(key.value))
                    }
                }
            }

            override fun onStemPressReceived(stemPress: ByteArray) {
                val (stemPressType, bud) = aacpManager.parseStemPressResponse(stemPress)

                Log.d("AirPodsParser", "Stem press received: $stemPressType on $bud, cameraActive: $cameraActive, cameraAction: ${config.cameraAction}")
                if (cameraActive && config.cameraAction != null && stemPressType == config.cameraAction) {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 27"))
                } else {
                    val action = getActionFor(bud, stemPressType)
                    Log.d("AirPodsParser", "$bud $stemPressType action: $action")
                    action?.let { executeStemAction(it) }
                }
            }
            override fun onAudioSourceReceived(audioSource: ByteArray) {
                Log.d("AirPodsParser", "Audio source changed mac: ${aacpManager.audioSource?.mac}, type: ${aacpManager.audioSource?.type?.name}")
                if (aacpManager.audioSource?.type != AACPManager.Companion.AudioSourceType.NONE && aacpManager.audioSource?.mac != localMac) {
                    Log.d("AirPodsParser", "Audio source is another device, better to give up aacp control")
                    aacpManager.sendControlCommand(
                        AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                        byteArrayOf(0x00)
                    )
                    // this also means that the other device has start playing the audio, and if that's true, we can again start listening for audio config changes
//                    Log.d(TAG, "Another device started playing audio, listening for audio config changes again")
//                    MediaController.pausedForOtherDevice = false
// future me: what the heck is this? this just means it will not be taking over again if audio source doesn't change???
                }
            }

            override fun onConnectedDevicesReceived(connectedDevices: List<AACPManager.Companion.ConnectedDevice>) {
                for (device in connectedDevices) {
                    Log.d("AirPodsParser", "Connected device: ${device.mac}, info1: ${device.info1}, info2: ${device.info2})")
                }
                val newDevices = connectedDevices.filter { newDevice ->
                    val notInOld = aacpManager.oldConnectedDevices.none { oldDevice -> oldDevice.mac == newDevice.mac }
                    val notLocal = newDevice.mac != localMac
                    notInOld && notLocal
                }

                for (device in newDevices) {
                    Log.d("AirPodsParser", "New connected device: ${device.mac}, info1: ${device.info1}, info2: ${device.info2})")
                    Log.d(TAG, "Sending new Tipi packet for device ${device.mac}, and sending media info to the device")
                    aacpManager.sendMediaInformationNewDevice(selfMacAddress = localMac, targetMacAddress = device.mac)
                    aacpManager.sendAddTiPiDevice(selfMacAddress = localMac, targetMacAddress = device.mac)
                }
            }
            override fun onUnknownPacketReceived(packet: ByteArray) {
                Log.d("AACPManager", "Unknown packet received: ${packet.joinToString(" ") { "%02X".format(it) }}")
            }
        })
    }

    private fun getActionFor(bud: AACPManager.Companion.StemPressBudType, type: StemPressType): StemAction? {
        return when (type) {
            StemPressType.SINGLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftSinglePressAction else config.rightSinglePressAction
            StemPressType.DOUBLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftDoublePressAction else config.rightDoublePressAction
            StemPressType.TRIPLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftTriplePressAction else config.rightTriplePressAction
            StemPressType.LONG_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftLongPressAction else config.rightLongPressAction
        }
    }

    private fun executeStemAction(action: StemAction) {
        when (action) {
            StemAction.defaultActions[StemPressType.SINGLE_PRESS] -> {
                Log.d("AirPodsParser", "Default single press action: Play/Pause, not taking action.")
            }
            StemAction.PLAY_PAUSE -> MediaController.sendPlayPause()
            StemAction.PREVIOUS_TRACK -> MediaController.sendPreviousTrack()
            StemAction.NEXT_TRACK -> MediaController.sendNextTrack()
            StemAction.DIGITAL_ASSISTANT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } else {
                    Log.w("AirPodsParser", "Digital Assistant action is not supported on this Android version.")
                }
            }
            StemAction.CYCLE_NOISE_CONTROL_MODES -> {
                Log.d("AirPodsParser", "Cycling noise control modes")
                sendBroadcast(Intent("me.kavishdevar.librepods.SET_ANC_MODE"))
            }
        }
    }

    private fun processEarDetectionChange(earDetection: ByteArray) {
        var inEar: Boolean
        val inEarData = listOf(earDetectionNotification.status[0] == 0x00.toByte(), earDetectionNotification.status[1] == 0x00.toByte())
        var justEnabledA2dp = false
        earDetectionNotification.setStatus(earDetection)
        if (config.earDetectionEnabled) {
            val data = earDetection.copyOfRange(earDetection.size - 2, earDetection.size)
            inEar = data[0] == 0x00.toByte() && data[1] == 0x00.toByte()

            val newInEarData = listOf(
                data[0] == 0x00.toByte(),
                data[1] == 0x00.toByte()
            )

            if (inEarData.sorted() == listOf(false, false) && newInEarData.sorted() != listOf(false, false) && islandWindow?.isVisible != true) {
                showIsland(
                    this@AirPodsService,
                    (batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.level?: 0).coerceAtMost(batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.level?: 0))
            }

            if (newInEarData == listOf(false, false) && islandWindow?.isVisible == true) {
                islandWindow?.close()
            }

            if (newInEarData.contains(true) && inEarData == listOf(false, false)) {
                connectAudio(this@AirPodsService, device)
                justEnabledA2dp = true
                registerA2dpConnectionReceiver()
                if (MediaController.getMusicActive()) {
                    MediaController.userPlayedTheMedia = true
                }
            } else if (newInEarData == listOf(false, false)) {
                MediaController.sendPause(force = true)
                if (config.disconnectWhenNotWearing) {
                    disconnectAudio(this@AirPodsService, device)
                }
            }

            if (inEarData.contains(false) && newInEarData == listOf(true, true)) {
                Log.d("AirPodsParser", "User put in both AirPods from just one.")
                MediaController.userPlayedTheMedia = false
            }

            if (newInEarData.contains(false) && inEarData == listOf(true, true)) {
                Log.d("AirPodsParser", "User took one of two out.")
                MediaController.userPlayedTheMedia = false
            }

            Log.d("AirPodsParser", "inEarData: ${inEarData.sorted()}, newInEarData: ${newInEarData.sorted()}")

            if (newInEarData.sorted() != inEarData.sorted()) {
                if (inEar) {
                    if (!justEnabledA2dp) {
                        MediaController.sendPlay()
                        MediaController.iPausedTheMedia = false
                    }
                } else {
                    MediaController.sendPause()
                }
            }
        }
    }

    private fun registerA2dpConnectionReceiver() {
        val a2dpConnectionStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED") {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    val previousState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

                    Log.d("MediaController", "A2DP state changed: $previousState -> $state for device: ${device?.address}")

                    if (state == BluetoothProfile.STATE_CONNECTED &&
                        previousState != BluetoothProfile.STATE_CONNECTED &&
                        device?.address == this@AirPodsService.device?.address) {

                        Log.d("MediaController", "A2DP connected, sending play command")
                        MediaController.sendPlay()
                        MediaController.iPausedTheMedia = false

                        context.unregisterReceiver(this)
                    }
                }
            }
        }

        val a2dpIntentFilter = IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter)
        }
    }

    private fun initializeConfig() {
        config = ServiceConfig(
            deviceName = sharedPreferences.getString("name", "AirPods") ?: "AirPods",
            earDetectionEnabled = sharedPreferences.getBoolean("automatic_ear_detection", true),
            conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", false),
            showPhoneBatteryInWidget = sharedPreferences.getBoolean("show_phone_battery_in_widget", true),
            relativeConversationalAwarenessVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", true),
            headGestures = sharedPreferences.getBoolean("head_gestures", true),
            disconnectWhenNotWearing = sharedPreferences.getBoolean("disconnect_when_not_wearing", false),
            conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", 43),
            qsClickBehavior = sharedPreferences.getString("qs_click_behavior", "cycle") ?: "cycle",

            // AirPods state-based takeover
            takeoverWhenDisconnected = sharedPreferences.getBoolean("takeover_when_disconnected", true),
            takeoverWhenIdle = sharedPreferences.getBoolean("takeover_when_idle", true),
            takeoverWhenMusic = sharedPreferences.getBoolean("takeover_when_music", false),
            takeoverWhenCall = sharedPreferences.getBoolean("takeover_when_call", true),

            // Phone state-based takeover
            takeoverWhenRingingCall = sharedPreferences.getBoolean("takeover_when_ringing_call", true),
            takeoverWhenMediaStart = sharedPreferences.getBoolean("takeover_when_media_start", true),

            // Stem actions
            leftSinglePressAction = StemAction.fromString(sharedPreferences.getString("left_single_press_action", "PLAY_PAUSE") ?: "PLAY_PAUSE")!!,
            rightSinglePressAction = StemAction.fromString(sharedPreferences.getString("right_single_press_action", "PLAY_PAUSE") ?: "PLAY_PAUSE")!!,

            leftDoublePressAction = StemAction.fromString(sharedPreferences.getString("left_double_press_action", "PREVIOUS_TRACK") ?: "NEXT_TRACK")!!,
            rightDoublePressAction = StemAction.fromString(sharedPreferences.getString("right_double_press_action", "NEXT_TRACK") ?: "NEXT_TRACK")!!,

            leftTriplePressAction = StemAction.fromString(sharedPreferences.getString("left_triple_press_action", "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK")!!,
            rightTriplePressAction = StemAction.fromString(sharedPreferences.getString("right_triple_press_action", "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK")!!,

            leftLongPressAction = StemAction.fromString(sharedPreferences.getString("left_long_press_action", "CYCLE_NOISE_CONTROL_MODES") ?: "CYCLE_NOISE_CONTROL_MODES")!!,
            rightLongPressAction = StemAction.fromString(sharedPreferences.getString("right_long_press_action", "DIGITAL_ASSISTANT") ?: "DIGITAL_ASSISTANT")!!,

            cameraAction = sharedPreferences.getString("camera_action", null)?.let { StemPressType.valueOf(it) },

            // AirPods device information
            airpodsName = sharedPreferences.getString("airpods_name", "") ?: "",
            airpodsModelNumber = sharedPreferences.getString("airpods_model_number", "") ?: "",
            airpodsManufacturer = sharedPreferences.getString("airpods_manufacturer", "") ?: "",
            airpodsSerialNumber = sharedPreferences.getString("airpods_serial_number", "") ?: "",
            airpodsLeftSerialNumber = sharedPreferences.getString("airpods_left_serial_number", "") ?: "",
            airpodsRightSerialNumber = sharedPreferences.getString("airpods_right_serial_number", "") ?: "",
            airpodsVersion1 = sharedPreferences.getString("airpods_version1", "") ?: "",
            airpodsVersion2 = sharedPreferences.getString("airpods_version2", "") ?: "",
            airpodsVersion3 = sharedPreferences.getString("airpods_version3", "") ?: "",
            airpodsHardwareRevision = sharedPreferences.getString("airpods_hardware_revision", "") ?: "",
            airpodsUpdaterIdentifier = sharedPreferences.getString("airpods_updater_identifier", "") ?: "",

            selfMacAddress = sharedPreferences.getString("self_mac_address", "") ?: ""
        )
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (preferences == null || key == null) return

        when(key) {
            "name" -> config.deviceName = preferences.getString(key, "AirPods") ?: "AirPods"
            "mac_address" -> macAddress = preferences.getString(key, "") ?: ""
            "automatic_ear_detection" -> config.earDetectionEnabled = preferences.getBoolean(key, true)
            "conversational_awareness_pause_music" -> config.conversationalAwarenessPauseMusic = preferences.getBoolean(key, false)
            "show_phone_battery_in_widget" -> {
                config.showPhoneBatteryInWidget = preferences.getBoolean(key, true)
                widgetMobileBatteryEnabled = config.showPhoneBatteryInWidget
                updateBattery()
            }
            "relative_conversational_awareness_volume" -> config.relativeConversationalAwarenessVolume = preferences.getBoolean(key, true)
            "head_gestures" -> config.headGestures = preferences.getBoolean(key, true)
            "disconnect_when_not_wearing" -> config.disconnectWhenNotWearing = preferences.getBoolean(key, false)
            "conversational_awareness_volume" -> config.conversationalAwarenessVolume = preferences.getInt(key, 43)
            "qs_click_behavior" -> config.qsClickBehavior = preferences.getString(key, "cycle") ?: "cycle"

            // AirPods state-based takeover
            "takeover_when_disconnected" -> config.takeoverWhenDisconnected = preferences.getBoolean(key, true)
            "takeover_when_idle" -> config.takeoverWhenIdle = preferences.getBoolean(key, true)
            "takeover_when_music" -> config.takeoverWhenMusic = preferences.getBoolean(key, false)
            "takeover_when_call" -> config.takeoverWhenCall = preferences.getBoolean(key, true)

            // Phone state-based takeover
            "takeover_when_ringing_call" -> config.takeoverWhenRingingCall = preferences.getBoolean(key, true)
            "takeover_when_media_start" -> config.takeoverWhenMediaStart = preferences.getBoolean(key, true)

            "left_single_press_action" -> {
                config.leftSinglePressAction = StemAction.fromString(
                    preferences.getString(key, "PLAY_PAUSE") ?: "PLAY_PAUSE"
                )!!
                setupStemActions()
            }
            "right_single_press_action" -> {
                config.rightSinglePressAction = StemAction.fromString(
                    preferences.getString(key, "PLAY_PAUSE") ?: "PLAY_PAUSE"
                )!!
                setupStemActions()
            }
            "left_double_press_action" -> {
                config.leftDoublePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }
            "right_double_press_action" -> {
                config.rightDoublePressAction = StemAction.fromString(
                    preferences.getString(key, "NEXT_TRACK") ?: "NEXT_TRACK"
                )!!
                setupStemActions()
            }
            "left_triple_press_action" -> {
                config.leftTriplePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }
            "right_triple_press_action" -> {
                config.rightTriplePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }
            "left_long_press_action" -> {
                config.leftLongPressAction = StemAction.fromString(
                    preferences.getString(key, "CYCLE_NOISE_CONTROL_MODES") ?: "CYCLE_NOISE_CONTROL_MODES"
                )!!
                setupStemActions()
            }
            "right_long_press_action" -> {
                config.rightLongPressAction = StemAction.fromString(
                    preferences.getString(key, "DIGITAL_ASSISTANT") ?: "DIGITAL_ASSISTANT"
                )!!
                setupStemActions()
            }
            "camera_action" -> config.cameraAction = preferences.getString(key, null)?.let { StemPressType.valueOf(it) }

            // AirPods device information
            "airpods_name" -> config.airpodsName = preferences.getString(key, "") ?: ""
            "airpods_model_number" -> config.airpodsModelNumber = preferences.getString(key, "") ?: ""
            "airpods_manufacturer" -> config.airpodsManufacturer = preferences.getString(key, "") ?: ""
            "airpods_serial_number" -> config.airpodsSerialNumber = preferences.getString(key, "") ?: ""
            "airpods_left_serial_number" -> config.airpodsLeftSerialNumber = preferences.getString(key, "") ?: ""
            "airpods_right_serial_number" -> config.airpodsRightSerialNumber = preferences.getString(key, "") ?: ""
            "airpods_version1" -> config.airpodsVersion1 = preferences.getString(key, "") ?: ""
            "airpods_version2" -> config.airpodsVersion2 = preferences.getString(key, "") ?: ""
            "airpods_version3" -> config.airpodsVersion3 = preferences.getString(key, "") ?: ""
            "airpods_hardware_revision" -> config.airpodsHardwareRevision = preferences.getString(key, "") ?: ""
            "airpods_updater_identifier" -> config.airpodsUpdaterIdentifier = preferences.getString(key, "") ?: ""

            "self_mac_address" -> config.selfMacAddress = preferences.getString(key, "") ?: ""
        }
    }

    private fun logPacket(packet: ByteArray, @Suppress("SameParameterValue") source: String) {
        val packetHex = packet.joinToString(" ") { "%02X".format(it) }
        val logEntry = "$source: $packetHex"

        synchronized(inMemoryLogs) {
            inMemoryLogs.add(logEntry)
            if (inMemoryLogs.size > maxLogEntries) {
                inMemoryLogs.iterator().next().let {
                    inMemoryLogs.remove(it)
                }
            }

            _packetLogsFlow.value = inMemoryLogs.toSet()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val logs = sharedPreferencesLogs.getStringSet(packetLogKey, mutableSetOf())?.toMutableSet()
                ?: mutableSetOf()
            logs.add(logEntry)

            if (logs.size > maxLogEntries) {
                val toKeep = logs.toList().takeLast(maxLogEntries).toSet()
                sharedPreferencesLogs.edit { putStringSet(packetLogKey, toKeep) }
            } else {
                sharedPreferencesLogs.edit { putStringSet(packetLogKey, logs) }
            }
        }
    }

    private fun clearPacketLogs() {
        synchronized(inMemoryLogs) {
            inMemoryLogs.clear()
            _packetLogsFlow.value = emptySet()
        }
        sharedPreferencesLogs.edit { remove(packetLogKey) }
    }

    fun clearLogs() {
        clearPacketLogs()
        _packetLogsFlow.value = emptySet()
    }

    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    private var gestureDetector: GestureDetector? = null
    private var isInCall = false
    private var callNumber: String? = null

    private fun initGestureDetector() {
        if (gestureDetector == null) {
            gestureDetector = GestureDetector(this)
        }
    }


    var popupShown = false
    fun showPopup(service: Service, name: String) {
        if (!Settings.canDrawOverlays(service)) {
            Log.d(TAG, "No permission for SYSTEM_ALERT_WINDOW")
            return
        }
        if (popupShown) {
            return
        }
        val popupWindow = PopupWindow(service.applicationContext)
        popupWindow.open(name, batteryNotification)
        popupShown = true
    }

    var islandOpen = false
    var islandWindow: IslandWindow? = null
    @SuppressLint("MissingPermission")
    fun showIsland(service: Service, batteryPercentage: Int, type: IslandType = IslandType.CONNECTED, reversed: Boolean = false, otherDeviceName: String? = null) {
        Log.d(TAG, "Showing island window")
        if (!Settings.canDrawOverlays(service)) {
            Log.d(TAG, "No permission for SYSTEM_ALERT_WINDOW")
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            islandWindow = IslandWindow(service.applicationContext)
            islandWindow!!.show(sharedPreferences.getString("name", "AirPods Pro").toString(), batteryPercentage, this@AirPodsService, type, reversed, otherDeviceName)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    var isConnectedLocally = false
    var device: BluetoothDevice? = null

    private lateinit var earReceiver: BroadcastReceiver
    var widgetMobileBatteryEnabled = false

    object BatteryChangedIntentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                ServiceManager.getService()?.updateBattery()
            } else if (intent.action == AirPodsNotifications.DISCONNECT_RECEIVERS) {
                try {
                    context?.unregisterReceiver(this)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun startForegroundNotification() {
        val disconnectedNotificationChannel = NotificationChannel(
            "background_service_status",
            "Background Service Status",
            NotificationManager.IMPORTANCE_LOW
        )

        val connectedNotificationChannel = NotificationChannel(
            "airpods_connection_status",
            "AirPods Connection Status",
            NotificationManager.IMPORTANCE_LOW,
        )

        val socketFailureChannel = NotificationChannel(
            "socket_connection_failure",
            "AirPods Socket Connection Issues",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications about problems connecting to AirPods protocol"
            enableLights(true)
            lightColor = Color.RED
            enableVibration(true)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(disconnectedNotificationChannel)
        notificationManager.createNotificationChannel(connectedNotificationChannel)
        notificationManager.createNotificationChannel(socketFailureChannel)

        val notificationSettingsIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, "background_service_status")
        }
        val pendingIntentNotifDisable = PendingIntent.getActivity(
            this,
            0,
            notificationSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "background_service_status")
            .setSmallIcon(R.drawable.airpods)
            .setContentTitle("Background Service Running")
            .setContentText("Useless notification, disable it by clicking on it.")
            .setContentIntent(pendingIntentNotifDisable)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun showSocketConnectionFailureNotification(errorMessage: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "socket_connection_failure")
            .setSmallIcon(R.drawable.airpods)
            .setContentTitle("AirPods Connection Issue")
            .setContentText("Unable to connect to AirPods over L2CAP")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Your AirPods are connected via Bluetooth, but LibrePods couldn't connect to AirPods using L2CAP. " +
                         "Error: $errorMessage"))
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(3, notification)
    }

    fun sendANCBroadcast() {
        sendBroadcast(Intent(AirPodsNotifications.ANC_DATA).apply {
            putExtra("data", ancNotification.status)
        })
    }

    fun sendBatteryBroadcast() {
        sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
            putParcelableArrayListExtra("data", ArrayList(batteryNotification.getBattery()))
        })
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendBatteryNotification() {
        updateNotificationContent(
            true,
            getSharedPreferences("settings", MODE_PRIVATE).getString("name", device?.name),
            batteryNotification.getBattery()
        )
    }

    fun setBatteryMetadata() {
        device?.let { it ->
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_CASE_BATTERY,
                batteryNotification.getBattery().find { it.component == BatteryComponent.CASE }?.level.toString().toByteArray()
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_CASE_CHARGING,
                (if (batteryNotification.getBattery().find { it.component == BatteryComponent.CASE}?.status == BatteryStatus.CHARGING) "1".toByteArray() else "0".toByteArray())
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_LEFT_BATTERY,
                batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT }?.level.toString().toByteArray()
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_LEFT_CHARGING,
                (if (batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.status == BatteryStatus.CHARGING) "1".toByteArray() else "0".toByteArray())
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_RIGHT_BATTERY,
                batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT }?.level.toString().toByteArray()
            )
            SystemApisUtils.setMetadata(
                it,
                it.METADATA_UNTETHERED_RIGHT_CHARGING,
                (if (batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.status == BatteryStatus.CHARGING) "1".toByteArray() else "0".toByteArray())
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateBatteryWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, BatteryWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val remoteViews = RemoteViews(packageName, R.layout.battery_widget).also { it ->
            val openActivityIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            it.setOnClickPendingIntent(R.id.battery_widget, openActivityIntent)

            val leftBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT }
            val rightBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT }
            val caseBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.CASE }

            it.setTextViewText(
                R.id.left_battery_widget,
                leftBattery?.let {
                    "${it.level}%"
                } ?: ""
            )
            it.setProgressBar(
                R.id.left_battery_progress,
                100,
                leftBattery?.level ?: 0,
                false
            )
            it.setViewVisibility(
                R.id.left_charging_icon,
                if (leftBattery?.status == BatteryStatus.CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(
                R.id.right_battery_widget,
                rightBattery?.let {
                    "${it.level}%"
                } ?: ""
            )
            it.setProgressBar(
                R.id.right_battery_progress,
                100,
                rightBattery?.level ?: 0,
                false
            )
            it.setViewVisibility(
                R.id.right_charging_icon,
                if (rightBattery?.status == BatteryStatus.CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(
                R.id.case_battery_widget,
                caseBattery?.let {
                    "${it.level}%"
                } ?: ""
            )
            it.setProgressBar(
                R.id.case_battery_progress,
                100,
                caseBattery?.level ?: 0,
                false
            )
            it.setViewVisibility(
                R.id.case_charging_icon,
                if (caseBattery?.status == BatteryStatus.CHARGING) View.VISIBLE else View.GONE
            )

            it.setViewVisibility(
                R.id.phone_battery_widget_container,
                if (widgetMobileBatteryEnabled) View.VISIBLE else View.GONE
            )
            if (widgetMobileBatteryEnabled) {
                val batteryManager = getSystemService(BatteryManager::class.java)
                val batteryLevel =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
                it.setTextViewText(
                    R.id.phone_battery_widget,
                    "$batteryLevel%"
                )
                it.setViewVisibility(
                    R.id.phone_charging_icon,
                    if (charging) View.VISIBLE else View.GONE
                )
                it.setProgressBar(
                    R.id.phone_battery_progress,
                    100,
                    batteryLevel,
                    false
                )
            }
        }
        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalMaterial3Api::class)
    fun updateBattery() {
        setBatteryMetadata()
        updateBatteryWidget()
        sendBatteryBroadcast()
        sendBatteryNotification()
    }

    fun updateNoiseControlWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, NoiseControlWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
        val remoteViews = RemoteViews(packageName, R.layout.noise_control_widget).also { it ->
            val ancStatus = ancNotification.status
            val allowOffModeValue = aacpManager.controlCommandStatusList.find { it.identifier == AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION }
            val allowOffMode = allowOffModeValue?.value?.takeIf { it.isNotEmpty() }?.get(0) == 0x01.toByte()
            it.setInt(
                R.id.widget_off_button,
                "setBackgroundResource",
                if (ancStatus == 1) R.drawable.widget_button_checked_shape_start else R.drawable.widget_button_shape_start
            )
            it.setInt(
                R.id.widget_transparency_button,
                "setBackgroundResource",
                if (ancStatus == 3) (if (allowOffMode) R.drawable.widget_button_checked_shape_middle else R.drawable.widget_button_checked_shape_start) else (if (allowOffMode) R.drawable.widget_button_shape_middle else R.drawable.widget_button_shape_start)
            )
            it.setInt(
                R.id.widget_adaptive_button,
                "setBackgroundResource",
                if (ancStatus == 4) R.drawable.widget_button_checked_shape_middle else R.drawable.widget_button_shape_middle
            )
            it.setInt(
                R.id.widget_anc_button,
                "setBackgroundResource",
                if (ancStatus == 2) R.drawable.widget_button_checked_shape_end else R.drawable.widget_button_shape_end
            )
            it.setViewVisibility(
                R.id.widget_off_button,
                if (allowOffMode) View.VISIBLE else View.GONE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                it.setViewLayoutMargin(
                    R.id.widget_transparency_button,
                    RemoteViews.MARGIN_START,
                    if (allowOffMode) 2f else 12f,
                    TypedValue.COMPLEX_UNIT_DIP
                )
            } else {
                it.setViewPadding(
                    R.id.widget_transparency_button,
                    if (allowOffMode) 2.dpToPx() else 12.dpToPx(),
                    12.dpToPx(),
                    2.dpToPx(),
                    12.dpToPx()
                )
            }
        }

        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateNotificationContent(
        connected: Boolean,
        airpodsName: String? = null,
        batteryList: List<Battery>? = null
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        var updatedNotification: Notification?

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (!::socket.isInitialized) {
            return
        }
        if (connected && (config.bleOnlyMode || socket.isConnected)) {
            val updatedNotificationBuilder = NotificationCompat.Builder(this, "airpods_connection_status")
                .setSmallIcon(R.drawable.airpods)
                .setContentTitle(airpodsName ?: config.deviceName)
                .setContentText(
                    """${
                        batteryList?.find { it.component == BatteryComponent.LEFT }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "L: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    } ${
                        batteryList?.find { it.component == BatteryComponent.RIGHT }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "R: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    } ${
                        batteryList?.find { it.component == BatteryComponent.CASE }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "Case: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    }""")
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)

            if (disconnectedBecauseReversed) {
                updatedNotificationBuilder.addAction(
                    R.drawable.ic_bluetooth,
                    "Reconnect",
                    PendingIntent.getService(
                        this,
                        0,
                        Intent(this, AirPodsService::class.java).apply {
                            action = "me.kavishdevar.librepods.RECONNECT_AFTER_REVERSE"
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            val updatedNotification = updatedNotificationBuilder.build()

            notificationManager.notify(2, updatedNotification)
            notificationManager.cancel(1)
        } else if (!connected) {
            updatedNotification = NotificationCompat.Builder(this, "background_service_status")
                .setSmallIcon(R.drawable.airpods)
                .setContentTitle("AirPods not connected")
                .setContentText("Tap to open app")
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            notificationManager.notify(1, updatedNotification)
            notificationManager.cancel(2)
        } else if (!config.bleOnlyMode && !socket.isConnected && isConnectedLocally) {
            showSocketConnectionFailureNotification("Socket created, but not connected. Is the Bluetooth process hooked?")
        }
    }

    fun handleIncomingCall() {
        if (isInCall) return
        if (config.headGestures) {
            initGestureDetector()
            startHeadTracking()
            gestureDetector?.startDetection { accepted ->
                if (accepted) {
                    answerCall()
                    handleIncomingCallOnceConnected = false
                } else {
                    rejectCall()
                    handleIncomingCallOnceConnected = false
                }
            }

        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun testHeadGestures(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            gestureDetector?.startDetection(doNotStop = true) { accepted ->
                if (continuation.isActive) {
                    continuation.resume(accepted) {
                        gestureDetector?.stopDetection()
                    }
                }
            }
        }
    }
    private fun answerCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.acceptRingingCall()
                }
            } else {
                val telephonyService = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val answerCallMethod = telephonyInterface.javaClass.getDeclaredMethod("answerRingingCall")
                answerCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call answered via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to answer call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }
    private fun rejectCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.endCall()
                }
            } else {
                val telephonyService = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val endCallMethod = telephonyInterface.javaClass.getDeclaredMethod("endCall")
                endCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call rejected via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to reject call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }

    fun sendToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun processHeadTrackingData(data: ByteArray) {
        val horizontal = ByteBuffer.wrap(data, 51, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val vertical = ByteBuffer.wrap(data, 53, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        gestureDetector?.processHeadOrientation(horizontal, vertical)
    }

    private lateinit var connectionReceiver: BroadcastReceiver

    private fun resToUri(resId: Int): Uri? {
        return try {
            Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority("me.kavishdevar.librepods")
                .appendPath(applicationContext.resources.getResourceTypeName(resId))
                .appendPath(applicationContext.resources.getResourceEntryName(resId))
                .build()
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    @Suppress("PrivatePropertyName")
    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV = "+IPHONEACCEV"
    @Suppress("PrivatePropertyName")
    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL = 1
    @Suppress("PrivatePropertyName")
    private val APPLE = 0x004C
    @Suppress("PrivatePropertyName")
    private val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    @Suppress("PrivatePropertyName")
    private val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    @Suppress("PrivatePropertyName")
    private val PACKAGE_ASI = "com.google.android.settings.intelligence"
    @Suppress("PrivatePropertyName")
    private val ACTION_ASI_UPDATE_BLUETOOTH_DATA = "batterywidget.impl.action.update_bluetooth_data"

    @Suppress("MissingPermission", "unused")
    fun broadcastBatteryInformation() {
        if (device == null) return

        val batteryList = batteryNotification.getBattery()
        val leftBattery = batteryList.find { it.component == BatteryComponent.LEFT }
        val rightBattery = batteryList.find { it.component == BatteryComponent.RIGHT }

        // Calculate unified battery level (minimum of left and right)
        val batteryUnified = minOf(
            leftBattery?.level ?: 100,
            rightBattery?.level ?: 100
        )

        // Check charging status
        val isLeftCharging = leftBattery?.status == BatteryStatus.CHARGING
        val isRightCharging = rightBattery?.status == BatteryStatus.CHARGING
        isLeftCharging && isRightCharging

        // Create arguments for vendor-specific event
        val arguments = arrayOf<Any>(
            1, // Number of key/value pairs
            VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL, // IndicatorType: Battery Level
            batteryUnified // Battery Level
        )

        // Broadcast vendor-specific event
        val intent = Intent(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
            putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD, VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV)
            putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE, BluetoothHeadset.AT_CMD_TYPE_SET)
            putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS, arguments)
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(BluetoothDevice.EXTRA_NAME, device?.name)
            addCategory("${BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY}.$APPLE")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendBroadcastAsUser(
                    intent,
                    UserHandle.getUserHandleForUid(-1),
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(-1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send vendor-specific event: ${e.message}")
        }

        // Broadcast battery level changes
        val batteryIntent = Intent(ACTION_BATTERY_LEVEL_CHANGED).apply {
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(EXTRA_BATTERY_LEVEL, batteryUnified)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendBroadcast(batteryIntent, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                sendBroadcastAsUser(batteryIntent, UserHandle.getUserHandleForUid(-1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send battery level broadcast: ${e.message}")
        }

        // Update Android Settings Intelligence's battery widget
        val statusIntent = Intent(ACTION_ASI_UPDATE_BLUETOOTH_DATA).apply {
            setPackage(PACKAGE_ASI)
            putExtra(ACTION_BATTERY_LEVEL_CHANGED, intent)
        }

        try {
            sendBroadcastAsUser(statusIntent, UserHandle.getUserHandleForUid(-1))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ASI battery level broadcast: ${e.message}")
        }

        Log.d(TAG, "Broadcast battery level $batteryUnified% to system")
    }

    private fun setMetadatas(d: BluetoothDevice) {
        d.let{ device ->
            val instance = airpodsInstance
            if (instance != null) {
                val metadataSet = SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_MAIN_ICON,
                    resToUri(instance.model.budCaseRes).toString().toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_MODEL_NAME,
                    instance.model.name.toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_DEVICE_TYPE,
                    device.DEVICE_TYPE_UNTETHERED_HEADSET.toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_CASE_ICON,
                    resToUri(instance.model.caseRes).toString().toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_RIGHT_ICON,
                    resToUri(instance.model.rightBudsRes).toString().toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_LEFT_ICON,
                    resToUri(instance.model.leftBudsRes).toString().toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_MANUFACTURER_NAME,
                    instance.model.manufacturer.toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_COMPANION_APP,
                    "me.kavishdevar.librepods".toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                ) &&
                SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                )
                Log.d(TAG, "Metadata set: $metadataSet")
            } else {
                Log.w(TAG, "AirPods instance is not of type AirPodsInstance, skipping metadata setting")
            }
        }
    }

    @Suppress("ClassName")
    private object bluetoothReceiver : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            val bluetoothDevice =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(
                        "android.bluetooth.device.extra.DEVICE",
                        BluetoothDevice::class.java
                    )
                } else {
                    intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE") as BluetoothDevice?
                }
            val action = intent.action
            val context = context?.applicationContext
            val name = context?.getSharedPreferences("settings", MODE_PRIVATE)
                ?.getString("name", bluetoothDevice?.name)
            if (bluetoothDevice != null && action != null && !action.isEmpty()) {
                Log.d(TAG, "Received bluetooth connection broadcast: action=$action")
                if (BluetoothDevice.ACTION_ACL_CONNECTED == action) {
                    val uuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
                    bluetoothDevice.fetchUuidsWithSdp()
                    if (bluetoothDevice.uuids != null) {
                        if (bluetoothDevice.uuids.contains(uuid)) {
                            val intent =
                                Intent(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
                            intent.putExtra("name", name)
                            intent.putExtra("device", bluetoothDevice)
                            context?.sendBroadcast(intent)
                        }
                    }
                }
            }
        }
    }

    val ancModeFilter = IntentFilter("me.kavishdevar.librepods.SET_ANC_MODE")
    var ancModeReceiver: BroadcastReceiver? = null

    @SuppressLint("InlinedApi", "MissingPermission", "UnspecifiedRegisterReceiverFlag")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with intent action: ${intent?.action}")

        if (intent?.action == "me.kavishdevar.librepods.RECONNECT_AFTER_REVERSE") {
            Log.d(TAG, "reconnect after reversed received, taking over")
            disconnectedBecauseReversed = false
            otherDeviceTookOver = false
            takeOver("music", manualTakeOverAfterReversed = true)
        }

        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission", "HardwareIds")
    fun takeOver(takingOverFor: String, manualTakeOverAfterReversed: Boolean = false, startHeadTrackingAgain: Boolean = false) {
        if (takingOverFor == "reverse") {
            aacpManager.sendControlCommand(
                AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                1
            )
            aacpManager.sendMediaInformataion(
                localMac
            )
            aacpManager.sendHijackReversed(
                localMac
            )
            connectAudio(
                this@AirPodsService,
                device
            )
            otherDeviceTookOver = false
        }
        Log.d(TAG, "owns connection: ${aacpManager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION)?.value?.get(0)?.toInt()}")
        if (isConnectedLocally) {
            if (aacpManager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION)?.value[0]?.toInt() != 1 || (aacpManager.audioSource?.mac != localMac && aacpManager.audioSource?.type != AACPManager.Companion.AudioSourceType.NONE)) {
                if (disconnectedBecauseReversed) {
                    if (manualTakeOverAfterReversed) {
                        Log.d(TAG, "forcefully taking over despite reverse as user requested")
                        disconnectedBecauseReversed = false
                    } else {
                        Log.d(TAG, "connected locally, but can not hijack as other device had reversed")
                        return
                    }
                }

                Log.d(TAG, "already connected locally, hijacking connection by asking AirPods")
                aacpManager.sendControlCommand(
                    AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                    1
                )
                aacpManager.sendMediaInformataion(
                    localMac
                )
                aacpManager.sendSmartRoutingShowUI(
                    localMac
                )
                aacpManager.sendHijackRequest(
                    localMac
                )
                otherDeviceTookOver = false
                connectAudio(this, device)
                showIsland(this, batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.level!!.coerceAtMost(batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.level!!),
                    IslandType.CONNECTED)

                CoroutineScope(Dispatchers.IO).launch {
                    delay(500) // a2dp takes time, and so does taking control + AirPods pause it for no reason after connecting
                    if (takingOverFor == "music") {
                        Log.d(TAG, "Resuming music after taking control")
                        MediaController.sendPlay(replayWhenPaused = true)
                    } else if (startHeadTrackingAgain) {
                        Log.d(TAG, "Starting head tracking again after taking control")
                        Handler(Looper.getMainLooper()).postDelayed({
                            startHeadTracking()
                        }, 500)
                    }
                    delay(1000) // should ideally have a callback when it's taken over because for some reason android doesn't dispatch when it's paused
                    if (takingOverFor == "music") {
                        Log.d(TAG, "resuming again just in case")
                        MediaController.sendPlay(force = true)
                    }
                }
            } else {
                Log.d(TAG, "Already connected locally and already own connection, skipping takeover")
            }
            return
        }

//        if (CrossDevice.isAvailable) {
//            Log.d(TAG, "CrossDevice is available, continuing")
//        }
//        else if (bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true) {
//            Log.d(TAG, "At least one AirPod is in ear, continuing")
//        }
//        else {
//            Log.d(TAG, "CrossDevice not available and AirPods not in ear, skipping")
//            return
//        }

        if (bleManager.getMostRecentStatus()?.isLeftInEar == false && bleManager.getMostRecentStatus()?.isRightInEar == false) {
            Log.d(TAG, "Both AirPods are out of ear, not taking over audio")
            return
        }

        val shouldTakeOverPState = when (takingOverFor) {
            "music" -> config.takeoverWhenMediaStart
            "call" -> config.takeoverWhenRingingCall
            else -> false
        }

        if (!shouldTakeOverPState) {
            Log.d(TAG, "Not taking over audio, phone state takeover disabled")
            return
        }

        val shouldTakeOver = when (bleManager.getMostRecentStatus()?.connectionState) {
            "Disconnected" -> config.takeoverWhenDisconnected
            "Idle" -> config.takeoverWhenIdle
            "Music" -> config.takeoverWhenMusic
            "Call" -> config.takeoverWhenCall
            "Ringing" -> config.takeoverWhenCall
            "Hanging Up" -> config.takeoverWhenCall
            else -> false
        }

        if (!shouldTakeOver) {
            Log.d(TAG, "Not taking over audio, airpods state takeover disabled")
            return
        }

        if (takingOverFor == "music") {
            Log.d(TAG, "Pausing music so that it doesn't play through speakers")
            MediaController.pausedWhileTakingOver = true
            MediaController.sendPause(true)
        } else {
            handleIncomingCallOnceConnected = true
        }

        Log.d(TAG, "Taking over audio")
//        CrossDevice.sendRemotePacket(CrossDevicePackets.REQUEST_DISCONNECT.packet)
        Log.d(TAG, macAddress)

//        sharedPreferences.edit { putBoolean("CrossDeviceIsAvailable", false) }
        device = getSystemService(BluetoothManager::class.java).adapter.bondedDevices.find {
            it.address == macAddress
        }

        if (device != null) {
            if (config.bleOnlyMode) {
                // In BLE-only mode, just show connecting status without actual L2CAP connection
                Log.d(TAG, "BLE-only mode: showing connecting status without L2CAP connection")
                updateNotificationContent(
                    true,
                    config.deviceName,
                    batteryNotification.getBattery()
                )
                // Set a temporary connecting state
                isConnectedLocally = false // Keep as false since we're not actually connecting to L2CAP
            } else {
                connectToSocket(device!!)
                connectAudio(this, device)
                isConnectedLocally = true
            }
        }
        showIsland(this, batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.level!!.coerceAtMost(batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.level!!),
            IslandType.TAKING_OVER)

//        CrossDevice.isAvailable = false
    }

    private fun createBluetoothSocket(device: BluetoothDevice, uuid: ParcelUuid): BluetoothSocket {
        val type = 3 // L2CAP
        val constructorSpecs = listOf(
            arrayOf(device, type, true, true, 0x1001, uuid),
            arrayOf(device, type, 1, true, true, 0x1001, uuid),
            arrayOf(type, 1, true, true, device, 0x1001, uuid),
            arrayOf(type, true, true, device, 0x1001, uuid)
        )

        val constructors = BluetoothSocket::class.java.declaredConstructors
        Log.d(TAG, "BluetoothSocket has ${constructors.size} constructors:")

        constructors.forEachIndexed { index, constructor ->
            val params = constructor.parameterTypes.joinToString(", ") { it.simpleName }
            Log.d(TAG, "Constructor $index: ($params)")
        }

        var lastException: Exception? = null
        var attemptedConstructors = 0

        for ((index, params) in constructorSpecs.withIndex()) {
            try {
                Log.d(TAG, "Trying constructor signature #${index + 1}")
                attemptedConstructors++
                return HiddenApiBypass.newInstance(BluetoothSocket::class.java, *params) as BluetoothSocket
            } catch (e: Exception) {
                Log.e(TAG, "Constructor signature #${index + 1} failed: ${e.message}")
                lastException = e
            }
        }

        val errorMessage = "Failed to create BluetoothSocket after trying $attemptedConstructors constructor signatures"
        Log.e(TAG, errorMessage)
        showSocketConnectionFailureNotification(errorMessage)
        throw lastException ?: IllegalStateException(errorMessage)
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    fun connectToSocket(device: BluetoothDevice, manual: Boolean = false) {
        Log.d(TAG, "<LogCollector:Start> Connecting to socket")
        HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/BluetoothSocket;")
        val uuid: ParcelUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
        if (!isConnectedLocally) {
            socket = try {
                createBluetoothSocket(device, uuid)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create BluetoothSocket: ${e.message}")
                showSocketConnectionFailureNotification("Failed to create Bluetooth socket: ${e.localizedMessage}")
                return
            }

            try {
                runBlocking {
                    withTimeout(5000L) {
                        try {
                            socket.connect()
                            isConnectedLocally = true
                            this@AirPodsService.device = device

                            BluetoothConnectionManager.setCurrentConnection(socket, device)

                            attManager = ATTManager(device)
                            attManager!!.connect()

                            // Create AirPodsInstance from stored config if available
                            if (airpodsInstance == null && config.airpodsModelNumber.isNotEmpty()) {
                                val model = AirPodsModels.getModelByModelNumber(config.airpodsModelNumber)
                                if (model != null) {
                                    airpodsInstance = AirPodsInstance(
                                        name = config.airpodsName,
                                        model = model,
                                        actualModelNumber = config.airpodsModelNumber,
                                        serialNumber = config.airpodsSerialNumber,
                                        leftSerialNumber = config.airpodsLeftSerialNumber,
                                        rightSerialNumber = config.airpodsRightSerialNumber,
                                        version1 = config.airpodsVersion1,
                                        version2 = config.airpodsVersion2,
                                        version3 = config.airpodsVersion3,
                                        aacpManager = aacpManager,
                                        attManager = attManager
                                    )
                                }
                            }

                            updateNotificationContent(
                                true,
                                config.deviceName,
                                batteryNotification.getBattery()
                            )
                            Log.d(TAG, "<LogCollector:Complete:Success> Socket connected")
                        } catch (e: Exception) {
                            Log.d(TAG, "<LogCollector:Complete:Failed> Socket not connected, ${e.message}")
                            if (manual) {
                                sendToast(
                                    "Couldn't connect to socket: ${e.localizedMessage}"
                                )
                            } else {
                                showSocketConnectionFailureNotification("Couldn't connect to socket: ${e.localizedMessage}")
                            }
                            return@withTimeout
//                            throw e // lol how did i not catch this before... gonna comment this line instead of removing to preserve history
                        }
                    }
                }
                if (!socket.isConnected) {
                    Log.d(TAG, "<LogCollector:Complete:Failed> Socket not connected")
                    if (manual) {
                        sendToast(
                            "Couldn't connect to socket: timeout."
                        )
                    } else {
                        showSocketConnectionFailureNotification("Couldn't connect to socket: Timeout")
                    }
                    return
                }
                this@AirPodsService.device = device
                socket.let {
                    aacpManager.sendPacket(aacpManager.createHandshakePacket())
                    aacpManager.sendSetFeatureFlagsPacket()
                    aacpManager.sendNotificationRequest()
                    Log.d(TAG, "Requesting proximity keys")
                    aacpManager.sendRequestProximityKeys((AACPManager.Companion.ProximityKeyType.IRK.value + AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte())
                    CoroutineScope(Dispatchers.IO).launch {
                        aacpManager.sendPacket(aacpManager.createHandshakePacket())
                        delay(200)
                        aacpManager.sendSetFeatureFlagsPacket()
                        delay(200)
                        aacpManager.sendNotificationRequest()
                        delay(200)
                        aacpManager.sendSomePacketIDontKnowWhatItIs()
                        delay(200)
                        aacpManager.sendRequestProximityKeys((AACPManager.Companion.ProximityKeyType.IRK.value+AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte())
                        if (!handleIncomingCallOnceConnected) startHeadTracking() else handleIncomingCall()
                        Handler(Looper.getMainLooper()).postDelayed({
                            aacpManager.sendPacket(aacpManager.createHandshakePacket())
                            aacpManager.sendSetFeatureFlagsPacket()
                            aacpManager.sendNotificationRequest()
                            aacpManager.sendRequestProximityKeys(AACPManager.Companion.ProximityKeyType.IRK.value)
                            if (!handleIncomingCallOnceConnected) stopHeadTracking()
                        }, 5000)

                        sendBroadcast(
                            Intent(AirPodsNotifications.AIRPODS_CONNECTED)
                                .putExtra("device", device)
                        )

                        setupStemActions()

                        while (socket.isConnected) {
                            socket.let { it ->
                                val buffer = ByteArray(1024)
                                val bytesRead = it.inputStream.read(buffer)
                                var data: ByteArray
                                if (bytesRead > 0) {
                                    data = buffer.copyOfRange(0, bytesRead)
                                    sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DATA).apply {
                                        putExtra("data", buffer.copyOfRange(0, bytesRead))
                                    })
                                    val bytes = buffer.copyOfRange(0, bytesRead)
                                    val formattedHex = bytes.joinToString(" ") { "%02X".format(it) }
//                                    CrossDevice.sendReceivedPacket(bytes)
                                    updateNotificationContent(
                                        true,
                                        sharedPreferences.getString("name", device.name),
                                        batteryNotification.getBattery()
                                    )

                                    aacpManager.receivePacket(data)

                                    if (!isHeadTrackingData(data)) {
                                        Log.d("AirPodsData", "Data received: $formattedHex")
                                        logPacket(data, "AirPods")
                                    }

                                } else if (bytesRead == -1) {
                                    Log.d("AirPods Service", "Socket closed (bytesRead = -1)")
                                    sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED))
                                    aacpManager.disconnected()
                                    return@launch
                                }
                            }
                        }
                        Log.d("AirPods Service", "Socket closed")
                        isConnectedLocally = false
                        socket.close()
                        aacpManager.disconnected()
                        updateNotificationContent(false)
                        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.d(TAG, "Failed to connect to socket: ${e.message}")
                showSocketConnectionFailureNotification("Failed to establish connection: ${e.localizedMessage}")
                isConnectedLocally = false
                this@AirPodsService.device = device
                updateNotificationContent(false)
            }
        } else {
            Log.d(TAG, "Already connected locally, skipping socket connection (isConnectedLocally = $isConnectedLocally, socket.isConnected = ${this::socket.isInitialized && socket.isConnected})")
        }
    }

    fun disconnectForCD() {
        if (!this::socket.isInitialized) return
        socket.close()
        MediaController.pausedWhileTakingOver = false
        Log.d(TAG, "Disconnected from AirPods, showing island.")
        showIsland(this, batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT}?.level!!.coerceAtMost(batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT}?.level!!),
            IslandType.MOVED_TO_REMOTE)
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    val connectedDevices = proxy.connectedDevices
                    if (connectedDevices.isNotEmpty()) {
                        MediaController.sendPause()
                    }
                }
                bluetoothAdapter.closeProfileProxy(profile, proxy)
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
        isConnectedLocally = false
//        CrossDevice.isAvailable = true
    }

    fun disconnectAirPods() {
        if (!this::socket.isInitialized) return
        socket.close()
        isConnectedLocally = false
        aacpManager.disconnected()
        attManager?.disconnect()
        updateNotificationContent(false)
        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED))

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    val connectedDevices = proxy.connectedDevices
                    if (connectedDevices.isNotEmpty()) {
                        MediaController.sendPause()
                    }
                }
                bluetoothAdapter.closeProfileProxy(profile, proxy)
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
        Log.d(TAG, "Disconnected AirPods upon user request")

    }

    val earDetectionNotification = AirPodsNotifications.EarDetection()
    val ancNotification = AirPodsNotifications.ANC()
    val batteryNotification = AirPodsNotifications.BatteryNotification()
    val conversationAwarenessNotification =
        AirPodsNotifications.ConversationalAwarenessNotification()

    @Suppress("unused")
    fun setEarDetection(enabled: Boolean) {
        if (config.earDetectionEnabled != enabled) {
            config.earDetectionEnabled = enabled
            sharedPreferences.edit { putBoolean("automatic_ear_detection", enabled) }
        }
    }

    fun getBattery(): List<Battery> {
//        if (!isConnectedLocally && CrossDevice.isAvailable) {
//            batteryNotification.setBattery(CrossDevice.batteryBytes)
//        }
        return batteryNotification.getBattery()
    }

    fun getANC(): Int {
//        if (!isConnectedLocally && CrossDevice.isAvailable) {
//            ancNotification.setStatus(CrossDevice.ancBytes)
//        }
        return ancNotification.status
    }

    fun disconnectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    try {
                        if (proxy.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED) {
                            Log.d(TAG, "Already disconnected from A2DP")
                            return
                        }
                        val method =
                            proxy.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.java)
                        method.invoke(proxy, device, 0)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method =
                            proxy.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.java)
                        method.invoke(proxy, device, 0)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HEADSET)
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    try {
                        val policyMethod = proxy.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.java)
                        policyMethod.invoke(proxy, device, 100)
                        val connectMethod =
                            proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        connectMethod.invoke(proxy, device) // reduces the slight delay between allowing and actually connecting
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        if (MediaController.pausedWhileTakingOver) {
                            MediaController.sendPlay()
                        }
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method =
                            proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HEADSET)
    }

    fun setName(name: String) {
        aacpManager.sendRename(name)

        if (config.deviceName != name) {
            config.deviceName = name
            sharedPreferences.edit { putString("name", name) }
        }

        updateNotificationContent(true, name, batteryNotification.getBattery())
        Log.d(TAG, "setName: $name")
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        clearPacketLogs()
        Log.d(TAG, "Service stopped is being destroyed for some reason!")

        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(ancModeReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(connectionReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(earReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            bleManager.stopScanning()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        isConnectedLocally = false
//        CrossDevice.isAvailable = true
        super.onDestroy()
    }

    var isHeadTrackingActive = false

    fun startHeadTracking() {
        isHeadTrackingActive = true
        val useAlternatePackets = sharedPreferences.getBoolean("use_alternate_head_tracking_packets", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && aacpManager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION)?.value?.get(0)?.toInt() != 1) {
            takeOver("call", startHeadTrackingAgain = true)
            Log.d(TAG, "Taking over for head tracking")
        } else {
            Log.w(TAG, "Will not be taking over for head tracking, might not work.")
        }
        if (useAlternatePackets) {
            aacpManager.sendDataPacket(aacpManager.createAlternateStartHeadTrackingPacket())
        } else {
            aacpManager.sendStartHeadTracking()
        }
        HeadTracking.reset()
    }

    fun stopHeadTracking() {
        val useAlternatePackets = sharedPreferences.getBoolean("use_alternate_head_tracking_packets", false)
        if (useAlternatePackets) {
            aacpManager.sendDataPacket(aacpManager.createAlternateStopHeadTrackingPacket())
        } else {
            aacpManager.sendStopHeadTracking()
        }
        isHeadTrackingActive = false
    }

    @SuppressLint("MissingPermission")
    fun reconnectFromSavedMac(){
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        device = bluetoothAdapter.bondedDevices.find {
            it.address == macAddress
        }
        if (device != null) {
            CoroutineScope(Dispatchers.IO).launch {
                connectToSocket(device!!, manual = true)
            }
        }
    }

}

private fun Int.dpToPx(): Int {
    val density = Resources.getSystem().displayMetrics.density
    return (this * density).toInt()
}
