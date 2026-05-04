package me.kavishdevar.librepods.presentation.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import kotlin.math.roundToInt

data class AppSettingsUiState(
    val showPhoneBatteryInWidget: Boolean = false,
    val conversationalAwarenessPauseMusicEnabled: Boolean = false,
    val relativeConversationalAwarenessVolumeEnabled: Boolean = true,
    val disconnectWhenNotWearing: Boolean = false,
    val takeoverWhenDisconnected: Boolean = false,
    val takeoverWhenIdle: Boolean = false,
    val takeoverWhenMusic: Boolean = false,
    val takeoverWhenCall: Boolean = false,
    val takeoverWhenRingingCall: Boolean = false,
    val takeoverWhenMediaStart: Boolean = false,
    val useAlternateHeadTrackingPackets: Boolean = true,
    val conversationalAwarenessVolume: Float = 43f,
    val showCameraDialog: Boolean = false,
    val cameraPackageValue: String = "",
    val cameraPackageError: String? = null,
    val vendorIdHook: Boolean = false,
    val isPremium: Boolean = false,
    val connectionSuccessful: Boolean = false
)

class AppSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPreferences = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(AppSettingsUiState())
    val uiState = _uiState.asStateFlow()

    private val xposedRemotePref = XposedRemotePrefProvider.create()

    val sharedPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPref, key ->
        if (key == "connection_successful") {
            _uiState.update { it.copy(connectionSuccessful = sharedPref.getBoolean(key, false)) }
        }
    }


    init {
        loadSettings()
        observeBilling()
        sharedPreferences.registerOnSharedPreferenceChangeListener(sharedPrefListener)
    }

    override fun onCleared() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(sharedPrefListener)
        super.onCleared()
    }

    private fun observeBilling() {
        viewModelScope.launch {
            BillingManager.provider.isPremium.collect { premium ->
                _uiState.update { it.copy(isPremium = premium) }
            }
        }
    }

    private fun loadSettings() {
        _uiState.update { currentState ->
            currentState.copy(
                showPhoneBatteryInWidget = sharedPreferences.getBoolean("show_phone_battery_in_widget", false),
                conversationalAwarenessPauseMusicEnabled = sharedPreferences.getBoolean("conversational_awareness_pause_music", false),
                relativeConversationalAwarenessVolumeEnabled = sharedPreferences.getBoolean("relative_conversational_awareness_volume", true),
                disconnectWhenNotWearing = sharedPreferences.getBoolean("disconnect_when_not_wearing", false),
                takeoverWhenDisconnected = sharedPreferences.getBoolean("takeover_when_disconnected", false),
                takeoverWhenIdle = sharedPreferences.getBoolean("takeover_when_idle", false),
                takeoverWhenMusic = sharedPreferences.getBoolean("takeover_when_music", false),
                takeoverWhenCall = sharedPreferences.getBoolean("takeover_when_call", false),
                takeoverWhenRingingCall = sharedPreferences.getBoolean("takeover_when_ringing_call", false),
                takeoverWhenMediaStart = sharedPreferences.getBoolean("takeover_when_media_start", false),
                useAlternateHeadTrackingPackets = sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true),
                conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", 43).toFloat(),
                cameraPackageValue = sharedPreferences.getString("custom_camera_package", "") ?: "",
                vendorIdHook = xposedRemotePref.getBoolean("vendor_id_hook", false),
                connectionSuccessful = sharedPreferences.getBoolean("connection_successful", false)
            )
        }
    }

    fun setShowPhoneBatteryInWidget(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("show_phone_battery_in_widget", enabled) }
        _uiState.update { it.copy(showPhoneBatteryInWidget = enabled) }
    }

    fun setConversationalAwarenessPauseMusicEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("conversational_awareness_pause_music", enabled) }
        _uiState.update { it.copy(conversationalAwarenessPauseMusicEnabled = enabled) }
    }

    fun setRelativeConversationalAwarenessVolumeEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("relative_conversational_awareness_volume", enabled) }
        _uiState.update { it.copy(relativeConversationalAwarenessVolumeEnabled = enabled) }
    }

    fun setDisconnectWhenNotWearing(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("disconnect_when_not_wearing", enabled) }
        _uiState.update { it.copy(disconnectWhenNotWearing = enabled) }
    }

    fun setTakeoverWhenDisconnected(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("takeover_when_disconnected", enabled) }
        _uiState.update { it.copy(takeoverWhenDisconnected = enabled) }
    }

    fun setTakeoverWhenIdle(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("takeover_when_idle", enabled) }
        _uiState.update { it.copy(takeoverWhenIdle = enabled) }
    }

    fun setTakeoverWhenMusic(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("takeover_when_music", enabled) }
        _uiState.update { it.copy(takeoverWhenMusic = enabled) }
    }

    fun setTakeoverWhenCall(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("takeover_when_call", enabled) }
        _uiState.update { it.copy(takeoverWhenCall = enabled) }
    }

    fun setTakeoverWhenRingingCall(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("takeover_when_ringing_call", enabled) }
        _uiState.update { it.copy(takeoverWhenRingingCall = enabled) }
    }

    fun setTakeoverWhenMediaStart(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("takeover_when_media_start", enabled) }
        _uiState.update { it.copy(takeoverWhenMediaStart = enabled) }
    }

    fun setUseAlternateHeadTrackingPackets(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("use_alternate_head_tracking_packets", enabled) }
        _uiState.update { it.copy(useAlternateHeadTrackingPackets = enabled) }
    }

    fun setConversationalAwarenessVolume(volume: Float) {
        sharedPreferences.edit { putInt("conversational_awareness_volume", volume.roundToInt()) }
        _uiState.update { it.copy(conversationalAwarenessVolume = volume) }
    }

    fun setShowCameraDialog(show: Boolean) {
        _uiState.update { it.copy(showCameraDialog = show) }
    }

    fun setCameraPackageValue(value: String) {
        _uiState.update { it.copy(cameraPackageValue = value) }
    }

    fun setCameraPackageError(error: String?) {
        _uiState.update { it.copy(cameraPackageError = error) }
    }

    fun saveCameraPackage() {
        if (_uiState.value.cameraPackageValue.isBlank()) {
            sharedPreferences.edit { remove("custom_camera_package") }
        } else {
            sharedPreferences.edit { putString("custom_camera_package", _uiState.value.cameraPackageValue) }
        }
        setShowCameraDialog(false)
    }

    fun setVendorIdHook(enabled: Boolean) {
        xposedRemotePref.putBoolean("vendor_id_hook", enabled)
        _uiState.update { it.copy(vendorIdHook = enabled) }
    }
}
