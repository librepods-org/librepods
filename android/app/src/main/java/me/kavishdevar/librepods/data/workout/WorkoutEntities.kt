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

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workout_sessions",
    indices = [
        Index(value = ["startTimeEpochMillis"]),
        Index(value = ["endTimeEpochMillis"]),
    ],
)
data class WorkoutSessionEntity(
    @PrimaryKey val id: String,
    val startTimeEpochMillis: Long,
    val startZoneOffsetSeconds: Int,
    val endTimeEpochMillis: Long? = null,
    val endZoneOffsetSeconds: Int? = null,
    val maxHeartRateBpm: Int,
    val healthConnectClientRecordId: String,
    val healthConnectRecordId: String? = null,
    val healthConnectExportState: String = HealthConnectSessionExportState.NOT_FINISHED.name,
    val healthConnectExportMessage: String? = null,
)

@Entity(
    tableName = "workout_samples",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["sessionId", "timestampEpochMillis"]),
        Index(value = ["sessionId", "timestampEpochMillis", "sequence"], unique = true),
    ],
)
data class WorkoutSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String,
    val timestampEpochMillis: Long,
    val sequence: Int,
    val bpm: Int,
)

enum class HealthConnectSessionExportState {
    NOT_FINISHED,
    PENDING,
    EXPORTED,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
    ERROR,
}
