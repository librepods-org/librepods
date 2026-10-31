package me.kavishdevar.librepods.core.devices

import androidx.annotation.IntRange
import kotlinx.serialization.Serializable
import me.kavishdevar.librepods.core.data.StemAction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class AppleSettings(
    val disconnectWhenNotWearing: Boolean = true, // disconnect_when_not_wearing

    val earDetectionEnabled: Boolean = true,

    val cacheDisconnectedComponentBattery: Boolean = true,

    val postureAlertEnabled: Boolean = true,
    val postureAlertThreshold: Int = 20,

    val headGesturesEnabled: Boolean = true, // head_gestures_enabled
    val headTrackingInterval: Duration = 40.milliseconds,

    val leftLongPressAction: StemAction = StemAction.CYCLE_listening_mode_MODES, // left_long_press_action
    val rightLongPressAction: StemAction = StemAction.CYCLE_listening_mode_MODES, // right_long_press_action

    val showIslandPopup: Boolean = true, // show_island_popup
    val showBottomSheetPopup: Boolean = true, // show_bottom_sheet_popup

    val alwaysConnectWhenAccessoryDisconnected: Boolean = true,

    val connectOnPhoneWhenAccessoryDisconnected: Boolean = true,
    val connectOnPhoneWhenAccessoryIdle: Boolean = true,
    val connectOnPhoneWhenAccessoryMusic: Boolean = true,

    val connectOnMediaWhenAccessoryDisconnected: Boolean = true,
    val connectOnMediaWhenAccessoryIdle: Boolean = true,
    val connectOnMediaWhenAccessoryMusic: Boolean = false,

    val conversationAwarenessPauseMusicEnabled: Boolean = false, // conversation_awareness_pause_music
    val relativeConversationAwarenessVolumeEnabled: Boolean = true, // relative_conversation_awareness_volume

    val conversationAwarenessVolume: Float = 43f,
    val conversationAwarenessReducedVolume: Float = 20f,

    val hrmAlertEnabled: Boolean = false,
    @IntRange(from = 120, to = 200) val hrmAlertThreshold: Int = 150,
): DeviceSettings
