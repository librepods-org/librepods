/* SPDX-License-Identifier: GPL-3.0-or-later; exact layouts from NTPods/Tahoe captures. */
package me.kavishdevar.librepods.core.bluetooth.aacp

internal object SensorDescriptorLayout {
    enum class Kind { MOTION, HEART_RATE }
    private const val MAX_PAYLOAD = 16_384
    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    private fun unsigned(value: Byte) = value.toInt() and 255
    internal val MOTION_DESCRIPTOR = hex("06 0c ff 09 11 a1 01 06 00 ff 09 23 a1 00 85 01 06 15 ff 09 04 95 01 75 40 81 02 06 0c ff 09 0d 76 b0 05 81 02 85 02 09 0e 81 02 c0 85 01 05 20 0a 0e 03 75 20 b1 02 85 02 0a 1b 03 b1 02 06 15 ff 0a 01 00 85 03 b1 02 85 04 05 20 0a 1a 03 b1 02 85 ff 06 15 ff 0a 20 00 75 08 95 e0 b1 02 c0")
    internal val HEART_DESCRIPTOR = hex("05 20 09 16 a1 01 85 01 0a 0e 03 14 27 ff ff ff 7f 75 20 95 01 b1 02 0a b8 04 26 ff 00 75 08 95 01 81 02 06 15 ff 0a 20 01 95 01 75 08 81 02 26 ff 7f 06 15 ff 0a 21 01 95 01 75 10 81 02 75 08 95 01 15 01 25 02 1a 04 01 2a 05 01 81 00 06 00 ff 09 23 a1 00 15 00 24 06 15 ff 09 04 95 08 75 08 81 02 06 0a ff 09 12 95 04 75 08 81 02 85 02 06 0a ff 09 13 96 58 02 75 08 81 02 c0 c0")
    fun parse(data: ByteArray): Kind? = try {
        require(data.size <= MAX_PAYLOAD && data.size >= 8 && unsigned(data[0]) == 0xd3 &&
            data.sliceArray(1..3).all { it == 0.toByte() })
        var offset = 4
        fun take(size: Int): ByteArray {
            require(size >= 0 && size <= data.size - offset)
            val result = data.copyOfRange(offset, offset + size)
            offset = (offset + size + 3) and -4
            require(offset <= data.size)
            return result
        }
        fun word(): Int = take(4).let { bytes ->
            unsigned(bytes[0]) or (unsigned(bytes[1]) shl 8) or (unsigned(bytes[2]) shl 16) or (unsigned(bytes[3]) shl 24)
        }
        fun item(): Pair<Int, ByteArray> {
            val header = word()
            val kind = (header ushr 24) and 0x7f
            val count = header and 0xffffff
            val length = when {
                kind == 4 && count <= 64 -> 8
                kind in 8..10 -> count
                kind == 11 && count <= 1 -> 0
                else -> error("Unsupported descriptor item")
            }
            return kind to take(length)
        }
        val header = word()
        val count = header and 0xffffff
        require(header and 0x7f000000 == 0x01000000 && count <= 96)
        val values = mutableMapOf<String, Pair<Int, ByteArray>>()
        repeat(count) {
            val (kind, bytes) = item()
            require(kind in 8..9 && bytes.size <= 128)
            val key = bytes.toString(Charsets.US_ASCII).trimEnd('\u0000')
            require(!values.containsKey(key))
            values[key] = item()
        }
        require(offset == data.size)
        fun number(key: String): Long? = values[key]?.takeIf { it.first == 4 && it.second.size == 8 }?.second?.let { bytes ->
            var value = 0L
            for (index in bytes.indices) value = value or (unsigned(bytes[index]).toLong() shl (index * 8))
            value
        }
        require(number("VendorID") == 1452L)
        val names = listOfNotNull(values["AccessoryService"], values["HeartRateService"])
        require(names.size == 1 && names[0].first in 8..9)
        val name = names[0].second.toString(Charsets.US_ASCII).trimEnd('\u0000')
        val descriptor = values["ReportDescriptor"]?.takeIf { it.first == 10 }?.second
        when {
            name == "devmotion6" && number("MaxReportSize") == 191L && descriptor.contentEquals(MOTION_DESCRIPTOR) -> Kind.MOTION
            name == "HeartRate" && number("MaxReportSize") == 601L && descriptor.contentEquals(HEART_DESCRIPTOR) -> Kind.HEART_RATE
            else -> null
        }
    } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }
}
