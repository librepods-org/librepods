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

import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.kavishdevar.librepods.bluetooth.HeartRateSample
import me.kavishdevar.librepods.health.workout.WorkoutHealthConnectExportResult
import me.kavishdevar.librepods.health.workout.WorkoutHealthConnectExporter
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

interface WorkoutLocalStore {
    fun observeActiveSummary(): Flow<WorkoutSessionSummaryRow?>
    fun observeFinishedSummaries(): Flow<List<WorkoutSessionSummaryRow>>
    fun observeSummary(sessionId: String): Flow<WorkoutSessionSummaryRow?>
    fun observeSamples(sessionId: String): Flow<List<WorkoutSampleEntity>>
    fun observeActiveSamples(): Flow<List<WorkoutSampleEntity>>
    suspend fun getActiveSession(): WorkoutSessionEntity?
    suspend fun getSession(sessionId: String): WorkoutSessionEntity?
    suspend fun getPendingHealthConnectSessions(): List<WorkoutSessionEntity>
    suspend fun getSamples(sessionId: String): List<WorkoutSampleEntity>
    suspend fun createSession(session: WorkoutSessionEntity)
    suspend fun addSample(sample: WorkoutSampleEntity)
    suspend fun deleteSession(sessionId: String): Boolean = false
    suspend fun finishLocally(sessionId: String, endMillis: Long, endOffsetSeconds: Int): WorkoutSessionEntity?
    suspend fun updateHealthConnectExport(
        sessionId: String,
        state: HealthConnectSessionExportState,
        recordId: String?,
        message: String?,
    )
}

class RoomWorkoutLocalStore(private val database: WorkoutDatabase) : WorkoutLocalStore {
    private val dao = database.workoutDao()

    override fun observeActiveSummary() = dao.observeActiveSummary()
    override fun observeFinishedSummaries() = dao.observeFinishedSummaries()
    override fun observeSummary(sessionId: String) = dao.observeSummary(sessionId)
    override fun observeSamples(sessionId: String) = dao.observeSamples(sessionId)
    override fun observeActiveSamples() = dao.observeActiveSamples()
    override suspend fun getActiveSession() = dao.getActiveSession()
    override suspend fun getSession(sessionId: String) = dao.getSession(sessionId)
    override suspend fun getPendingHealthConnectSessions() = dao.getPendingHealthConnectSessions()
    override suspend fun getSamples(sessionId: String) = dao.getSamples(sessionId)
    override suspend fun createSession(session: WorkoutSessionEntity) = dao.insertSession(session)
    override suspend fun addSample(sample: WorkoutSampleEntity) { dao.insertSample(sample) }
    override suspend fun deleteSession(sessionId: String): Boolean = dao.deleteSession(sessionId) > 0

    override suspend fun finishLocally(
        sessionId: String,
        endMillis: Long,
        endOffsetSeconds: Int,
    ): WorkoutSessionEntity? = database.withTransaction {
        val current = dao.getSession(sessionId) ?: return@withTransaction null
        if (current.endTimeEpochMillis == null) {
            dao.closeSessionIfActive(
                sessionId,
                endMillis,
                endOffsetSeconds,
                HealthConnectSessionExportState.PENDING.name,
            )
        }
        dao.getSession(sessionId)
    }

    override suspend fun updateHealthConnectExport(
        sessionId: String,
        state: HealthConnectSessionExportState,
        recordId: String?,
        message: String?,
    ) = dao.updateHealthConnectExport(sessionId, state.name, recordId, message)
}

data class WorkoutSummary(
    val id: String,
    val startTimeEpochMillis: Long,
    val endTimeEpochMillis: Long?,
    val maxHeartRateBpm: Int,
    val sampleCount: Long,
    val latestBpm: Int?,
    val minBpm: Int?,
    val avgBpm: Double?,
    val maxBpm: Int?,
    val healthConnectExportState: HealthConnectSessionExportState,
    val healthConnectExportMessage: String?,
)

data class WorkoutDetail(
    val summary: WorkoutSummary,
    val samples: List<WorkoutSampleEntity>,
    val zones: List<HeartRateZone>,
)

sealed interface FinishWorkoutResult {
    data object NoActiveWorkout : FinishWorkoutResult
    data class Finished(val sessionId: String, val healthConnectState: HealthConnectSessionExportState) : FinishWorkoutResult
}

class WorkoutRepository(
    private val localStore: WorkoutLocalStore,
    private val healthConnectExporter: WorkoutHealthConnectExporter,
    private val maxHeartRateProvider: () -> Int,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val zoneOffsetSecondsAt: (Long) -> Int = { millis ->
        val instant = Instant.ofEpochMilli(millis)
        ZoneId.systemDefault().rules.getOffset(instant).totalSeconds
    },
) {
    private val localMutationMutex = Mutex()
    private val healthExportMutex = Mutex()

    val activeWorkout: Flow<WorkoutDetail?> = combine(
        localStore.observeActiveSummary(),
        localStore.observeActiveSamples(),
    ) { row, samples -> row?.let { detail(it, samples) } }

    val history: Flow<List<WorkoutSummary>> = localStore.observeFinishedSummaries().map { rows ->
        rows.map(::summary)
    }

    fun workout(sessionId: String): Flow<WorkoutDetail?> = combine(
        localStore.observeSummary(sessionId),
        localStore.observeSamples(sessionId),
    ) { row, samples -> row?.let { detail(it, samples) } }

    suspend fun startWorkout(): String = localMutationMutex.withLock {
        localStore.getActiveSession()?.id ?: run {
            val start = nowMillis()
            val id = newId()
            localStore.createSession(
                WorkoutSessionEntity(
                    id = id,
                    startTimeEpochMillis = start,
                    startZoneOffsetSeconds = zoneOffsetSecondsAt(start),
                    maxHeartRateBpm = maxHeartRateProvider(),
                    healthConnectClientRecordId = "librepods-workout:$id",
                )
            )
            id
        }
    }

    /** Called only from HeartRateMonitor's already-validated/published stream. */
    fun recordValidatedSample(sample: HeartRateSample) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            localMutationMutex.withLock {
                val session = localStore.getActiveSession() ?: return@withLock
                localStore.addSample(
                    WorkoutSampleEntity(
                        sessionId = session.id,
                        timestampEpochMillis = sample.receivedAtMillis,
                        sequence = sample.sequence,
                        bpm = sample.bpm,
                    )
                )
            }
        }
    }

    suspend fun finishWorkout(sessionId: String? = null): FinishWorkoutResult {
        val finished = localMutationMutex.withLock {
            val id = sessionId ?: localStore.getActiveSession()?.id
                ?: return FinishWorkoutResult.NoActiveWorkout
            val end = nowMillis()
            localStore.finishLocally(id, end, zoneOffsetSecondsAt(end))
                ?: return FinishWorkoutResult.NoActiveWorkout
        }
        val state = exportFinishedSession(finished)
        return FinishWorkoutResult.Finished(finished.id, state)
    }

    suspend fun deleteWorkout(sessionId: String): Boolean = localMutationMutex.withLock {
        localStore.deleteSession(sessionId)
    }

    suspend fun retryHealthConnectExport(sessionId: String): HealthConnectSessionExportState {
        val session = localStore.getSession(sessionId) ?: return HealthConnectSessionExportState.ERROR
        if (session.endTimeEpochMillis == null) return HealthConnectSessionExportState.NOT_FINISHED
        return exportFinishedSession(session)
    }

    /** Resumes only commits that were interrupted after the local finish transaction. */
    fun retryPendingHealthConnectExports() {
        scope.launch {
            localStore.getPendingHealthConnectSessions().forEach { session ->
                exportFinishedSession(session)
            }
        }
    }

    suspend fun snapshot(sessionId: String): WorkoutDetail? {
        val session = localStore.getSession(sessionId) ?: return null
        val samples = localStore.getSamples(sessionId)
        val row = WorkoutSessionSummaryRow(
            id = session.id,
            startTimeEpochMillis = session.startTimeEpochMillis,
            endTimeEpochMillis = session.endTimeEpochMillis,
            maxHeartRateBpm = session.maxHeartRateBpm,
            healthConnectExportState = session.healthConnectExportState,
            healthConnectExportMessage = session.healthConnectExportMessage,
            sampleCount = samples.size.toLong(),
            latestBpm = samples.maxByOrNull { it.timestampEpochMillis }?.bpm,
            minBpm = samples.minOfOrNull { it.bpm },
            avgBpm = samples.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average(),
            maxBpm = samples.maxOfOrNull { it.bpm },
        )
        return detail(row, samples)
    }

    private suspend fun exportFinishedSession(session: WorkoutSessionEntity): HealthConnectSessionExportState =
        healthExportMutex.withLock {
            val latest = localStore.getSession(session.id) ?: session
            if (latest.healthConnectExportState == HealthConnectSessionExportState.EXPORTED.name) {
                return@withLock HealthConnectSessionExportState.EXPORTED
            }
            val result = healthConnectExporter.export(latest)
            val (state, recordId, message) = when (result) {
                is WorkoutHealthConnectExportResult.Exported -> Triple(
                    HealthConnectSessionExportState.EXPORTED,
                    result.recordId ?: latest.healthConnectRecordId,
                    null,
                )
                WorkoutHealthConnectExportResult.PermissionRequired -> Triple(
                    HealthConnectSessionExportState.PERMISSION_REQUIRED,
                    latest.healthConnectRecordId,
                    "Exercise write permission is required",
                )
                WorkoutHealthConnectExportResult.Unavailable -> Triple(
                    HealthConnectSessionExportState.UNAVAILABLE,
                    latest.healthConnectRecordId,
                    "Health Connect is unavailable",
                )
                is WorkoutHealthConnectExportResult.Failed -> Triple(
                    HealthConnectSessionExportState.ERROR,
                    latest.healthConnectRecordId,
                    result.message,
                )
            }
            localStore.updateHealthConnectExport(latest.id, state, recordId, message)
            state
        }

    private fun summary(row: WorkoutSessionSummaryRow) = WorkoutSummary(
        id = row.id,
        startTimeEpochMillis = row.startTimeEpochMillis,
        endTimeEpochMillis = row.endTimeEpochMillis,
        maxHeartRateBpm = row.maxHeartRateBpm,
        sampleCount = row.sampleCount,
        latestBpm = row.latestBpm,
        minBpm = row.minBpm,
        avgBpm = row.avgBpm,
        maxBpm = row.maxBpm,
        healthConnectExportState = runCatching {
            HealthConnectSessionExportState.valueOf(row.healthConnectExportState)
        }.getOrDefault(HealthConnectSessionExportState.ERROR),
        healthConnectExportMessage = row.healthConnectExportMessage,
    )

    private fun detail(row: WorkoutSessionSummaryRow, samples: List<WorkoutSampleEntity>): WorkoutDetail =
        WorkoutDetail(
            summary = summary(row),
            samples = samples,
            zones = HeartRateZones.distribution(samples, row.maxHeartRateBpm),
        )
}
