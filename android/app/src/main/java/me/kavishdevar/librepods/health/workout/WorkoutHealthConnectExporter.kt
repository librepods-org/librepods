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

package me.kavishdevar.librepods.health.workout

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import me.kavishdevar.librepods.data.workout.WorkoutSessionEntity
import java.time.Instant
import java.time.ZoneOffset

sealed interface WorkoutHealthConnectExportResult {
    data class Exported(val recordId: String?) : WorkoutHealthConnectExportResult
    data object PermissionRequired : WorkoutHealthConnectExportResult
    data object Unavailable : WorkoutHealthConnectExportResult
    data class Failed(val message: String) : WorkoutHealthConnectExportResult
}

interface WorkoutHealthConnectExporter {
    suspend fun export(session: WorkoutSessionEntity): WorkoutHealthConnectExportResult
}

class AndroidWorkoutHealthConnectExporter(context: Context) : WorkoutHealthConnectExporter {
    private val appContext = context.applicationContext
    private var client: HealthConnectClient? = null

    override suspend fun export(session: WorkoutSessionEntity): WorkoutHealthConnectExportResult {
        val endMillis = session.endTimeEpochMillis ?: return WorkoutHealthConnectExportResult.Failed(
            "Workout has not been finished"
        )
        return try {
            if (HealthConnectClient.getSdkStatus(appContext) != HealthConnectClient.SDK_AVAILABLE) {
                return WorkoutHealthConnectExportResult.Unavailable
            }
            val healthClient = client ?: HealthConnectClient.getOrCreate(appContext).also { client = it }
            if (WRITE_EXERCISE_PERMISSION !in healthClient.permissionController.getGrantedPermissions()) {
                return WorkoutHealthConnectExportResult.PermissionRequired
            }

            val record = ExerciseSessionRecord(
                startTime = Instant.ofEpochMilli(session.startTimeEpochMillis),
                startZoneOffset = ZoneOffset.ofTotalSeconds(session.startZoneOffsetSeconds),
                endTime = Instant.ofEpochMilli(endMillis),
                endZoneOffset = ZoneOffset.ofTotalSeconds(
                    session.endZoneOffsetSeconds ?: session.startZoneOffsetSeconds
                ),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
                title = "LibrePods workout",
                metadata = Metadata.activelyRecorded(
                    device = Device(
                        type = Device.TYPE_UNKNOWN,
                        manufacturer = "Apple",
                        model = "AirPods",
                    ),
                    clientRecordId = session.healthConnectClientRecordId,
                    clientRecordVersion = 0L,
                ),
            )
            val response = healthClient.insertRecords(listOf(record))
            WorkoutHealthConnectExportResult.Exported(response.recordIdsList.firstOrNull())
        } catch (security: SecurityException) {
            WorkoutHealthConnectExportResult.PermissionRequired
        } catch (error: Exception) {
            WorkoutHealthConnectExportResult.Failed(
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    companion object {
        val WRITE_EXERCISE_PERMISSION: String =
            HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        val REQUIRED_PERMISSIONS: Set<String> = setOf(WRITE_EXERCISE_PERMISSION)
    }
}
