package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class HeartRateMeasurementEncoderTest {
    @Test
    fun bpmAtOrBelow255UsesUint8Format() {
        assertArrayEquals(
            byteArrayOf(0x00, 72),
            HeartRateMeasurementEncoder.encodeBpm(72)
        )
        assertArrayEquals(
            byteArrayOf(0x00, 0xff.toByte()),
            HeartRateMeasurementEncoder.encodeBpm(255)
        )
    }

    @Test
    fun bpmAbove255UsesUint16LittleEndianFormat() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x2c, 0x01),
            HeartRateMeasurementEncoder.encodeBpm(300)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun bpmOutsideUint16IsRejected() {
        HeartRateMeasurementEncoder.encodeBpm(65_536)
    }
}
