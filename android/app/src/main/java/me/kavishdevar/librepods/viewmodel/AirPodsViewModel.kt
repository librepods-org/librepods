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

package me.kavishdevar.librepods.viewmodel

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.constants.AirPodsNotifications
import me.kavishdevar.librepods.constants.Battery
import me.kavishdevar.librepods.constants.StemAction
import me.kavishdevar.librepods.data.ControlCommandRepository
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.utils.AACPManager
import me.kavishdevar.librepods.utils.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.utils.ATTHandles
import me.kavishdevar.librepods.utils.AirPodsInstance
import me.kavishdevar.librepods.utils.AirPodsModels
import me.kavishdevar.librepods.utils.Capability

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

    val isPremium: Boolean = false,
)

class AirPodsViewModel(
    private val service: AirPodsService,
    private val sharedPreferences: SharedPreferences,
    private val controlRepo: ControlCommandRepository,
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AirPodsUiState(deviceName = sharedPreferences.getString("name", "AirPods Pro") ?: "AirPods Pro"))
    val uiState: StateFlow<AirPodsUiState> = _uiState

    private val listeners = mutableMapOf<
        ControlCommandIdentifiers,
        AACPManager.ControlCommandListener
    >()

    private lateinit var broadcastReceiver: BroadcastReceiver

    private val _cameraAction = MutableStateFlow(
        sharedPreferences.getString("camera_action", null)
            ?.let { value -> AACPManager.Companion.StemPressType.entries.find { it.name == value } }
    )

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
        viewModelScope.launch {
            BillingManager.provider.isPremium.collect { premium ->

                if (!premium) {
                    setControlCommandBoolean(ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG, false)
                    setHeadGesturesEnabled(false)
                }

                _uiState.update { it.copy(isPremium = premium) }
            }
        }
    }

    private fun observeBroadcasts() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    AirPodsNotifications.AIRPODS_CONNECTED -> {
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
                        val data = intent.getParcelableArrayListExtra("data", Battery::class.java)?.toList() ?: emptyList()
                        _uiState.update {
                            it.copy(battery = data)
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
            broadcastReceiver,
            filter,
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    fun setControlCommandValue(
        identifier: ControlCommandIdentifiers,
        value: ByteArray
    ) {
        controlRepo.setValue(identifier, value)
        _uiState.update {
            it.copy(
                controlStates = it.controlStates + (identifier to value)
            )
        }
    }

    fun setControlCommandBoolean(
        identifier: ControlCommandIdentifiers,
        enabled: Boolean
    ) {
        setControlCommandValue(
            identifier,
            if (enabled) byteArrayOf(0x01) else byteArrayOf(0x02)
        )
    }

    fun setControlCommandInt(
        identifier: ControlCommandIdentifiers,
        value: Int
    ) {
        setControlCommandValue(identifier, byteArrayOf(value.toByte()))
    }

    fun setControlCommandByte(
        identifier: ControlCommandIdentifiers,
        value: Byte
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
        service.let { service ->
            _uiState.update {
                it.copy(
                    isLocallyConnected = service.isConnected(),
                    battery = service.getBattery()
                )
            }
        }
    }

    private fun loadSharedPreferences() {
        val offListeningModeEnabled = sharedPreferences.getBoolean("off_listening_mode", true)
        val automaticEarDetectionEnabled = sharedPreferences.getBoolean("automatic_ear_detection", true)
        val automaticConnectionEnabled = sharedPreferences.getBoolean("automatic_connection_ctrl_cmd", true)
        val headGesturesEnabled = sharedPreferences.getBoolean("head_gestures", true)
        val leftAction = StemAction.valueOf(sharedPreferences.getString("left_long_press_action", "CYCLE_NOISE_CONTROL_MODES") ?: "CYCLE_NOISE_CONTROL_MODES")
        val rightAction = StemAction.valueOf(sharedPreferences.getString("right_long_press_action", "CYCLE_NOISE_CONTROL_MODES") ?: "CYCLE_NOISE_CONTROL_MODES")

        _uiState.update {
            it.copy(
                offListeningMode = offListeningModeEnabled,
                automaticEarDetectionEnabled = automaticEarDetectionEnabled,
                automaticConnectionEnabled = automaticConnectionEnabled,
                headGesturesEnabled = headGesturesEnabled,
                leftAction = leftAction,
                rightAction = rightAction
            )
        }
    }

    fun setOffListeningMode(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("off_listening_mode", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.ALLOW_OFF_OPTION, enabled)
        Log.d("AirPodsViewModel", "Hello???? $enabled")
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

    private fun loadInstance() {
        val instance = service.airpodsInstance ?: AirPodsInstance(
            name = "AirPods",
            model = AirPodsModels.getModelByModelNumber("A3049")!!,
            actualModelNumber = "A3049",
            aacpManager = service.aacpManager,
            serialNumber = null,
            leftSerialNumber = null,
            rightSerialNumber = null,
            version1 = null,
            version2 = null,
            version3 = null,
            attManager = null
        )

        _uiState.update {
            it.copy(
                capabilities = instance.model.capabilities,
                instance = instance,
                modelName = instance.model.displayName,
                actualModel = instance.actualModelNumber,
                serialNumbers = listOf(instance.serialNumber ?: "", instance.leftSerialNumber ?: "", instance.rightSerialNumber ?: ""),
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
        service.attManager?.write(handle, value)
    }

    fun getATTCharacteristicValue(handle: ATTHandles): ByteArray? {
        return service.attManager?.read(handle)
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

    fun purchase(context: Context) {
        BillingManager.provider.purchase(context as Activity)
    }
}
