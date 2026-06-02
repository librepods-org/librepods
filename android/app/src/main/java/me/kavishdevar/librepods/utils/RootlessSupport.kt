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

fun isSupported(sharedPreferences: SharedPreferences): Boolean {
    val isPixel = Build.MANUFACTURER.lowercase() == "google"
    val isOppoFamily = Build.MANUFACTURER.lowercase() in listOf("oneplus", "oppo", "realme")
    val isBypassFlagActive = sharedPreferences.getBoolean("bypass_device_check.v2", false)

    if (isBypassFlagActive) return true

    if (isPixel) {
        when (Build.VERSION.SDK_INT) {
            36 -> {
                return Build.ID.startsWith("CP1A")
            }

            37 -> {
                return true
            }
        }
    } else if (isOppoFamily) {
        return Build.VERSION.SDK_INT >= 36
    }
    return false
}
