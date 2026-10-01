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

package me.kavishdevar.librepods.data.workout

import android.content.SharedPreferences
import androidx.core.content.edit

class WorkoutPreferences(private val sharedPreferences: SharedPreferences) {
    var maxHeartRateBpm: Int
        get() = HeartRateZones.normalizedMaxHeartRate(
            sharedPreferences.getInt(KEY_MAX_HEART_RATE, HeartRateZones.DEFAULT_MAX_HEART_RATE_BPM)
        )
        set(value) {
            sharedPreferences.edit {
                putInt(KEY_MAX_HEART_RATE, HeartRateZones.normalizedMaxHeartRate(value))
            }
        }

    companion object {
        private const val KEY_MAX_HEART_RATE = "workout_max_heart_rate_bpm"
    }
}
