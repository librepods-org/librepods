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

import me.kavishdevar.librepods.data.workout.HealthConnectSessionExportState
import me.kavishdevar.librepods.data.workout.HeartRateZones
import me.kavishdevar.librepods.data.workout.WorkoutDetail
import me.kavishdevar.librepods.data.workout.WorkoutSampleEntity
import me.kavishdevar.librepods.data.workout.WorkoutSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutCsvEncoderTest {
    @Test
    fun escapingDoublesQuotesAndWrapsSpecialFields() {
        assertEquals("\"a,\"\"b\"\"\n\"", WorkoutCsvEncoder.csvEscape("a,\"b\"\n"))
    }

    @Test
    fun timestampsAreIso8601UtcInstants() {
        val workout = detail(id = "session,\"quoted\"")
        val csv = WorkoutCsvEncoder.encode(workout)
        assertTrue(csv.contains("2024-01-01T00:00:00Z"))
        assertTrue(csv.contains("2024-01-01T00:00:01Z"))
        assertTrue(csv.contains("\"session,\"\"quoted\"\"\""))
    }


    @Test
    fun emptyWorkoutStillExportsOneSessionMetadataRow() {
        val workout = detail(id = "empty").copy(samples = emptyList())
        val csv = WorkoutCsvEncoder.encode(workout)
        val lines = csv.trimEnd().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("empty,2024-01-01T00:00:00Z,"))
        assertTrue(lines[1].endsWith(",190,,,"))
    }

    private fun detail(id: String): WorkoutDetail {
        val start = 1_704_067_200_000L
        val samples = listOf(
            WorkoutSampleEntity(id = 1, sessionId = id, timestampEpochMillis = start + 1_000L, sequence = 7, bpm = 123)
        )
        val summary = WorkoutSummary(
            id = id,
            startTimeEpochMillis = start,
            endTimeEpochMillis = start + 10_000L,
            maxHeartRateBpm = 190,
            sampleCount = 1,
            latestBpm = 123,
            minBpm = 123,
            avgBpm = 123.0,
            maxBpm = 123,
            healthConnectExportState = HealthConnectSessionExportState.EXPORTED,
            healthConnectExportMessage = null,
        )
        return WorkoutDetail(summary, samples, HeartRateZones.distribution(samples, 190))
    }
}
