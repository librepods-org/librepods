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

package me.kavishdevar.librepods.export.workout

import me.kavishdevar.librepods.data.workout.WorkoutDetail
import java.time.Instant

object WorkoutCsvEncoder {
    fun encode(workout: WorkoutDetail): String = buildString {
        appendLine("session_id,session_start,session_end,max_hr_bpm,sample_timestamp,bpm,sequence")
        val summary = workout.summary
        val start = Instant.ofEpochMilli(summary.startTimeEpochMillis).toString()
        val end = summary.endTimeEpochMillis?.let { Instant.ofEpochMilli(it).toString() }.orEmpty()
        val sessionPrefix = buildString {
            append(csvEscape(summary.id)); append(',')
            append(csvEscape(start)); append(',')
            append(csvEscape(end)); append(',')
            append(summary.maxHeartRateBpm)
        }
        if (workout.samples.isEmpty()) {
            append(sessionPrefix); append(",,,\n")
        } else {
            workout.samples.forEach { sample ->
                append(sessionPrefix); append(',')
                append(csvEscape(Instant.ofEpochMilli(sample.timestampEpochMillis).toString())); append(',')
                append(sample.bpm); append(',')
                append(sample.sequence); append('\n')
            }
        }
    }

    internal fun csvEscape(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\r' || it == '\n' }) return value
        return buildString(value.length + 2) {
            append('"')
            value.forEach { char ->
                if (char == '"') append("\"\"") else append(char)
            }
            append('"')
        }
    }
}
