package me.kavishdevar.librepods.core.bluetooth.aacp

/**
 * Reassembles SensorDataWX RTBuddy records that span socket reads, so a long descriptor
 * announcement reaches the protobuf parser whole. Oversized or malformed lengths are skipped.
 */
internal class SensorFrames {
    private var carry = byteArrayOf()

    @Synchronized
    fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.size > MAX_RECORD || carry.size + chunk.size > 2 * MAX_RECORD) {
            carry = byteArrayOf()
            return emptyList()
        }
        val input = carry + chunk
        val frames = mutableListOf<ByteArray>()
        var cursor = 0
        while (input.size - cursor >= PREFIX.size) {
            if (!PREFIX.indices.all { input[cursor + it] == PREFIX[it] }) { cursor++; continue }
            if (input.size - cursor < HEADER) break
            val length = (input[cursor + 10].toInt() and 255) or ((input[cursor + 11].toInt() and 255) shl 8)
            if (length > MAX_RECORD) { cursor += PREFIX.size; continue }
            val end = cursor + HEADER + length
            if (end > input.size) break
            if (input.copyOfRange(cursor + 6, cursor + 10).contentEquals(SENSOR_DATA_WX)) {
                frames += input.copyOfRange(cursor, end)
            }
            cursor = end
        }
        carry = input.copyOfRange(cursor, input.size)
        return frames
    }

    @Synchronized
    fun reset() { carry = byteArrayOf() }

    private companion object {
        const val MAX_RECORD = 16_384
        const val HEADER = 12
        val PREFIX = byteArrayOf(4, 0, 4, 0, 0x17, 0)
        val SENSOR_DATA_WX = byteArrayOf(0, 0, 0x10, 0) // RTBuddyDescriptor.SENSOR_DATA_WX, little endian
    }
}
