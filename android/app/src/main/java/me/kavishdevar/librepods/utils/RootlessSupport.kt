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

package me.kavishdevar.librepods.utils

import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit

internal const val ANDROID_16_SDK = 36
internal const val ANDROID_17_SDK = 37

private const val BYPASS_DEVICE_CHECK_KEY = "bypass_device_check.v2"
internal const val GOOGLE_MANUFACTURER = "google"
internal const val ONEPLUS_MANUFACTURER = "oneplus"
internal const val OPPO_MANUFACTURER = "oppo"
internal const val REALME_MANUFACTURER = "realme"
internal const val XIAOMI_MANUFACTURER = "xiaomi"
internal const val POCO_MANUFACTURER = "poco"
internal const val REDMI_MANUFACTURER = "redmi"
private const val PIXEL_ANDROID_16_SUPPORTED_BUILD_PREFIX = "CP1A"

private val OPPO_FAMILY_MANUFACTURERS = setOf(
    ONEPLUS_MANUFACTURER,
    OPPO_MANUFACTURER,
    REALME_MANUFACTURER,
)
private val XIAOMI_FAMILY_MANUFACTURERS = setOf(
    XIAOMI_MANUFACTURER,
    POCO_MANUFACTURER,
    REDMI_MANUFACTURER,
)

fun isSupported(sharedPreferences: SharedPreferences): Boolean {
    val isBypassFlagActive = sharedPreferences.getBoolean(BYPASS_DEVICE_CHECK_KEY, false)

    return isRootlessSupported(
        sdkInt = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER,
        buildId = Build.ID,
        isBypassFlagActive = isBypassFlagActive,
    )
}

fun isRootlessSupported(
    sdkInt: Int,
    manufacturer: String,
    buildId: String,
    isBypassFlagActive: Boolean,
): Boolean {
    if (sdkInt >= ANDROID_17_SDK) return true
    if (isBypassFlagActive) return true

    val normalizedManufacturer = manufacturer.lowercase()
    val isPixel = normalizedManufacturer == GOOGLE_MANUFACTURER
    val isOppoFamily = normalizedManufacturer in OPPO_FAMILY_MANUFACTURERS
    val isXiaomiFamily = normalizedManufacturer in XIAOMI_FAMILY_MANUFACTURERS

    if (isPixel && sdkInt == ANDROID_16_SDK) {
        return buildId.startsWith(PIXEL_ANDROID_16_SUPPORTED_BUILD_PREFIX)
    } else if (isOppoFamily || isXiaomiFamily) {
        return sdkInt >= ANDROID_16_SDK
    }

    return false
}

fun bypassDeviceCheck(sharedPreferences: SharedPreferences) {
    sharedPreferences.edit{ putBoolean(BYPASS_DEVICE_CHECK_KEY, true) }
}

fun removeDeviceCheckBypass(sharedPreferences: SharedPreferences) {
    sharedPreferences.edit{ putBoolean("bypass_device_check.v2", false) }
}
