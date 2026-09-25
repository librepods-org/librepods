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

class FitActivityEncoderTest {
    @Test
    fun headerAndDataCrcAreSelfConsistent() {
        val bytes = FitActivityEncoder.encode(detail())
        assertEquals(14, bytes[0].toInt() and 0xFF)
        assertEquals(".FIT", String(bytes, 8, 4, Charsets.US_ASCII))

        val dataSize = le32(bytes, 4).toInt()
        assertEquals(bytes.size - 14 - 2, dataSize)

        val storedHeaderCrc = le16(bytes, 12)
        assertEquals(FitCrc.compute(bytes, 0, 12), storedHeaderCrc)
        assertEquals(0, FitCrc.compute(bytes, 0, 14))

        val storedFileCrc = le16(bytes, 14 + dataSize)
        assertEquals(FitCrc.compute(bytes, 14, dataSize), storedFileCrc)
        assertEquals(FitCrc.compute(bytes, 0, 14 + dataSize), storedFileCrc)
        assertEquals(setOf(0, 18, 19, 20, 34), globalMessageDefinitions(bytes, dataSize))
        assertTrue(dataSize > 0)
    }

    private fun globalMessageDefinitions(bytes: ByteArray, dataSize: Int): Set<Int> {
        val globals = linkedSetOf<Int>()
        val localSizes = IntArray(16)
        var offset = 14
        val dataEnd = 14 + dataSize
        while (offset < dataEnd) {
            val header = bytes[offset++].toInt() and 0xFF
            val local = header and 0x0F
            if ((header and 0x40) != 0) {
                offset++ // reserved
                val architecture = bytes[offset++].toInt() and 0xFF
                require(architecture == 0)
                val global = le16(bytes, offset)
                offset += 2
                globals += global
                val fieldCount = bytes[offset++].toInt() and 0xFF
                var messageSize = 0
                repeat(fieldCount) {
                    offset++ // field number
                    messageSize += bytes[offset++].toInt() and 0xFF
                    offset++ // base type
                }
                localSizes[local] = messageSize
            } else {
                offset += localSizes[local]
            }
        }
        return globals
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun detail(): WorkoutDetail {
        val start = 1_704_067_200_000L
        val samples = listOf(
            WorkoutSampleEntity(id = 1, sessionId = "fit", timestampEpochMillis = start + 1_000, sequence = 1, bpm = 120),
            WorkoutSampleEntity(id = 2, sessionId = "fit", timestampEpochMillis = start + 2_000, sequence = 2, bpm = 128),
        )
        val summary = WorkoutSummary(
            id = "fit",
            startTimeEpochMillis = start,
            endTimeEpochMillis = start + 60_000,
            maxHeartRateBpm = 190,
            sampleCount = samples.size.toLong(),
            latestBpm = 128,
            minBpm = 120,
            avgBpm = 124.0,
            maxBpm = 128,
            healthConnectExportState = HealthConnectSessionExportState.EXPORTED,
            healthConnectExportMessage = null,
        )
        return WorkoutDetail(summary, samples, HeartRateZones.distribution(samples, 190))
    }
}
