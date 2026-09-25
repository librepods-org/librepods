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

data class HeartRateZone(
    val label: String,
    val minimumPercent: Int?,
    val maximumPercentExclusive: Int?,
    val sampleCount: Int,
)

object HeartRateZones {
    const val DEFAULT_MAX_HEART_RATE_BPM = 190
    const val MIN_CONFIGURABLE_MAX_HEART_RATE_BPM = 120
    const val MAX_CONFIGURABLE_MAX_HEART_RATE_BPM = 240

    fun normalizedMaxHeartRate(value: Int): Int = value.coerceIn(
        MIN_CONFIGURABLE_MAX_HEART_RATE_BPM,
        MAX_CONFIGURABLE_MAX_HEART_RATE_BPM,
    )

    /**
     * Deterministic five-zone model using percentage of the configured max HR.
     * Recovery=50-59%, Endurance=60-69%, Tempo=70-79%, Threshold=80-89%, Peak>=90%;
     * below 50% is separate.
     * Distribution is by recorded sample count, not inferred time between samples.
     */
    fun zoneIndex(bpm: Int, maxHeartRateBpm: Int): Int {
        val maxHr = normalizedMaxHeartRate(maxHeartRateBpm)
        val percentage = bpm.toLong() * 100L / maxHr.toLong()
        return when {
            percentage < 50L -> 0
            percentage < 60L -> 1
            percentage < 70L -> 2
            percentage < 80L -> 3
            percentage < 90L -> 4
            else -> 5
        }
    }

    fun distribution(samples: List<WorkoutSampleEntity>, maxHeartRateBpm: Int): List<HeartRateZone> {
        val counts = IntArray(6)
        samples.forEach { counts[zoneIndex(it.bpm, maxHeartRateBpm)]++ }
        return listOf(
            HeartRateZone("Below target", null, 50, counts[0]),
            HeartRateZone("Recovery", 50, 60, counts[1]),
            HeartRateZone("Endurance", 60, 70, counts[2]),
            HeartRateZone("Tempo", 70, 80, counts[3]),
            HeartRateZone("Threshold", 80, 90, counts[4]),
            HeartRateZone("Peak", 90, null, counts[5]),
        )
    }
}
