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

package me.kavishdevar.librepods.presentation.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.data.AirPodsInstance
import me.kavishdevar.librepods.data.AirPodsModels
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.ControlCommandRepository
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import me.kavishdevar.librepods.services.AirPodsService

@Suppress("ArrayInDataClass")
data class AirPodsUiState(
    val deviceName: String,

    val isLocallyConnected: Boolean = false,

    val instance: AirPodsInstance? = null,
    val capabilities: Set<Capability> = emptySet(),

    val controlStates: Map<ControlCommandIdentifiers, ByteArray> = emptyMap(),
    val offListeningMode: Boolean = true,

    val battery: List<Battery> = emptyList(),
    val ancMode: Int = 3,

    val modelName: String = "",
    val actualModel: String = "",
    val serialNumbers: List<String> = emptyList(),
    val version1: String = "",
    val version2: String = "",
    val version3: String = "",

    val headTrackingActive: Boolean = false,
    val headGesturesEnabled: Boolean = true,

    val eqData: FloatArray = floatArrayOf(),

    val automaticEarDetectionEnabled: Boolean = true,
    val automaticConnectionEnabled: Boolean = true,

    val leftAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,
    val rightAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,

    val loudSoundReductionEnabled: Boolean = false,
    val transparencyData: ByteArray = byteArrayOf(),
    val hearingAidData: ByteArray = byteArrayOf(),

    val isPremium: Boolean = false,
    val vendorIdHook: Boolean = false
)

class AirPodsViewModel(
    private val service: AirPodsService,
    private val sharedPreferences: SharedPreferences,
    private val controlRepo: ControlCommandRepository,
    private val appContext: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AirPodsUiState(
            deviceName = sharedPreferences.getString(
                "name",
                "AirPods Pro"
            ) ?: "AirPods Pro"
        )
    )
    val uiState: StateFlow<AirPodsUiState> = _uiState

    private var isDemoMode = false
    val demoActivated = MutableSharedFlow<Unit>()

    private val listeners =
        mutableMapOf<ControlCommandIdentifiers, AACPManager.ControlCommandListener>()

    private val xposedRemotePref = XposedRemotePrefProvider.create()

    private lateinit var broadcastReceiver: BroadcastReceiver

    private val _cameraAction = MutableStateFlow(
        sharedPreferences.getString("camera_action", null)
            ?.let { value -> AACPManager.Companion.StemPressType.entries.find { it.name == value } })

    val cameraAction: StateFlow<AACPManager.Companion.StemPressType?> = _cameraAction

    fun setCameraAction(action: AACPManager.Companion.StemPressType?) {
        sharedPreferences.edit {
            if (action == null) remove("camera_action")
            else putString("camera_action", action.name)
        }
        _cameraAction.value = action
    }

    init {
        observeBroadcasts()
        loadName()
        loadInstance()
        loadSharedPreferences()
        setupControlObservers()
        observeBilling()
        loadControlList()
        observeATT()
        if (isDemoMode) activateDemoMode()
    }

    override fun onCleared() {
        listeners.forEach { (id, listener) ->
            controlRepo.remove(id, listener)
        }

        appContext.unregisterReceiver(broadcastReceiver)

        super.onCleared()
    }

    private fun loadName() {
        val name = sharedPreferences.getString("name", "AirPods Pro")!!
        _uiState.update { it.copy(deviceName = name) }
    }

    private fun observeBilling() {
        if (isDemoMode) return
        viewModelScope.launch {
//            if (!BuildConfig.PLAY_BUILD) billingFirstCollectDone = true // FOSS doesn't send multiple events
            BillingManager.provider.isPremium.collect { premium ->
//                if (!billingFirstCollectDone) {
//                    billingFirstCollectDone = true
//                    return@collect
//                }
                if (!premium) {
                    setControlCommandBoolean(
                        ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
                        false
                    )
                    setHeadGesturesEnabled(false)
                }
                _uiState.update { it.copy(isPremium = premium) }
            }
        }
    }

    private fun observeBroadcasts() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (!isDemoMode) when (action) {
                    AirPodsNotifications.AIRPODS_L2CAP_CONNECTED -> {
                        _uiState.update {
                            it.copy(isLocallyConnected = true)
                        }
                    }

                    AirPodsNotifications.AIRPODS_DISCONNECTED -> {
                        _uiState.update {
                            it.copy(isLocallyConnected = false)
                        }
                    }

                    AirPodsNotifications.BATTERY_DATA -> {
                        _uiState.update {
                            it.copy(battery = service.getBattery())
                        }
                    }

                    AirPodsNotifications.EQ_DATA -> {
                        val data = intent.getFloatArrayExtra("eqData") ?: floatArrayOf()

                        _uiState.update {
                            it.copy(eqData = data)
                        }
                    }

                    AirPodsNotifications.AIRPODS_INFORMATION_UPDATED -> {
                        loadInstance()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
            addAction(AirPodsNotifications.BATTERY_DATA)
            addAction(AirPodsNotifications.EQ_DATA)
            addAction(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED)
        }

        appContext.registerReceiver(
            broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED
        )
    }

    fun setControlCommandValue(
        identifier: ControlCommandIdentifiers, value: ByteArray
    ) {
        if (!isDemoMode) controlRepo.setValue(identifier, value)
        _uiState.update {
            it.copy(
                controlStates = it.controlStates + (identifier to value)
            )
        }
    }

    fun setControlCommandBoolean(
        identifier: ControlCommandIdentifiers, enabled: Boolean
    ) {
        setControlCommandValue(
            identifier, if (enabled) byteArrayOf(0x01) else byteArrayOf(0x02)
        )
    }

    fun setControlCommandInt(
        identifier: ControlCommandIdentifiers, value: Int
    ) {
        setControlCommandValue(identifier, byteArrayOf(value.toByte()))
    }

    fun setControlCommandByte(
        identifier: ControlCommandIdentifiers, value: Byte
    ) {
        setControlCommandValue(identifier, byteArrayOf(value))
    }

    fun observeControl(identifier: ControlCommandIdentifiers) {
        val listener = controlRepo.observe(identifier) { value ->
            _uiState.update { state ->
                val current = state.controlStates[identifier]
                if (current?.contentEquals(value) == true) return@update state

                state.copy(
                    controlStates = state.controlStates + (identifier to value)
                )
            }
        }

        listeners[identifier] = listener
    }

    // I'm lazy, sorry.
    fun setupControlObservers() {
        val identifiersList = listOf(
            ControlCommandIdentifiers.MIC_MODE,
            ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL,
            ControlCommandIdentifiers.CLICK_HOLD_INTERVAL,
            ControlCommandIdentifiers.LISTENING_MODE_CONFIGS,
            ControlCommandIdentifiers.ONE_BUD_ANC_MODE,
            ControlCommandIdentifiers.LISTENING_MODE,
            ControlCommandIdentifiers.AUTO_ANSWER_MODE,
            ControlCommandIdentifiers.CHIME_VOLUME,
            ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL,
            ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
            ControlCommandIdentifiers.VOLUME_SWIPE_MODE,
            ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG,
            ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
            ControlCommandIdentifiers.HEARING_AID,
            ControlCommandIdentifiers.AUTO_ANC_STRENGTH,
            ControlCommandIdentifiers.HPS_GAIN_SWIPE,
            ControlCommandIdentifiers.HEARING_ASSIST_CONFIG,
            ControlCommandIdentifiers.ALLOW_OFF_OPTION,
            ControlCommandIdentifiers.STEM_CONFIG,
            ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG,
            ControlCommandIdentifiers.ALLOW_AUTO_CONNECT,
            ControlCommandIdentifiers.EAR_DETECTION_CONFIG,
            ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG,
            ControlCommandIdentifiers.OWNS_CONNECTION,
            ControlCommandIdentifiers.PPE_TOGGLE_CONFIG,
        )
        for (identifier in identifiersList) {
            observeControl(identifier)
        }
    }

    fun refreshInitialData() {
        if (isDemoMode) return
        service.let { service ->
            _uiState.update {
                it.copy(
                    isLocallyConnected = service.isConnected(), battery = service.getBattery()
                )
            }
        }
    }

    private fun loadSharedPreferences() {
        val offListeningModeEnabled = sharedPreferences.getBoolean("off_listening_mode", true)
        val automaticEarDetectionEnabled =
            sharedPreferences.getBoolean("automatic_ear_detection", true)
        val automaticConnectionEnabled =
            sharedPreferences.getBoolean("automatic_connection_ctrl_cmd", true)
        val headGesturesEnabled = sharedPreferences.getBoolean("head_gestures", true)
        val leftAction = StemAction.valueOf(
            sharedPreferences.getString(
                "left_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        )
        val rightAction = StemAction.valueOf(
            sharedPreferences.getString(
                "right_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        )
        val vendorIdHook = xposedRemotePref.getBoolean("vendor_id_hook", false)

        _uiState.update {
            it.copy(
                offListeningMode = offListeningModeEnabled,
                automaticEarDetectionEnabled = automaticEarDetectionEnabled,
                automaticConnectionEnabled = automaticConnectionEnabled,
                headGesturesEnabled = headGesturesEnabled,
                leftAction = leftAction,
                rightAction = rightAction,
                vendorIdHook = vendorIdHook
            )
        }
    }

    fun setOffListeningMode(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("off_listening_mode", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.ALLOW_OFF_OPTION, enabled)
        _uiState.update {
            it.copy(offListeningMode = enabled)
        }
    }

    fun setHeadGesturesEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("head_gestures", enabled) }
        _uiState.update {
            it.copy(headGesturesEnabled = enabled)
        }
    }

    private fun loadControlList() {
        _uiState.update {
            it.copy(
                controlStates = controlRepo.getMap()
            )
        }
    }

    private fun loadInstance() {
        val instance = service.airpodsInstance ?: AirPodsInstance(
            name = "AirPods",
            model = AirPodsModels.getModelByModelNumber("A3049")!!,
            actualModelNumber = "A3049",
            serialNumber = null,
            leftSerialNumber = null,
            rightSerialNumber = null,
            version1 = null,
            version2 = null,
            version3 = null,
        )

        _uiState.update {
            it.copy(
                capabilities = instance.model.capabilities,
                instance = instance,
                modelName = instance.model.displayName,
                actualModel = instance.actualModelNumber,
                serialNumbers = listOf(
                    instance.serialNumber ?: "",
                    instance.leftSerialNumber ?: "",
                    instance.rightSerialNumber ?: ""
                ),
                version1 = instance.version1 ?: "",
                version2 = instance.version2 ?: "",
                version3 = instance.version3 ?: ""
            )
        }
    }

    fun reconnectFromSavedMac() {
        service.reconnectFromSavedMac()
    }

    fun setName(name: String) {
        service.setName(name)
    }

    fun startHeadTracking() {
        service.startHeadTracking()
        _uiState.update { it.copy(headTrackingActive = true) }
    }

    fun stopHeadTracking() {
        service.stopHeadTracking()
        _uiState.update { it.copy(headTrackingActive = false) }
    }

    fun setATTCharacteristicValue(handle: ATTHandles, value: ByteArray) {
        if (handle == ATTHandles.LOUD_SOUND_REDUCTION) {
            _uiState.update { it.copy(loudSoundReductionEnabled = value[0].toInt() == 0x01) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                service.attManager?.connect()
                while (service.attManager?.socket?.isConnected != true) {
                    delay(250)
                }
                service.attManager?.write(handle, value)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun refreshATT() {
        viewModelScope.launch(Dispatchers.IO) {
            val loudSoundReduction =
                runCatching { service.attManager?.read(ATTHandles.LOUD_SOUND_REDUCTION) }.getOrNull()
            val transparencyData =
                runCatching { service.attManager?.read(ATTHandles.TRANSPARENCY) }.getOrNull()?: byteArrayOf()
            val hearingAid =
                runCatching { service.attManager?.read(ATTHandles.HEARING_AID) }.getOrNull()?: byteArrayOf()
            _uiState.value = _uiState.value.copy(
                loudSoundReductionEnabled = loudSoundReduction?.get(0)?.toInt() == 0x01,
                transparencyData = transparencyData,
                hearingAidData = hearingAid
            )
        }
    }

    fun observeATT() {
        viewModelScope.launch(Dispatchers.IO) {
            service.attManager?.connect()
            while (service.attManager?.socket?.isConnected != true) {
                delay(1000)
            }
            service.attManager?.enableNotifications(ATTHandles.LOUD_SOUND_REDUCTION)
            service.attManager?.enableNotifications(ATTHandles.TRANSPARENCY)
            service.attManager?.enableNotifications(ATTHandles.HEARING_AID)

            while (true) {
                refreshATT()
                delay(15000)
            }
        }
    }

    fun setAutomaticEarDetectionEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("automatic_ear_detection", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.EAR_DETECTION_CONFIG, enabled)
        _uiState.update {
            it.copy(
                automaticEarDetectionEnabled = enabled
            )
        }
    }

    fun setAutomaticConnectionEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("automatic_connection_ctrl_cmd", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG, enabled)
        _uiState.update {
            it.copy(
                automaticConnectionEnabled = enabled
            )
        }
    }

    fun activateDemoMode() {
        isDemoMode = true
        viewModelScope.launch {
            demoActivated.emit(Unit)
        }
        val fakeInstance = AirPodsInstance(
            name = "AirPods Pro (Demo)",
            model = AirPodsModels.getModelByModelNumber("A3049")!!,
            actualModelNumber = "A3049",
            serialNumber = "DEMO123",
            leftSerialNumber = "L-DEMO",
            rightSerialNumber = "R-DEMO",
            version1 = "1.0",
            version2 = "1.0",
            version3 = "1.0",
        )

        _uiState.update {
            it.copy(
                isLocallyConnected = true,
                instance = fakeInstance,
                capabilities = fakeInstance.model.capabilities,

                battery = listOf(
                    Battery(BatteryComponent.LEFT, 85, BatteryStatus.CHARGING),
                    Battery(BatteryComponent.RIGHT, 25, BatteryStatus.NOT_CHARGING),
                    Battery(BatteryComponent.CASE, 85, BatteryStatus.CHARGING),
                ),

                modelName = fakeInstance.model.displayName,
                actualModel = fakeInstance.actualModelNumber,
                serialNumbers = listOf("DEMO", "DEMO", "DEMO"),
                version3 = "Demo Firmware",
                isPremium = true
            )
        }
    }

    fun sendPhoneMediaEQ(eq: FloatArray, phoneByte: Byte, mediaByte: Byte) {
        service.aacpManager.sendPhoneMediaEQ(eq, phoneByte, mediaByte)
    }

    fun setLongPressAction(side: String, action: StemAction) {
        val prefKey = if (side.lowercase() == "left") "left_long_press_action" else "right_long_press_action"
        sharedPreferences.edit { putString(prefKey, action.name) }
        _uiState.update {
            if (side.lowercase() == "left") it.copy(leftAction = action) else it.copy(rightAction = action)
        }
    }

    private fun countEnabledModes(byteValue: Int): Int {
        var count = 0
        if ((byteValue and 0x01) != 0) count++
        if ((byteValue and 0x02) != 0) count++
        if ((byteValue and 0x04) != 0) count++
        if ((byteValue and 0x08) != 0) count++
        return count
    }

    fun toggleListeningMode(modeBit: Int) {
        val currentByte = uiState.value.controlStates[ControlCommandIdentifiers.LISTENING_MODE_CONFIGS]?.get(0)?.toInt() ?: 0
        val newValue = if ((currentByte and modeBit) != 0) {
            val temp = currentByte and modeBit.inv()
            if (countEnabledModes(temp) >= 2) temp else currentByte
        } else {
            currentByte or modeBit
        }
        setControlCommandByte(ControlCommandIdentifiers.LISTENING_MODE_CONFIGS, newValue.toByte())
        sharedPreferences.edit { putInt("long_press_byte", newValue) }
    }

    fun disconnect() {
        service.disconnectAirPods()
        if (appContext.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(appContext, "App has disconnected, disconnect from Android Settings.",
                Toast.LENGTH_LONG).show()
        }
    }
}
