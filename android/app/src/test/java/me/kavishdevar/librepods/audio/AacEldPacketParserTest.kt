package me.kavishdevar.librepods.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AacEldPacketParserTest {
    @Test
    fun parsesAllCompleteAccessUnits() {
        val packet = audioHeader() + byteArrayOf(
            0x04, 0x03, 0x02, 0x01, 0x03, 0x11, 0x22, 0x33,
            0x08, 0x07, 0x06, 0x05, 0x02, 0x44, 0x55,
        )

        val frames = AacEldPacketParser.parse(packet)

        assertEquals(2, frames.size)
        assertEquals(0x01020304L, frames[0].timestamp)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), frames[0].data)
        assertEquals(0x05060708L, frames[1].timestamp)
        assertArrayEquals(byteArrayOf(0x44, 0x55), frames[1].data)
    }

    @Test
    fun dropsTruncatedTailWithoutDiscardingCompleteFrames() {
        val packet = audioHeader() + byteArrayOf(
            0x04, 0x03, 0x02, 0x01, 0x01, 0x66,
            0x08, 0x07, 0x06, 0x05, 0x03, 0x77,
        )

        val frames = AacEldPacketParser.parse(packet)

        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x66), frames.single().data)
    }

    @Test
    fun rejectsControlAndShortPackets() {
        val control = audioHeader().also { it[6] = 0x00 }

        assertFalse(AacEldPacketParser.isAudioPacket(control))
        assertTrue(AacEldPacketParser.parse(control).isEmpty())
        assertTrue(AacEldPacketParser.parse(byteArrayOf(0x04, 0x00)).isEmpty())
    }

    private fun audioHeader() = ByteArray(22).apply {
        this[0] = 0x04
        this[2] = 0x04
        this[4] = 0x58
        this[6] = 0x01
    }
}
