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
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.ext.SdkExtensions
import android.provider.Settings
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.EldDecoder
import me.kavishdevar.librepods.audio.WavWriter
import me.kavishdevar.librepods.bluetooth.MacAddress
import me.kavishdevar.librepods.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.bluetooth.verifyRPA
import me.kavishdevar.librepods.data.apple.BuddyState
import me.kavishdevar.librepods.data.heartrate.HeartRateSample
import me.kavishdevar.librepods.data.recording.Recording
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.devices.AppleDevice
import me.kavishdevar.librepods.devices.AppleSettings
import me.kavishdevar.librepods.devices.AppleState
import me.kavishdevar.librepods.devices.BatteryComponent
import me.kavishdevar.librepods.devices.BatteryStatus
import me.kavishdevar.librepods.devices.ComponentStatus
import me.kavishdevar.librepods.devices.ConnectionState
import me.kavishdevar.librepods.devices.Device
import me.kavishdevar.librepods.devices.DeviceComponentState
import me.kavishdevar.librepods.presentation.activities.MainActivity
import me.kavishdevar.librepods.presentation.overlays.IslandType
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.presentation.widgets.BatteryWidget
import me.kavishdevar.librepods.utils.MediaController
import me.kavishdevar.librepods.utils.calculateLevel
import me.kavishdevar.librepods.utils.redactMac
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaInstant

private const val TAG = "LibrePodsService"

@SuppressLint("MissingPermission")
class LibrePodsService: Service() {
    inner class LocalBinder : Binder() {
        fun getService(): LibrePodsService = this@LibrePodsService
    }

    private val binder = LocalBinder()

    private val _devices = MutableStateFlow<Map<MacAddress, Device<*, *, *>>>(emptyMap())
    val devices = _devices.asStateFlow()

    private val deviceJobs = mutableMapOf<MacAddress, MutableList<Job>>()

    val irkMap = mutableMapOf<MacAddress, ByteArray>()
    val rpasByPublicMac = mutableMapOf<MacAddress, MutableSet<MacAddress>>()

    val rejectedRandomMac = mutableSetOf<MacAddress>()
    private val eldDecoder = EldDecoder()

    private val wavWriters = mutableMapOf<Recording, WavWriter>()

    private val appleRepository by lazy {
        (application as LibrePodsApplication).appleRepository
    }

    private val appDataRepository by lazy {
        (application as LibrePodsApplication).appDataRepository
    }

    private val widgetConfigRepository by lazy {
        (application as LibrePodsApplication).widgetConfigRepository
    }

    private val heartRateRepository by lazy {
        (application as LibrePodsApplication).heartRateRepository
    }

    private val recordingRepository by lazy {
        (application as LibrePodsApplication).recordingRepository
    }

    private val healthConnectClient by lazy {
        (application as LibrePodsApplication).healthConnectClient
    }

    private val islandWindow: IslandWindow by lazy {
        (application as LibrePodsApplication).islandWindow
    }

    private fun observeCameraState() {
        CoroutineScope(Dispatchers.IO).launch {
            LibrePodsAccessibilityService.isCameraOpen.collect { isCamera ->
                if (isCamera) {
                    Log.d(TAG, "Camera is open")
                } else {
                    Log.d(TAG, "Camera is closed")
                }
            }
        }
    }

    private var isCallRinging = false

    private val telephonyCallback = object: TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                isCallRinging = true
                _devices.value.values.firstOrNull { device ->
                    device is AppleDevice &&
                    device.connectionState.value == ConnectionState.CONNECTED &&
                    device.state.value.componentState.any { it.status == ComponentStatus.IN_EAR }
                }?.let { device ->
                    (device as AppleDevice).detectHeadGestures { accept ->
                        if (!isCallRinging) return@detectHeadGestures

                        try {
                            val telecomManager = getSystemService(TelecomManager::class.java)

                            @Suppress("DEPRECATION")
                            if (accept) {
                                telecomManager?.acceptRingingCall(
                                    VideoProfile.STATE_AUDIO_ONLY
                                )
                                Toast.makeText(
                                    this@LibrePodsService,
                                    getString(R.string.call_accepted),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@LibrePodsService,
                                    getString(R.string.call_rejected),
                                    Toast.LENGTH_SHORT
                                ).show()
                                telecomManager?.endCall()
                            }

                            isCallRinging = false
                            device.stopHeadTracking()

                        } catch (e: Exception) {
                            Log.e(TAG, "Error accepting call", e)
                        }
                    }
                }
            } else {
                if (isCallRinging) {
                    _devices.value.values.firstOrNull { device ->
                        device is AppleDevice &&
                        device.connectionState.value == ConnectionState.CONNECTED &&
                        device.state.value.componentState.any { it.status == ComponentStatus.IN_EAR }
                    }?.let { device ->
                        (device as AppleDevice).stopHeadGestureDetection()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        observeAppSettings()

        registerBluetoothReceivers()

        loadDevices()

        startBleScanner()

        MediaController.initialize(
            audioManager = getSystemService(AudioManager::class.java),
            sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE),
            localMac = null // TODO: smart routing. MAC_ADDRESS message gives host mac?
        )

        if (
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        ) {
            val telephonyManager = getSystemService(TelephonyManager::class.java)

            telephonyManager.registerTelephonyCallback(
                mainExecutor,
                telephonyCallback
            )
        }

        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // TODO: make this a sealed class or something, instead of stringly typed. also, make this different for widget so other apps can use it too
            "ACTION_SET_ANC_MODE" -> {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val macAddress = intent.getStringExtra("MAC_ADDRESS")?.let { MacAddress(it) }

                val ancMode = intent.getIntExtra("ANC_MODE", -1)
                val device =
                    widgetConfigRepository.widgetConfigs.value.find { it.appWidgetId == appWidgetId }
                        ?.let { config ->
                            devices.value[config.macAddress]
                        } ?: devices.value[macAddress]
                    ?: devices.value.values.firstOrNull { it.connectionState.value == ConnectionState.CONNECTED }

                if (device != null && ancMode != -1) {
                    Log.i(
                        TAG,
                        "Setting ANC mode to $ancMode for device ${device.macAddress.toRedactedString()}"
                    )
                    when (device) {
                        is AppleDevice -> device.setControlCommand(
                            ControlCommandIdentifier.LISTENING_MODE,
                            ancMode
                        )
                    }
                } else {
                    Log.w(TAG, "No connected Apple device found or invalid ANC mode: $ancMode")
                }
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        unregisterBluetoothReceivers()
        stopBleScanner()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun onDeviceConnected(bluetoothDevice: BluetoothDevice) {
        if (devices.value[MacAddress(bluetoothDevice.address)]?.connectionState?.value == ConnectionState.CONNECTED) {
            Log.d(TAG, "Device already connected: ${bluetoothDevice.address}")
            return
        }

        val device = devices.value[MacAddress(bluetoothDevice.address)] ?: createDevice(bluetoothDevice)

        if (device == null) {
            Log.d(TAG, "Unsupported device connected: ${bluetoothDevice.address}")
            return
        }

        when (device) {
            is AppleDevice -> CoroutineScope(Dispatchers.IO).launch {
                Log.i(TAG, "Loading device ${device.macAddress.toRedactedString()} from db")

                appleRepository.load(device.macAddress)?.let { entity ->
                    val cache = entity.cache
                    Log.i(
                        TAG,
                        "Loaded cached state for device ${device.macAddress.toRedactedString()}: $cache"
                    )
                    val settings = entity.settings
                    Log.i(
                        TAG,
                        "Loaded settings for device ${device.macAddress.toRedactedString()}: $settings"
                    )
                    val metadata = entity.metadata
                    Log.i(
                        TAG,
                        "Loaded metadata for device ${device.macAddress.toRedactedString()}: $metadata"
                    )

                    device.loadInitialState(
                        state = AppleState().copy(
                            capabilities = cache.capabilities,
                            magicKeys = cache.magicKeys,
                            controlStates = cache.controlStates,
                        ),
                        settings = settings,
                        metadata = metadata
                    )

                    if (device.settings.value.hrmAlertEnabled) {
                        device.startHr()
                    }
                }

                deviceJobs[MacAddress(bluetoothDevice.address)] = mutableListOf()

                deviceJobs[MacAddress(bluetoothDevice.address)]?.add(observeAppleState(device))
                deviceJobs[MacAddress(bluetoothDevice.address)]?.add(observeAppleSettings(device))
                deviceJobs[MacAddress(bluetoothDevice.address)]?.add(observeAppleMetadata(device))
                deviceJobs[MacAddress(bluetoothDevice.address)]?.add(observeAppleMicrophoneFrames(device))
            }
        }

        device.connect()

        Log.i(
            TAG,
            "Device connected: ${device.macAddress.toRedactedString()} (${device.javaClass.simpleName})"
        )

        _devices.update { it + (device.macAddress to device) }
    }

    private fun onDeviceDisconnected(mac: MacAddress) {
        Log.i(TAG, "Device disconnected: $mac")
        deviceJobs[mac]?.forEach { it.cancel() }
        devices.value[mac]?.disconnect()
        updateDeviceNotification(device = devices.value[mac] ?: return)
    }

    private fun loadDevices() {
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        val bondedDevices = bluetoothAdapter.bondedDevices

        bondedDevices.forEach { bluetoothDevice ->
            val device = createDevice(bluetoothDevice)
            if (device != null) {
                val notificationManager = getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(
                    "device_${device.macAddress}",
                    "Device ${device.metadata.value.name}",
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)

                _devices.update { it + (device.macAddress to device) }
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            val bluetoothDevice = intent.getParcelableExtra(
                "android.bluetooth.device.extra.DEVICE",
                BluetoothDevice::class.java
            )
            val action = intent.action

            if (bluetoothDevice != null) {
                when (action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        if (bluetoothDevice.uuids == null) {
                            bluetoothDevice.fetchUuidsWithSdp()
                        } else {
                            onDeviceConnected(bluetoothDevice)
                        }
                    }

                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDeviceDisconnected(
                        MacAddress(
                            bluetoothDevice.address
                        )
                    )

                    BluetoothDevice.ACTION_UUID -> onDeviceConnected(bluetoothDevice)
                }
            }
        }
    }

    fun registerBluetoothReceivers() {
        val intentFilter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.device.action.UUID")
        }

        registerReceiver(bluetoothReceiver, intentFilter, RECEIVER_EXPORTED)
    }

    fun unregisterBluetoothReceivers() {
        unregisterReceiver(bluetoothReceiver)
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d(TAG,
                "${result.device.address} " +
                    "sid=${result.advertisingSid} " +
                    "legacy=${result.isLegacy} " +
                    "phy=${result.primaryPhy} " +
                    "rssi=${result.rssi}"
            )
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult?>?) {
            if (results == null) return
            for (result in results) {
                if (result != null) {
                    handleScanResult(result)
                }
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val macAddress = MacAddress(result.device.address)

        if (rejectedRandomMac.contains(macAddress)) return

        val device = getDeviceFromBleMac(macAddress)?: return

        Log.d(TAG, "Scan result for device ${device.macAddress.toRedactedString()}")

        when (device) {
            is AppleDevice -> {
                val manufacturerData = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return

                Log.i(TAG, "Apple device scan result: ${manufacturerData.toHexString()}")
                // hell. TODO: do stuff
            }
        }
    }

    private fun getDeviceFromBleMac(bleMac: MacAddress): Device<*, *, *>? {
        var deviceMac: MacAddress? = devices.value[bleMac]?.macAddress

        if (deviceMac == null) {
            Log.d(TAG, "BLE random address found: $bleMac")
            rpasByPublicMac.forEach { (address, addresses) ->
                if (addresses.contains(bleMac)) {
                    deviceMac = address
                }
            }
        }

        if (deviceMac == null) {
            irkMap.forEach { (macAddress, irk) ->
                Log.d(TAG, "Verifiying $bleMac against ${irk.toHexString()}")
                if (verifyRPA(bleMac.value, irk)) {
                    Log.i(TAG, "New RPA for device ${macAddress.toRedactedString()}")
                    deviceMac = macAddress
                    val newSet = rpasByPublicMac[macAddress] ?: mutableSetOf()
                    newSet.add(bleMac)
                    rpasByPublicMac[macAddress] = newSet
                }
            }
        }

        if (deviceMac == null) {
            rejectedRandomMac.add(bleMac)
        }

        return devices.value.values.firstOrNull { it.macAddress == deviceMac }
    }

    private fun startBleScanner() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter

        val bleScanner = bluetoothAdapter.bluetoothLeScanner

        if (bleScanner == null) {
            Log.w(TAG, "startBleScanner: ble scanner not available")
            return
        }

        val appSettings = appDataRepository.settings.value

        val scanSettings = ScanSettings.Builder()
            .setScanMode(appSettings.bleScanMode)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(appSettings.bleReportDelay)
            .build()

        val manufacturerData = byteArrayOf(0x07, 0x19)
        val manufacturerMask = byteArrayOf(
            0xFF.toByte(),
            0xFF.toByte()
        )

        val filter = ScanFilter.Builder()
            .setManufacturerData(
                0x004C,
                manufacturerData,
                manufacturerMask
            )
            .build()

        try {
            bleScanner.startScan(listOf(filter), scanSettings, bleScanCallback)
            Log.i(TAG, "Started BLE scan with user-set params: scanMode: ${appSettings.bleScanMode}, reportDelay: ${appSettings.bleReportDelay}")
        } catch (e: Exception) {
            if (e.message?.contains("too frequently") == true) {
                CoroutineScope(Dispatchers.IO).launch {
                    delay(5.seconds)
                    startBleScanner()
                }
            } else {
                Log.e(TAG, "Error starting BLE scan", e)
            }
        }
    }

    private fun stopBleScanner() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter

        val bleScanner = bluetoothAdapter.bluetoothLeScanner

        if (bleScanner == null) {
            Log.w(TAG, "stopBleScanner: ble scanner not available")
            return
        }

        try {
            bleScanner.stopScan(bleScanCallback)
            Log.i(TAG, "Stopped BLE scan")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scan", e)
        }
    }

    private fun createDevice(
        bluetoothDevice: BluetoothDevice
    ): Device<*, *, *>? {

        val aacpUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter

        val isConnectedMethod = BluetoothDevice::class.java.getMethod("isConnected")
        isConnectedMethod.isAccessible = true

        val device = when {
            bluetoothDevice.uuids?.contains(aacpUuid) == true -> {
                Log.i(TAG, "Apple device detected: ${bluetoothDevice.address.redactMac()}")
                AppleDevice(
                    context = this,
                    bluetoothAdapter = bluetoothAdapter,
                    bluetoothDevice = bluetoothDevice,
                    currentState = if (isConnectedMethod.invoke(bluetoothDevice) as Boolean) ConnectionState.AVAILABLE else ConnectionState.DISCONNECTED
                )
            }

            else -> null
        }

        when (device) {
            is AppleDevice -> CoroutineScope(Dispatchers.IO).launch {
                Log.i(TAG, "Loading device ${device.macAddress.toRedactedString()} from db")

                appleRepository.load(device.macAddress)?.let { entity ->
                    val cache = entity.cache

                    // load irk at the earliest so we can start parsing
                    val irk = entity.cache.magicKeys[MagicKeyType.IRK]
                    if (irk != null) {
                        irkMap[device.macAddress] = irk
                        rejectedRandomMac.clear()
                        Log.d(TAG, "Loaded IRK for device ${device.macAddress.toRedactedString()}")
                    }

                    Log.i(
                        TAG,
                        "Loaded cached state for device ${device.macAddress.toRedactedString()}: $cache"
                    )
                    val settings = entity.settings
                    Log.i(
                        TAG,
                        "Loaded settings for device ${device.macAddress.toRedactedString()}: $settings"
                    )
                    val metadata = entity.metadata
                    Log.i(
                        TAG,
                        "Loaded metadata for device ${device.macAddress.toRedactedString()}: $metadata"
                    )

                    device.loadInitialState(
                        state = AppleState().copy(
                            capabilities = cache.capabilities,
                            magicKeys = cache.magicKeys,
                            controlStates = cache.controlStates,
                        ),
                        settings = settings,
                        metadata = metadata
                    )
                }

                // assuming that the device is not already in the map
                deviceJobs[MacAddress(bluetoothDevice.address)] = mutableListOf()

                deviceJobs[MacAddress(bluetoothDevice.address)]?.add(observeAppleState(device))
                deviceJobs[MacAddress(bluetoothDevice.address)]?.add(observeAppleSettings(device))
                deviceJobs[MacAddress(bluetoothDevice.address)]?.add(observeAppleMetadata(device))
            }
        }

        return device
    }

    fun observeAppSettings(): Job {
        var oldAppSettings: AppSettingsEntity = appDataRepository.settings.value

        return CoroutineScope(Dispatchers.IO).launch {
            appDataRepository.settings.collect { settings ->
                if (oldAppSettings.bleReportDelay != settings.bleReportDelay || oldAppSettings.bleScanMode != settings.bleScanMode) {
                    Log.d(TAG, "BLE settings changed: $settings, restarting scanner")
                    stopBleScanner()
                    startBleScanner()
                }
                islandWindow.updateSettings(settings)
                oldAppSettings = settings
            }
        }
    }

    fun observeAppleState(device: AppleDevice): Job = CoroutineScope(Dispatchers.IO).launch {
        var previousState = device.state.value

        device.state.collect { state ->
            val deviceSettings = device.settings.value


            if (state.aacpPackets != previousState.aacpPackets) {
                if (!appDataRepository.state.value.hasConnectedToAACP) {
                    appDataRepository.updateState { it.copy(hasConnectedToAACP = true) }
                }
                if (appDataRepository.state.value.firstSuccessfulConnectionTime == null) {
                    appDataRepository.updateState { it.copy(firstSuccessfulConnectionTime = System.currentTimeMillis()) }
                }
            }

            when {
                state.capabilities != previousState.capabilities -> {
                    Log.i(
                        TAG,
                        "capabilities changed for device ${device.macAddress.toRedactedString()}"
                    )
                    Log.d(TAG, "capabilities: ${state.capabilities}")

                    if (state.capabilities.isNotEmpty()) {
                        appleRepository.saveCacheFromState(device.macAddress, state)
                    }
                }

                state.battery != previousState.battery -> {
                    Log.i(
                        TAG,
                        "battery state changed for device ${device.macAddress.toRedactedString()}"
                    )
                    Log.d(TAG, "battery state: ${state.battery}")

                    Log.d(TAG, "updating widgets")
                    updateWidgets()

                    Log.d(TAG, "updating island window")
                    if (islandWindow.isVisible) {
                        val leftBattery = state.battery.find { it.component == BatteryComponent.LEFT }
                        val rightBattery = state.battery.find { it.component == BatteryComponent.RIGHT }

                        val leftLevel = leftBattery?.level ?: 0
                        val rightLevel = rightBattery?.level ?: 0

                        val displayBatteryLevel = when {
                            leftLevel > 0 && rightLevel > 0 -> minOf(leftLevel, rightLevel)
                            leftLevel > 0 -> leftLevel
                            rightLevel > 0 -> rightLevel
                            else -> 0
                        }

                        islandWindow.updateBattery(displayBatteryLevel)
                    }

                    Log.d(TAG, "updating notification")
                    updateDeviceNotification(device = device)

                    // TODO:Shizuku
                    /* Log.d(TAG, "updating bluetooth metadata")
                    shizuku.setMetadata(
                        device.macAddress.value,
                        BluetoothMetadata.METADATA_UNTETHERED_CASE_BATTERY,
                        state.battery.find { it.component == BatteryComponent.CASE }?.level.toString()
                            .toByteArray()
                    )
                    shizuku.setMetadata(
                        device.macAddress.value,
                        BluetoothMetadata.METADATA_UNTETHERED_CASE_CHARGING,
                        (if (state.battery.find { it.component == BatteryComponent.CASE }?.status == BatteryStatus.CHARGING
                            || state.battery.find { it.component == BatteryComponent.CASE }?.status == BatteryStatus.OPTIMIZED_CHARGING
                        ) "1" else "0").toByteArray()
                    )
                    shizuku.setMetadata(
                        device.macAddress.value,
                        BluetoothMetadata.METADATA_UNTETHERED_LEFT_BATTERY,
                        state.battery.find { it.component == BatteryComponent.LEFT }?.level.toString()
                            .toByteArray()
                    )
                    shizuku.setMetadata(
                        device.macAddress.value,
                        BluetoothMetadata.METADATA_UNTETHERED_LEFT_CHARGING,
                        (if (state.battery.find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.CHARGING
                            || state.battery.find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.OPTIMIZED_CHARGING
                        ) "1" else "0").toByteArray()
                    )
                    shizuku.setMetadata(
                        device.macAddress.value,
                        BluetoothMetadata.METADATA_UNTETHERED_RIGHT_BATTERY,
                        state.battery.find { it.component == BatteryComponent.RIGHT }?.level.toString()
                            .toByteArray()
                    )
                    shizuku.setMetadata(
                        device.macAddress.value,
                        BluetoothMetadata.METADATA_UNTETHERED_RIGHT_CHARGING,
                        (if (state.battery.find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.CHARGING
                            || state.battery.find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.OPTIMIZED_CHARGING
                        ) "1" else "0").toByteArray()
                    ) */
                }

                state.componentState != previousState.componentState -> {
                    Log.i(
                        TAG,
                        "component state changed for device ${device.macAddress.toRedactedString()}"
                    )
                    Log.d(TAG, "component state: ${state.componentState}")

                    val earDetectionCtrlCmdValue =
                        state.controlStates[ControlCommandIdentifier.EAR_DETECTION_CONFIG]
                            ?: byteArrayOf(0x01.toByte())
                    Log.d(
                        TAG,
                        "ear detection control command value: ${earDetectionCtrlCmdValue.toHexString()}"
                    )
                    val earDetectionEnabled = earDetectionCtrlCmdValue[0] == 0x01.toByte() || deviceSettings.earDetectionEnabled // temporary, cache broken (?)
                    Log.d(TAG, "ear detection enabled: $earDetectionEnabled")
                    if (earDetectionEnabled) {
                        processComponentStateChange(
                            device = device,
                            previousComponentState = previousState.componentState,
                            newComponentState = state.componentState,
                            disconnectWhenNotWearing = deviceSettings.disconnectWhenNotWearing
                        )
                    }

                    Log.d(TAG, "updating notification")
                    updateDeviceNotification(device = device)
                }

                state.controlStates != previousState.controlStates -> {
                    Log.i(
                        TAG,
                        "control states changed for device ${device.macAddress.toRedactedString()}"
                    )
                    Log.d(TAG, "control states: ${state.controlStates}")

                    if (state.controlStates.isNotEmpty()) {
                        appleRepository.saveCacheFromState(device.macAddress, state)
                    }

                    Log.d(TAG, "updating notification")
                    updateDeviceNotification(device = device)
                }

                state.conversationAwarenessState != previousState.conversationAwarenessState -> {
                    Log.i(
                        TAG,
                        "conversation awareness state changed for device ${device.macAddress.toRedactedString()}"
                    )
                    Log.d(
                        TAG,
                        "conversation awareness state: ${state.conversationAwarenessState}"
                    )

                    when (state.conversationAwarenessState) {
                        1 -> {
                            MediaController.startSpeaking()
                            MediaController.setVolume(
                                deviceSettings.conversationAwarenessVolume.toInt()
                            )
                        }

                        2 -> {
                            MediaController.setVolume(
                                deviceSettings.conversationAwarenessReducedVolume.toInt()
                            )
                        }

                        3 -> {
                            MediaController.setVolume(
                                deviceSettings.conversationAwarenessVolume.toInt()
                            )
                        }

                        6, 7, 8, 9 -> {
                            MediaController.stopSpeaking()
                        }
                    }
                }

                state.currentHeartRate?.timestamp != previousState.currentHeartRate?.timestamp -> {
                    state.currentHeartRate?.let { heartRateSample ->
                        Log.i(
                            TAG,
                            "current heart rate changed from device ${device.macAddress.toRedactedString()}"
                        )
                        Log.d(
                            TAG,
                            "current heart rate: ${heartRateSample.bpm} bpm, timestamp: ${heartRateSample.timestamp.toJavaInstant()}. processing."
                        )

                        processHeartRateSample(
                            heartRateSample = heartRateSample,
                            interval = state.heartRateInterval,
                            alertThreshold = deviceSettings.hrmAlertThreshold
                        )
                    }
                }

                state.recordingState != previousState.recordingState -> {
                    Log.i(
                        TAG,
                        "recording state changed for device ${device.macAddress.toRedactedString()}"
                    )
                    Log.d(TAG, "recording state: ${state.recordingState}")
                    if (previousState.recordingState.isRecording && !state.recordingState.isRecording) {
                        previousState.recordingState.recording?.let { recording ->
                            wavWriters[recording]?.close()
                        }
                    } else if (!previousState.recordingState.isRecording && state.recordingState.isRecording) {
                        state.recordingState.recording?.let { recording ->
                            try {
                                val wavWriter = WavWriter(recording.file)
                                wavWriters[recording] = wavWriter
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to create WavWriter for recording: ${recording.file}", e)
                            }
                        }
                    } else {
                        Log.w(TAG, "Recording state changed but no action taken. previous: ${previousState.recordingState}, current: ${state.recordingState}")
                    }
                }
            }
            previousState = state
        }
    }

    fun observeAppleSettings(device: AppleDevice): Job = CoroutineScope(Dispatchers.IO).launch {
        var previousSettings = device.settings.value

        device.settings.collect { settings ->
            if (settings != previousSettings) {
                Log.i(TAG, "settings changed for device ${device.macAddress.toRedactedString()}")
                Log.d(TAG, "settings: $settings")

                appleRepository.saveSettings(device.macAddress, settings)
            }
            previousSettings = settings
        }
    }

    fun observeAppleMetadata(device: AppleDevice): Job = CoroutineScope(Dispatchers.IO).launch {
        var previousMetadata = device.metadata.value

        device.metadata.collect { metadata ->
            if (metadata != previousMetadata) {
                Log.i(TAG, "metadata changed for device ${device.macAddress.toRedactedString()}")
                Log.d(TAG, "metadata: $metadata")

                appleRepository.saveMetadata(device.macAddress, metadata)

                // startHr() needs the firmware version; on connect it may not have been known yet
                if (previousMetadata.version3.isEmpty() && metadata.version3.isNotEmpty() &&
                    device.settings.value.hrmAlertEnabled && device.state.value.hrmState == BuddyState.INACTIVE
                ) {
                    device.startHr()
                }

                setAppleBluetoothMetadata(device)

                val notificationManager = getSystemService(NotificationManager::class.java)
                val channel =
                    notificationManager.getNotificationChannel("device_${device.macAddress}")
                channel?.name = "Device ${metadata.name}"
                notificationManager.createNotificationChannel(channel)
            }
            previousMetadata = metadata
        }
    }

    fun observeAppleMicrophoneFrames(device: AppleDevice): Job = CoroutineScope(Dispatchers.IO).launch {
        device.microphoneFrames.collect { frame ->
            val state = device.state.value

            if (state.recordingState.isRecording) {
                eldDecoder.decode(
                    accessUnit = frame.accessUnit
                ) { pcm ->
                    wavWriters[state.recordingState.recording]?.write(pcm)

                    device.updateState {
                        it.copy(
                            microphoneState = state.microphoneState.copy(
                                isActive = true,
                                packetsReceived = it.microphoneState.packetsReceived + 1,
                                durationMs = it.microphoneState.durationMs + 30,
                                level = calculateLevel(pcm)
                            )
                        )
                    }
                }
            }
        }
    }

    fun showIsland(
        device: Device<*, *, *>,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = false,
        otherDeviceName: String? = null
    ) {
        Log.d(TAG, "Showing island window")

        val state = device.state.value
        val settings = device.settings.value

        when (state) {
            is AppleState -> {
                val device = device as AppleDevice
                val state = device.state.value
                val settings = settings as AppleSettings
                val metadata = device.metadata.value

                if (state.componentState.isEmpty()) {
                    Log.w(TAG, "No component state available, can't show island")
                    return
                }

                if (settings.showIslandPopup) {
                    if (!Settings.canDrawOverlays(this)) {
                        Log.w(TAG, "No permission for SYSTEM_ALERT_WINDOW")
                        return
                    }

                    Log.i(TAG, "Showing island for device ${device.macAddress.toRedactedString()}")

                    val leftBattery = state.battery.find { it.component == BatteryComponent.LEFT }?.level ?: 0
                    val rightBattery = state.battery.find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                    val batteryPercentage = leftBattery.coerceAtMost(rightBattery)

                    islandWindow.show(
                        name = metadata.name,
                        batteryLevel = batteryPercentage,
                        type = type,
                        reversed = reversed,
                        otherDeviceName = otherDeviceName,

                        openApp = {
                            Log.d(TAG, "Opening app")
                            val intent = Intent(
                                this,
                                MainActivity::class.java,
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        },

                        showOffListeningMode = state.controlStates[ControlCommandIdentifier.ALLOW_OFF_OPTION]?.getOrNull(0)?.toInt() == 1,
                        noiseControlModeValue = state.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 3,
                        onNoiseControlModeChanged = {
                            device.setControlCommand(
                                ControlCommandIdentifier.LISTENING_MODE, it
                            )
                        },
                        adaptiveStrength = state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.getOrNull(0)?.toInt() ?: 50,
                        onAdaptiveStrengthChanged = {
                            device.setControlCommand(
                                ControlCommandIdentifier.AUTO_ANC_STRENGTH, it
                            )
                        }
                    )
                }
            }

            else -> {
                Log.d(
                    TAG,
                    "Unsupported device state: ${device.state.value.javaClass.simpleName}, not showing island"
                )
                return
            }
        }
    }

    fun updateDeviceNotification(
        device: Device<*, *, *>
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationId = device.macAddress.toNotificationId()

        if (device.connectionState.value == ConnectionState.CONNECTED || device.connectionState.value == ConnectionState.AVAILABLE) {
            when (device) {
                is AppleDevice -> {
                    Log.d(
                        TAG,
                        "Updating notification for Apple device ${device.macAddress.toRedactedString()}"
                    )

                    val updatedNotificationBuilder =
                        NotificationCompat.Builder(this, "device_${device.macAddress}")
                            .setSmallIcon(R.drawable.ic_airpods)
                            .setContentTitle(device.metadata.value.name)
                            .setContentText(device.state.value.battery.joinToString(" ") { "${it.component.name[0]}: ${it.level}%" })
                            .setContentIntent(pendingIntent)
                            .setCategory(Notification.CATEGORY_STATUS)
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setOngoing(true)
                            .addAction(
                                R.drawable.ic_transparency, // icon never shows up?? (tested on AOSP ROMs)
                                "Transparency",
                                PendingIntent.getService(
                                    this,
                                    notificationId + 3,
                                    Intent(this, LibrePodsService::class.java).apply {
                                        action = "ACTION_SET_ANC_MODE"
                                        putExtra("MAC_ADDRESS", device.macAddress.toString())
                                        putExtra("ANC_MODE", 3)
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                ),
                            )
                            .addAction(
                                R.drawable.ic_adaptive,
                                "Adaptive",
                                PendingIntent.getService(
                                    this,
                                    notificationId + 4,
                                    Intent(this, LibrePodsService::class.java).apply {
                                        action = "ACTION_SET_ANC_MODE"
                                        putExtra("MAC_ADDRESS", device.macAddress.toString())
                                        putExtra("ANC_MODE", 4)
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                ),
                            )
                            .addAction(
                                R.drawable.ic_noise_cancellation,
                                "Noise Cancellation",
                                PendingIntent.getService(
                                    this,
                                    notificationId + 2,
                                    Intent(this, LibrePodsService::class.java).apply {
                                        action = "ACTION_SET_ANC_MODE"
                                        putExtra("MAC_ADDRESS", device.macAddress.toString())
                                        putExtra("ANC_MODE", 2)
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                ),
                            )

                    val updatedNotification = updatedNotificationBuilder.build()

                    notificationManager.notify(notificationId, updatedNotification)
                }
            }

        } else {
            notificationManager.cancel(notificationId)
        }
    }

    fun startForegroundNotification() {
        val disconnectedNotificationChannel = NotificationChannel(
            "foreground_service_status",
            getString(R.string.foreground_service_status),
            NotificationManager.IMPORTANCE_NONE
        )

        val hrmAlertChannel = NotificationChannel(
            "hrm_alert",
            getString(R.string.heart_rate_alert),
            NotificationManager.IMPORTANCE_HIGH
        )

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(disconnectedNotificationChannel)
        notificationManager.createNotificationChannel(hrmAlertChannel)

        val notificationSettingsIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, "foreground_service_status")
        }

        val pendingIntentNotifDisable = PendingIntent.getActivity(
            this,
            0,
            notificationSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "foreground_service_status")
            .setSmallIcon(R.drawable.ic_airpods).setContentTitle(getString(R.string.service_running))
            .setContentText(getString(R.string.foreground_notification_description))
            .setContentIntent(pendingIntentNotifDisable).setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()

        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateWidgets() {
        // TODO: Room
        val widgetMobileBatteryEnabled = getSharedPreferences(
            "settings",
            MODE_PRIVATE
        ).getBoolean("show_phone_battery_in_widget", false)

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, BatteryWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val remoteViews = RemoteViews(packageName, R.layout.battery_widget).also { it ->
            val openActivityIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            it.setOnClickPendingIntent(R.id.battery_widget, openActivityIntent)

            // TODO: device-specific widgets
            val device =
                devices.value.values.firstOrNull { it is AppleDevice && it.connectionState.value == ConnectionState.CONNECTED } as? AppleDevice

            val leftBattery =
                device?.state?.value?.battery?.find { it.component == BatteryComponent.LEFT }
            val rightBattery =
                device?.state?.value?.battery?.find { it.component == BatteryComponent.RIGHT }
            val caseBattery =
                device?.state?.value?.battery?.find { it.component == BatteryComponent.CASE }

            it.setTextViewText(R.id.left_battery_widget, leftBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.left_battery_progress, 100, leftBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.left_charging_icon,
                if (leftBattery?.status == BatteryStatus.CHARGING || leftBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.right_battery_widget, rightBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.right_battery_progress, 100, rightBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.right_charging_icon,
                if (rightBattery?.status == BatteryStatus.CHARGING || rightBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.case_battery_widget, caseBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.case_battery_progress, 100, caseBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.case_charging_icon,
                if (caseBattery?.status == BatteryStatus.CHARGING || caseBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
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
                    R.id.phone_battery_widget, "$batteryLevel%"
                )
                it.setViewVisibility(
                    R.id.phone_charging_icon, if (charging) View.VISIBLE else View.GONE
                )
                it.setProgressBar(
                    R.id.phone_battery_progress, 100, batteryLevel, false
                )
            }
        }
        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    private fun processComponentStateChange(
        device: Device<*, *, *>,
        previousComponentState: Set<DeviceComponentState>,
        newComponentState: Set<DeviceComponentState>,
        disconnectWhenNotWearing: Boolean
    ) {
        val old = earPresenceOf(previousComponentState)
        val new = earPresenceOf(newComponentState)
        if (old == new) return

        Log.d(TAG, "earPresence: $old -> $new")

        // new != NONE because old!=new
        if (old == EarPresence.NONE) {
            showIsland(
                device = device,
                type = IslandType.CONNECTED
            )
            Log.i(TAG, "User put in at least one component, showing island.")
        }

        if (new == EarPresence.NONE && appDataRepository.settings.value.islandSettings.keepCompactVisible) {
            islandWindow.close()
        }

        var justEnabledA2dp = false

        when {
            old == EarPresence.NONE -> {
                Log.d(
                    TAG,
                    "User put in at least one component, enabling audio for device ${device.macAddress.toRedactedString()}"
                )
                device.enableAudio()
                device.connectA2dp()
                justEnabledA2dp = true

                device.waitForA2dpConnection(this) {
                    MediaController.sendPlay()
                    MediaController.iPausedTheMedia = false
                }

                if (MediaController.getMusicActive()) {
                    MediaController.userPlayedTheMedia = true
                }
                if (new == EarPresence.PARTIAL) {
                    MediaController.sendPlay()
                    MediaController.iPausedTheMedia = false
                }
            }

            new == EarPresence.NONE -> {
                MediaController.sendPause(force = true)
                if (disconnectWhenNotWearing) {
                    Log.d(
                        TAG,
                        "Disconnecting audio for device ${device.macAddress.toRedactedString()} because user took out all components and disconnectWhenNotWearing is true"
                    )
                    device.disableAudio()
                    device.disconnectAudio()
                }
            }
        }

        when {
            new == EarPresence.FULL -> {
                Log.d("AirPodsParser", "User put in all components.")
                MediaController.userPlayedTheMedia = false
                if (!justEnabledA2dp) {
                    MediaController.sendPlay()
                    MediaController.iPausedTheMedia = false
                }
            }

            old == EarPresence.FULL -> {
                Log.d("AirPodsParser", "User took one out.")
                MediaController.userPlayedTheMedia = false
                if (new == EarPresence.PARTIAL) {
                    MediaController.sendPause()
                }
            }
        }
    }

    private fun processHeartRateSample(heartRateSample: HeartRateSample, interval: Duration, alertThreshold: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "inserting to local db")
            heartRateRepository.insert(heartRateSample)
        }

        CoroutineScope(Dispatchers.Default).launch {
            val notificationManager = getSystemService(NotificationManager::class.java)

            if (heartRateSample.bpm > alertThreshold) {
                val notification = NotificationCompat.Builder(this@LibrePodsService, "hrm_alert")
                    .setSmallIcon(R.drawable.ic_pulse_alert)
                    .setContentTitle(getString(R.string.high_heart_rate))
                    .setContentText(
                        getString(
                            R.string.high_heart_rate_notification_text,
                            heartRateSample.bpm
                        )
                    )
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setOngoing(true)
                    .build()

                notificationManager.notify(999, notification)
            } else {
                if (notificationManager.activeNotifications.any { it.id == 999 }) {
                    notificationManager.cancel(999)
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 7) {
                if (checkSelfPermission(HealthPermission.getWritePermission(HeartRateRecord::class)) != PackageManager.PERMISSION_GRANTED) return@launch
                val healthConnectHeartRateSample = HeartRateRecord.Sample(
                    time = heartRateSample.timestamp.toJavaInstant(),
                    beatsPerMinute = heartRateSample.bpm.toLong()
                )

                val zoneOffset =
                    ZoneOffset.systemDefault().rules.getOffset(heartRateSample.timestamp.toJavaInstant())

                val heartRateRecord = HeartRateRecord(
                    startTime = (heartRateSample.timestamp - interval).toJavaInstant(),
                    endTime = heartRateSample.timestamp.toJavaInstant(),
                    startZoneOffset = zoneOffset,
                    endZoneOffset = zoneOffset,
                    samples = listOf(healthConnectHeartRateSample),
                    metadata = androidx.health.connect.client.records.metadata.Metadata.autoRecorded(
                        device = androidx.health.connect.client.records.metadata.Device(type = androidx.health.connect.client.records.metadata.Device.TYPE_HEARABLE)
                    )
                )

                if (healthConnectClient != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            healthConnectClient!!.insertRecords(listOf(heartRateRecord))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error inserting heart rate record", e)
                        }
                    }
                } else {
                    Log.w(TAG, "Health Connect client not available")
                }
            } else {
                Log.d(TAG, "U SDK Extension <7")
            }
        }
    }

    // TODO: Shizuku
    private fun setAppleBluetoothMetadata(
        @Suppress("unused") device: AppleDevice
    ) {
        /* val macAddress = device.macAddress.value
        val metadata = device.metadata.value
        val spec = AirPodsSpecs.getSpec(metadata.model)

        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_MAIN_ICON,
            resToUri(spec.primaryImageRes).toString().toByteArray()
        )
        shizuku.setMetadata(
            macAddress, BluetoothMetadata.METADATA_MODEL_NAME, metadata.modelNumber.toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_DEVICE_TYPE,
            BluetoothMetadata.DEVICE_TYPE_UNTETHERED_HEADSET.toByteArray()
        )
        spec.caseImageRes?.let {
            shizuku.setMetadata(
                macAddress,
                BluetoothMetadata.METADATA_UNTETHERED_CASE_ICON,
                resToUri(it).toString().toByteArray()
            )
        }
//        shizuku.setMetadata(
//            macAddress,
//            BluetoothMetadata.METADATA_UNTETHERED_RIGHT_ICON,
//            resToUri(spec.components.find { it.type == DeviceComponent.RIGHT }.imageRes).toString().toByteArray()
//        )
//        shizuku.setMetadata(
//            macAddress,
//            BluetoothMetadata.METADATA_UNTETHERED_LEFT_ICON,
//            resToUri(spec.components.find { it.type == DeviceComponent.LEFT }.imageRes).toString().toByteArray()
//        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_MANUFACTURER_NAME,
            metadata.manufacturer.toByteArray()
        )
        shizuku.setMetadata(
            macAddress, BluetoothMetadata.METADATA_COMPANION_APP, "me.kavishdevar.librepods".toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
            "20".toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
            "20".toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
            "20".toByteArray()
        )
        Log.d(TAG, "Metadata set for apple device ${device.macAddress.toRedactedString()}")
     */
    }
}

// TODO: move out of this file
private enum class EarPresence { NONE, PARTIAL, FULL }

private fun earPresenceOf(components: Set<DeviceComponentState>): EarPresence {
    if (components.isEmpty()) return EarPresence.NONE
    val inEarCount = components.count { it.status == ComponentStatus.IN_EAR }
    return when (inEarCount) {
        0 -> EarPresence.NONE
        components.size -> EarPresence.FULL
        else -> EarPresence.PARTIAL
    }
}
