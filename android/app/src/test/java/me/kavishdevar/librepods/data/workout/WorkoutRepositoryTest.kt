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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.bluetooth.HeartRateSample
import me.kavishdevar.librepods.health.workout.WorkoutHealthConnectExportResult
import me.kavishdevar.librepods.health.workout.WorkoutHealthConnectExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WorkoutRepositoryTest {
    @Test
    fun finishIsIdempotentAndDoesNotMoveEndTimeOrDuplicateHealthConnectExport() = runBlocking {
        var now = 1_000L
        val store = FakeStore()
        val exporter = FakeExporter(WorkoutHealthConnectExportResult.Exported("hc-record"))
        val repository = WorkoutRepository(
            localStore = store,
            healthConnectExporter = exporter,
            maxHeartRateProvider = { 190 },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            nowMillis = { now },
            newId = { "session-1" },
            zoneOffsetSecondsAt = { 0 },
        )

        assertEquals("session-1", repository.startWorkout())
        now = 5_000L
        repository.finishWorkout()
        now = 9_000L
        repository.finishWorkout("session-1")

        val stored = store.getSession("session-1")
        assertNotNull(stored)
        assertEquals(5_000L, stored!!.endTimeEpochMillis)
        assertEquals(HealthConnectSessionExportState.EXPORTED.name, stored.healthConnectExportState)
        assertEquals("hc-record", stored.healthConnectRecordId)
        assertEquals(1, exporter.calls)
    }

    @Test
    fun permissionFailureKeepsFinishedLocalSessionAndRetryUsesSameIdentity() = runBlocking {
        var now = 10_000L
        val store = FakeStore()
        val exporter = SequencedExporter(
            mutableListOf(
                WorkoutHealthConnectExportResult.PermissionRequired,
                WorkoutHealthConnectExportResult.Exported("hc-2"),
            )
        )
        val repository = WorkoutRepository(
            localStore = store,
            healthConnectExporter = exporter,
            maxHeartRateProvider = { 185 },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            nowMillis = { now },
            newId = { "stable-id" },
            zoneOffsetSecondsAt = { 3600 },
        )

        repository.startWorkout()
        now = 20_000L
        repository.finishWorkout()
        val afterDenied = store.getSession("stable-id")!!
        assertEquals(20_000L, afterDenied.endTimeEpochMillis)
        assertEquals(HealthConnectSessionExportState.PERMISSION_REQUIRED.name, afterDenied.healthConnectExportState)

        now = 30_000L
        repository.retryHealthConnectExport("stable-id")
        val afterRetry = store.getSession("stable-id")!!
        assertEquals(20_000L, afterRetry.endTimeEpochMillis)
        assertEquals("librepods-workout:stable-id", afterRetry.healthConnectClientRecordId)
        assertEquals(HealthConnectSessionExportState.EXPORTED.name, afterRetry.healthConnectExportState)
        assertEquals(2, exporter.calls)
    }


    @Test
    fun validatedSamplePublishedBeforeFinishIsPersistedToThatSession() = runBlocking {
        var now = 1_000L
        val store = FakeStore()
        val repository = WorkoutRepository(
            localStore = store,
            healthConnectExporter = FakeExporter(WorkoutHealthConnectExportResult.Exported("hc")),
            maxHeartRateProvider = { 190 },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            nowMillis = { now },
            newId = { "sample-session" },
            zoneOffsetSecondsAt = { 0 },
        )

        repository.startWorkout()
        repository.recordValidatedSample(
            HeartRateSample(
                bpm = 137,
                sequence = 42,
                receivedAtMillis = 1_500L,
                receivedAtElapsedRealtime = 500L,
            )
        )
        now = 2_000L
        repository.finishWorkout()

        val samples = store.getSamples("sample-session")
        assertEquals(1, samples.size)
        assertEquals(137, samples.single().bpm)
        assertEquals(42, samples.single().sequence)
    }

    private class FakeExporter(private val result: WorkoutHealthConnectExportResult) : WorkoutHealthConnectExporter {
        var calls = 0
        override suspend fun export(session: WorkoutSessionEntity): WorkoutHealthConnectExportResult {
            calls++
            return result
        }
    }

    private class SequencedExporter(
        private val results: MutableList<WorkoutHealthConnectExportResult>
    ) : WorkoutHealthConnectExporter {
        var calls = 0
        override suspend fun export(session: WorkoutSessionEntity): WorkoutHealthConnectExportResult {
            calls++
            return results.removeAt(0)
        }
    }

    private class FakeStore : WorkoutLocalStore {
        private val sessions = linkedMapOf<String, WorkoutSessionEntity>()
        private val samples = mutableListOf<WorkoutSampleEntity>()

        override fun observeActiveSummary(): Flow<WorkoutSessionSummaryRow?> = flowOf(null)
        override fun observeFinishedSummaries(): Flow<List<WorkoutSessionSummaryRow>> = flowOf(emptyList())
        override fun observeSummary(sessionId: String): Flow<WorkoutSessionSummaryRow?> = flowOf(null)
        override fun observeSamples(sessionId: String): Flow<List<WorkoutSampleEntity>> = flowOf(emptyList())
        override fun observeActiveSamples(): Flow<List<WorkoutSampleEntity>> = flowOf(emptyList())
        override suspend fun getActiveSession(): WorkoutSessionEntity? = sessions.values.lastOrNull { it.endTimeEpochMillis == null }
        override suspend fun getSession(sessionId: String): WorkoutSessionEntity? = sessions[sessionId]
        override suspend fun getPendingHealthConnectSessions(): List<WorkoutSessionEntity> =
            sessions.values.filter { it.healthConnectExportState == HealthConnectSessionExportState.PENDING.name }
        override suspend fun getSamples(sessionId: String): List<WorkoutSampleEntity> = samples.filter { it.sessionId == sessionId }
        override suspend fun createSession(session: WorkoutSessionEntity) { sessions[session.id] = session }
        override suspend fun addSample(sample: WorkoutSampleEntity) { samples += sample }

        override suspend fun finishLocally(sessionId: String, endMillis: Long, endOffsetSeconds: Int): WorkoutSessionEntity? {
            val current = sessions[sessionId] ?: return null
            if (current.endTimeEpochMillis == null) {
                sessions[sessionId] = current.copy(
                    endTimeEpochMillis = endMillis,
                    endZoneOffsetSeconds = endOffsetSeconds,
                    healthConnectExportState = HealthConnectSessionExportState.PENDING.name,
                )
            }
            return sessions[sessionId]
        }

        override suspend fun updateHealthConnectExport(
            sessionId: String,
            state: HealthConnectSessionExportState,
            recordId: String?,
            message: String?,
        ) {
            val current = sessions[sessionId] ?: return
            sessions[sessionId] = current.copy(
                healthConnectExportState = state.name,
                healthConnectRecordId = recordId,
                healthConnectExportMessage = message,
            )
        }
    }
}
