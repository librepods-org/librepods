package me.kavishdevar.librepods.bluetooth

/** Encodes the Bluetooth SIG Heart Rate Measurement characteristic (0x2A37). */
object HeartRateMeasurementEncoder {
    fun encodeBpm(bpm: Int): ByteArray {
        require(bpm in 0..0xFFFF) { "Heart rate must fit an unsigned 16-bit value" }
        return if (bpm <= 0xFF) {
            byteArrayOf(0x00, bpm.toByte())
        } else {
            byteArrayOf(
                0x01, // Flags: Heart Rate Value Format = UINT16; all optional fields absent.
                (bpm and 0xFF).toByte(),
                ((bpm ushr 8) and 0xFF).toByte()
            )
        }
    }
}
