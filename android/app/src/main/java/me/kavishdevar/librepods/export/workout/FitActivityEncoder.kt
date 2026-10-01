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
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Minimal FIT Activity encoder for HR-only LibrePods sessions. */
object FitActivityEncoder {
    private const val HEADER_SIZE = 14
    private const val PROTOCOL_VERSION = 0x20 // FIT protocol 2.0
    // Matches the current public Garmin C SDK profile used to validate these definitions (21.213).
    private const val PROFILE_VERSION = 21_213
    private const val FIT_EPOCH_UNIX_SECONDS = 631065600L

    fun encode(workout: WorkoutDetail): ByteArray {
        val summary = workout.summary
        val startMillis = summary.startTimeEpochMillis
        val endMillis = requireNotNull(summary.endTimeEpochMillis) {
            "FIT export requires a finished workout"
        }
        val data = ByteArrayOutputStream()

        definition(data, 0, 0, listOf(
            Field(0, 1, BaseType.ENUM),
            Field(1, 2, BaseType.UINT16),
            Field(4, 4, BaseType.UINT32),
        ))
        dataMessage(data, 0) {
            u8(4) // file type: activity
            u16(255) // manufacturer: development
            u32(fitTimestamp(startMillis))
        }

        definition(data, 1, 20, listOf(
            Field(253, 4, BaseType.UINT32),
            Field(3, 1, BaseType.UINT8),
        ))
        val orderedSamples = workout.samples.sortedBy { it.timestampEpochMillis }
        if (orderedSamples.isEmpty()) {
            // Required Record message without fabricating a heart-rate value. 0xFF is invalid uint8.
            dataMessage(data, 1) {
                u32(fitTimestamp(startMillis))
                u8(0xFF)
            }
        } else {
            orderedSamples.forEach { sample ->
                dataMessage(data, 1) {
                    u32(fitTimestamp(sample.timestampEpochMillis))
                    u8(sample.bpm.coerceIn(0, 255))
                }
            }
        }

        val elapsedMs = (endMillis - startMillis).coerceAtLeast(0L)
        val avgHr = summary.avgBpm?.roundToInt()?.coerceIn(0, 255) ?: 0xFF
        val maxHr = summary.maxBpm?.coerceIn(0, 255) ?: 0xFF

        // Garmin Activity files require a Lap message even for an unsplit single-lap activity.
        definition(data, 2, 19, listOf(
            Field(253, 4, BaseType.UINT32),
            Field(2, 4, BaseType.UINT32),
            Field(7, 4, BaseType.UINT32),
            Field(8, 4, BaseType.UINT32),
            Field(15, 1, BaseType.UINT8),
            Field(16, 1, BaseType.UINT8),
        ))
        dataMessage(data, 2) {
            u32(fitTimestamp(endMillis))
            u32(fitTimestamp(startMillis))
            u32(elapsedMs)
            u32(elapsedMs)
            u8(avgHr)
            u8(maxHr)
        }

        definition(data, 3, 18, listOf(
            Field(253, 4, BaseType.UINT32),
            Field(2, 4, BaseType.UINT32),
            Field(7, 4, BaseType.UINT32),
            Field(8, 4, BaseType.UINT32),
            Field(5, 1, BaseType.ENUM),
            Field(16, 1, BaseType.UINT8),
            Field(17, 1, BaseType.UINT8),
        ))
        dataMessage(data, 3) {
            u32(fitTimestamp(endMillis))
            u32(fitTimestamp(startMillis))
            u32(elapsedMs)
            u32(elapsedMs)
            u8(0) // sport: generic
            u8(avgHr)
            u8(maxHr)
        }

        definition(data, 4, 34, listOf(
            Field(253, 4, BaseType.UINT32),
            Field(0, 4, BaseType.UINT32),
            Field(1, 2, BaseType.UINT16),
            Field(2, 1, BaseType.ENUM),
        ))
        dataMessage(data, 4) {
            u32(fitTimestamp(endMillis))
            u32(elapsedMs)
            u16(1)
            u8(0) // activity type: manual
        }

        val dataBytes = data.toByteArray()
        val header = ByteArrayOutputStream().apply {
            u8(HEADER_SIZE)
            u8(PROTOCOL_VERSION)
            u16(PROFILE_VERSION)
            u32(dataBytes.size.toLong())
            write(byteArrayOf('.'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte()))
        }.toByteArray()
        val fileCrc = FitCrc.compute(dataBytes)
        return ByteArrayOutputStream().apply {
            write(header)
            u16(FitCrc.compute(header))
            write(dataBytes)
            u16(fileCrc)
        }.toByteArray()
    }

    private fun fitTimestamp(unixMillis: Long): Long =
        (unixMillis / 1000L - FIT_EPOCH_UNIX_SECONDS).coerceAtLeast(0L)

    private data class Field(val number: Int, val size: Int, val baseType: Int)
    private object BaseType {
        const val ENUM = 0x00
        const val UINT8 = 0x02
        const val UINT16 = 0x84
        const val UINT32 = 0x86
    }

    private fun definition(out: ByteArrayOutputStream, local: Int, global: Int, fields: List<Field>) {
        out.u8(0x40 or (local and 0x0F))
        out.u8(0)
        out.u8(0) // little endian architecture
        out.u16(global)
        out.u8(fields.size)
        fields.forEach { field ->
            out.u8(field.number)
            out.u8(field.size)
            out.u8(field.baseType)
        }
    }

    private inline fun dataMessage(
        out: ByteArrayOutputStream,
        local: Int,
        body: ByteArrayOutputStream.() -> Unit,
    ) {
        out.u8(local and 0x0F)
        out.body()
    }

    private fun ByteArrayOutputStream.u8(value: Int) = write(value and 0xFF)
    private fun ByteArrayOutputStream.u16(value: Int) {
        u8(value)
        u8(value ushr 8)
    }
    private fun ByteArrayOutputStream.u32(value: Long) {
        u8(value.toInt())
        u8((value ushr 8).toInt())
        u8((value ushr 16).toInt())
        u8((value ushr 24).toInt())
    }
}

object FitCrc {
    private val table = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400,
        0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401,
        0x5000, 0x9C01, 0x8801, 0x4400,
    )

    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        var crc = 0
        for (index in offset until offset + length) {
            val value = bytes[index].toInt() and 0xFF
            var tmp = table[crc and 0x0F]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor tmp xor table[value and 0x0F]
            tmp = table[crc and 0x0F]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor tmp xor table[(value ushr 4) and 0x0F]
        }
        return crc and 0xFFFF
    }
}
