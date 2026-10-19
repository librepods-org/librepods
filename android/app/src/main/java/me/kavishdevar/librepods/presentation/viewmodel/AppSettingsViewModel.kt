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
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.ToggleListeningModeActivity
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import kotlin.math.roundToInt

data class AppSettingsUiState(
    val showPhoneBatteryInWidget: Boolean = false,
    val conversationAwarenessPauseMusicEnabled: Boolean = false,
    val relativeConversationAwarenessVolumeEnabled: Boolean = true,
    val disconnectWhenNotWearing: Boolean = false,
    val takeoverWhenDisconnected: Boolean = false,
    val takeoverWhenIdle: Boolean = false,
    val takeoverWhenMusic: Boolean = false,
    val takeoverWhenCall: Boolean = false,
    val takeoverWhenRingingCall: Boolean = false,
    val takeoverWhenMediaStart: Boolean = false,
    val useAlternateHeadTrackingPackets: Boolean = true,
    val conversationAwarenessVolume: Float = 43f,
    val showCameraDialog: Boolean = false,
    val cameraPackageValue: String = "",
    val cameraPackageError: String? = null,
    val vendorIdHook: Boolean = false,
    val isPremium: Boolean = false,
    val connectionSuccessful: Boolean = false,
    val showBottomSheetPopup: Boolean = true,
    val showIslandPopup: Boolean = true,
    val timeUntilFOSSPremiumExpiry: Long = 0L,
    val m3eEnabled: Boolean = false,
    val toggleListeningModes: List<Int> = emptyList()
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
    }

    private fun observeBilling() {
        viewModelScope.launch {
            BillingManager.provider.isPremium.collect { premium ->
                if (premium) {
                    sharedPreferences.edit {
                        remove("premium_expiry_time")
                        if (BuildConfig.PLAY_BUILD) remove("foss_upgraded")
                    }
                    _uiState.update { it.copy(isPremium = true, timeUntilFOSSPremiumExpiry = 0L) }
                } else {
                    // No billing premium, only update if no temporary premium is active
                    if (_uiState.value.timeUntilFOSSPremiumExpiry <= 0L) {
                        _uiState.update { it.copy(isPremium = false) }
                    }
                }
            }
        }
    }

    private fun loadSettings() {
        // faulty update on Play caused PLAY_BUILD to be false and resulted in use of FOSS billing in Play. since FOSS is not verified, we need to give 2 weeks to verify the purchase

        val fossUpgraded = sharedPreferences.getBoolean("foss_upgraded", false)
        val expiryTime = sharedPreferences.getLong("premium_expiry_time", 0L)
        val now = System.currentTimeMillis()

        when {
            // existing temporary premium
            expiryTime > 0L -> {
                if (expiryTime <= now) {
                    sharedPreferences.edit {
                        remove("premium_expiry_time")
                        remove("foss_upgraded")
                    }

                    _uiState.update {
                        it.copy(
                            timeUntilFOSSPremiumExpiry = 0L,
                            isPremium = false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            timeUntilFOSSPremiumExpiry = expiryTime - now,
                            isPremium = true
                        )
                    }
                }
            }

            // First migration from accidental FOSS Play build
            fossUpgraded && !_uiState.value.isPremium && BuildConfig.PLAY_BUILD -> {
                val newExpiry = now + 28L * 24 * 60 * 60 * 1000

                sharedPreferences.edit {
                    putLong("premium_expiry_time", newExpiry)
                }

                _uiState.update {
                    it.copy(
                        timeUntilFOSSPremiumExpiry = newExpiry - now,
                        isPremium = true
                    )
                }
            }
        }

        _uiState.update { currentState ->
            currentState.copy(
                showPhoneBatteryInWidget = sharedPreferences.getBoolean("show_phone_battery_in_widget", false),
                conversationAwarenessPauseMusicEnabled = sharedPreferences.getBoolean("conversation_awareness_pause_music", false),
                relativeConversationAwarenessVolumeEnabled = sharedPreferences.getBoolean("relative_conversation_awareness_volume", true),
                disconnectWhenNotWearing = sharedPreferences.getBoolean("disconnect_when_not_wearing", false),
                takeoverWhenDisconnected = sharedPreferences.getBoolean("takeover_when_disconnected", false),
                takeoverWhenIdle = sharedPreferences.getBoolean("takeover_when_idle", false),
                takeoverWhenMusic = sharedPreferences.getBoolean("takeover_when_music", false),
                takeoverWhenCall = sharedPreferences.getBoolean("takeover_when_call", false),
                takeoverWhenRingingCall = sharedPreferences.getBoolean("takeover_when_ringing_call", false),
                takeoverWhenMediaStart = sharedPreferences.getBoolean("takeover_when_media_start", false),
                useAlternateHeadTrackingPackets = sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true),
                conversationAwarenessVolume = sharedPreferences.getInt("conversation_awareness_volume", 43).toFloat(),
                cameraPackageValue = sharedPreferences.getString("custom_camera_package", "") ?: "",
                vendorIdHook = xposedRemotePref.getBoolean("vendor_id_hook", false),
                connectionSuccessful = sharedPreferences.getBoolean("connection_successful", false),
                showBottomSheetPopup = sharedPreferences.getBoolean("show_bottom_sheet_popup", true),
                showIslandPopup = sharedPreferences.getBoolean("show_island_popup", true),
                m3eEnabled = sharedPreferences.getBoolean("m3e_enabled", true),
                toggleListeningModes = ToggleListeningModeActivity.getModes(sharedPreferences)
            )
        }
    }

    fun setShowPhoneBatteryInWidget(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("show_phone_battery_in_widget", enabled) }
        _uiState.update { it.copy(showPhoneBatteryInWidget = enabled) }
    }

    fun setConversationAwarenessPauseMusicEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("conversation_awareness_pause_music", enabled) }
        _uiState.update { it.copy(conversationAwarenessPauseMusicEnabled = enabled) }
    }

    fun setRelativeConversationAwarenessVolumeEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("relative_conversation_awareness_volume", enabled) }
        _uiState.update { it.copy(relativeConversationAwarenessVolumeEnabled = enabled) }
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

    fun selectToggleListeningMode(mode: Int) {
        val modes = _uiState.value.toggleListeningModes
        if (mode in modes) return
        // Keep exactly two modes: the newly picked one replaces the least recently picked.
        val newModes = listOf(modes.last(), mode)
        sharedPreferences.edit {
            putString(ToggleListeningModeActivity.PREF_KEY, newModes.joinToString(","))
        }
        _uiState.update { it.copy(toggleListeningModes = newModes) }
    }

    fun setUseAlternateHeadTrackingPackets(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("use_alternate_head_tracking_packets", enabled) }
        _uiState.update { it.copy(useAlternateHeadTrackingPackets = enabled) }
    }

    fun setConversationAwarenessVolume(volume: Float) {
        sharedPreferences.edit { putInt("conversation_awareness_volume", volume.roundToInt()) }
        _uiState.update { it.copy(conversationAwarenessVolume = volume) }
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

    fun setShowBottomSheetPopup(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("show_bottom_sheet_popup", enabled) }
        _uiState.update { it.copy(showBottomSheetPopup = enabled) }
    }

    fun setShowIslandPopup(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("show_island_popup", enabled) }
        _uiState.update { it.copy(showIslandPopup = enabled) }
    }

    fun setm3eEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("m3e_enabled", enabled) }
        _uiState.update { it.copy(m3eEnabled = enabled) }
    }
}
