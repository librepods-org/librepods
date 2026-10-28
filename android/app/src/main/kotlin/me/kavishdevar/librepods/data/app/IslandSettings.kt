package me.kavishdevar.librepods.data.app

import kotlinx.serialization.Serializable

@Serializable
data class IslandSettings(
    val enableExpanded: Boolean = true,
    val keepCompactVisible: Boolean = false,

    val showListeningModeControl: Boolean = false,

    val expandedHeight: Float = 252f,
    val expandedWidth: Float = 1008f,
    val expandedBorderRadius: Float = 114f,
    val expandedBorderWidth: Float = 0f,
    val expandedBackgroundColor: OverlayBackgroundColor = OverlayBackgroundColor.Default(OverlayType.Island),
    val expandedBackgroundAlpha: Float = 1f,
    val expandedUseMonochrome: Boolean = false,
    val expandedOpenAppOnTap: Boolean = false,
    val expandedBatteryStyle: OverlayBatteryStyle = OverlayBatteryStyle.CIRCLE_TEXT,

    val fontSize: Float = 17f,

    val compactHeight: Float = 85f,
    val compactWidth: Float = 300f,
    val compactBorderRadius: Float = 100f,
    val compactBorderWidth: Float = 1f,
    val compactBackgroundColor: OverlayBackgroundColor = OverlayBackgroundColor.Default(OverlayType.Island),
    val compactBackgroundAlpha: Float = 1f,
    val compactUseMonochrome: Boolean = false,
    val compactOpenAppOnTap: Boolean = true,
    val compactBatteryStyle: OverlayBatteryStyle = OverlayBatteryStyle.CIRCLE,

    val offsetY: Float = 32f,
    val offsetX: Float = 0f
)
