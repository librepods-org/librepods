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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Aggregate row used directly by UI-facing repository Flows. */
data class WorkoutSessionSummaryRow(
    val id: String,
    val startTimeEpochMillis: Long,
    val endTimeEpochMillis: Long?,
    val maxHeartRateBpm: Int,
    val healthConnectExportState: String,
    val healthConnectExportMessage: String?,
    val sampleCount: Long,
    val latestBpm: Int?,
    val minBpm: Int?,
    val avgBpm: Double?,
    val maxBpm: Int?,
)

@Dao
interface WorkoutDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: WorkoutSessionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSample(sample: WorkoutSampleEntity): Long

    @Query("SELECT * FROM workout_sessions WHERE endTimeEpochMillis IS NULL ORDER BY startTimeEpochMillis DESC LIMIT 1")
    suspend fun getActiveSession(): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): WorkoutSessionEntity?

    @Query("DELETE FROM workout_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String): Int

    @Query("SELECT * FROM workout_sessions WHERE endTimeEpochMillis IS NOT NULL AND healthConnectExportState = 'PENDING' ORDER BY startTimeEpochMillis")
    suspend fun getPendingHealthConnectSessions(): List<WorkoutSessionEntity>

    @Query("SELECT * FROM workout_samples WHERE sessionId = :sessionId ORDER BY timestampEpochMillis, id")
    suspend fun getSamples(sessionId: String): List<WorkoutSampleEntity>

    @Query("SELECT * FROM workout_samples WHERE sessionId = :sessionId ORDER BY timestampEpochMillis, id")
    fun observeSamples(sessionId: String): Flow<List<WorkoutSampleEntity>>

    @Query(
        """
        SELECT hr.* FROM workout_samples hr
        INNER JOIN workout_sessions s ON s.id = hr.sessionId
        WHERE s.endTimeEpochMillis IS NULL
        ORDER BY hr.timestampEpochMillis, hr.id
        """
    )
    fun observeActiveSamples(): Flow<List<WorkoutSampleEntity>>

    @Query(
        """
        UPDATE workout_sessions
        SET endTimeEpochMillis = :endTimeEpochMillis,
            endZoneOffsetSeconds = :endZoneOffsetSeconds,
            healthConnectExportState = :exportState,
            healthConnectExportMessage = NULL
        WHERE id = :sessionId AND endTimeEpochMillis IS NULL
        """
    )
    suspend fun closeSessionIfActive(
        sessionId: String,
        endTimeEpochMillis: Long,
        endZoneOffsetSeconds: Int,
        exportState: String,
    ): Int

    @Query(
        """
        UPDATE workout_sessions
        SET healthConnectExportState = :exportState,
            healthConnectRecordId = :recordId,
            healthConnectExportMessage = :message
        WHERE id = :sessionId
        """
    )
    suspend fun updateHealthConnectExport(
        sessionId: String,
        exportState: String,
        recordId: String?,
        message: String?,
    )

    @Query(ACTIVE_SUMMARY_QUERY)
    fun observeActiveSummary(): Flow<WorkoutSessionSummaryRow?>

    @Query(FINISHED_SUMMARIES_QUERY)
    fun observeFinishedSummaries(): Flow<List<WorkoutSessionSummaryRow>>

    @Query(SESSION_SUMMARY_QUERY)
    fun observeSummary(sessionId: String): Flow<WorkoutSessionSummaryRow?>

    companion object {
        private const val SUMMARY_COLUMNS = """
            s.id AS id,
            s.startTimeEpochMillis AS startTimeEpochMillis,
            s.endTimeEpochMillis AS endTimeEpochMillis,
            s.maxHeartRateBpm AS maxHeartRateBpm,
            s.healthConnectExportState AS healthConnectExportState,
            s.healthConnectExportMessage AS healthConnectExportMessage,
            COUNT(hr.id) AS sampleCount,
            (SELECT latest.bpm FROM workout_samples latest
                WHERE latest.sessionId = s.id
                ORDER BY latest.timestampEpochMillis DESC, latest.id DESC LIMIT 1) AS latestBpm,
            MIN(hr.bpm) AS minBpm,
            AVG(hr.bpm) AS avgBpm,
            MAX(hr.bpm) AS maxBpm
        """

        const val ACTIVE_SUMMARY_QUERY = """
            SELECT $SUMMARY_COLUMNS
            FROM workout_sessions s
            LEFT JOIN workout_samples hr ON hr.sessionId = s.id
            WHERE s.endTimeEpochMillis IS NULL
            GROUP BY s.id
            ORDER BY s.startTimeEpochMillis DESC
            LIMIT 1
        """

        const val FINISHED_SUMMARIES_QUERY = """
            SELECT $SUMMARY_COLUMNS
            FROM workout_sessions s
            LEFT JOIN workout_samples hr ON hr.sessionId = s.id
            WHERE s.endTimeEpochMillis IS NOT NULL
            GROUP BY s.id
            ORDER BY s.startTimeEpochMillis DESC
        """

        const val SESSION_SUMMARY_QUERY = """
            SELECT $SUMMARY_COLUMNS
            FROM workout_sessions s
            LEFT JOIN workout_samples hr ON hr.sessionId = s.id
            WHERE s.id = :sessionId
            GROUP BY s.id
            LIMIT 1
        """
    }
}
