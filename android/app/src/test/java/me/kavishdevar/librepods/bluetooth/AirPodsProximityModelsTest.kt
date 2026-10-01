/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPodsProximityModelsTest {
    private fun frame(id: Int): ByteArray = ByteArray(27).apply {
        this[0] = 0x07
        this[1] = 0x19
        this[2] = 0x01
        this[3] = (id ushr 8).toByte()
        this[4] = id.toByte()
    }

    @Test
    fun recognizesBothAirPods5Identifiers() {
        for (id in listOf(0x3020, 0x3620)) {
            assertEquals("AirPods 5", AirPodsProximityModels.getModelName(frame(id)))
        }
    }

    @Test
    fun recognizesSanitizedAirPods5Captures() {
        // Packet facts from AirPodsDesktop commit 71fbb5e. Private trailing bytes
        // remain zeroed; this checks identification, not decryption or AACP control.
        for (status in listOf(0x35, 0x55)) {
            val data = byteArrayOf(
                0x07, 0x19, 0x01, 0x30, 0x20, status.toByte(),
                0xAA.toByte(), 0xBA.toByte(), 0x32, 0x00, 0x05
            ) + ByteArray(16)
            assertEquals(27, data.size)
            assertTrue(AirPodsProximityModels.isValid(data))
            assertEquals("AirPods 5", AirPodsProximityModels.getModelName(data))
        }
    }

    @Test
    fun preservesExistingModelMappings() {
        val existing = mapOf(
            0x0E20 to "AirPods Pro", 0x1420 to "AirPods Pro 2",
            0x2420 to "AirPods Pro 2 (USB-C)", 0x0220 to "AirPods 1",
            0x0F20 to "AirPods 2", 0x1320 to "AirPods 3",
            0x1920 to "AirPods 4", 0x1B20 to "AirPods 4 (ANC)",
            0x0A20 to "AirPods Max", 0x1F20 to "AirPods Max (USB-C)"
        )
        for ((id, name) in existing) {
            assertEquals(name, AirPodsProximityModels.getModelName(frame(id)))
        }
    }

    @Test
    fun keepsUnknownIdentifiersWithoutSignedByteCorruption() {
        assertEquals("Unknown (65312)", AirPodsProximityModels.getModelName(frame(0xFF20)))
    }

    @Test
    fun doesNotMistakeReversedWireBytesForAirPods5() {
        for (id in listOf(0x2030, 0x2036)) {
            assertNotEquals("AirPods 5", AirPodsProximityModels.getModelName(frame(id)))
        }
    }

    @Test
    fun rejectsEveryTruncatedMessageLength() {
        for (length in 0 until 27) {
            val data = frame(0x3020).copyOf(length)
            assertFalse(AirPodsProximityModels.isValid(data))
            assertThrows(IllegalArgumentException::class.java) {
                AirPodsProximityModels.getModelName(data)
            }
        }
    }

    @Test
    fun rejectsIncorrectTypeOrPayloadLength() {
        for (index in listOf(0, 1)) {
            val data = frame(0x3020).apply { this[index] = 0 }
            assertFalse(AirPodsProximityModels.isValid(data))
            assertThrows(IllegalArgumentException::class.java) {
                AirPodsProximityModels.getModelName(data)
            }
        }
    }
}
