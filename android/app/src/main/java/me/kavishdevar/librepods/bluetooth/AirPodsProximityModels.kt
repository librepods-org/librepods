/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.kavishdevar.librepods.bluetooth

/** Model identification for Apple's 0x07 proximity manufacturer data (company ID excluded). */
internal object AirPodsProximityModels {
    private val modelNames = mapOf(
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro 2",
        0x2420 to "AirPods Pro 2 (USB-C)",
        0x0220 to "AirPods 1",
        0x0F20 to "AirPods 2",
        0x1320 to "AirPods 3",
        0x1920 to "AirPods 4",
        0x1B20 to "AirPods 4 (ANC)",
        0x0A20 to "AirPods Max",
        0x1F20 to "AirPods Max (USB-C)",
        // Preserve LibrePods' existing byte-order convention: wire bytes 30 20 / 36 20.
        // AirPodsDesktop names the same IDs 0x2030 / 0x2036 (little-endian):
        // https://github.com/SpriteOvO/AirPodsDesktop/commit/71fbb5e961991dd614e2c58bbadfb918b9441e92
        // The reference maps both to the family, not to individual case/capability variants.
        0x3020 to "AirPods 5",
        0x3620 to "AirPods 5"
    )

    fun isValid(data: ByteArray): Boolean =
        data.size >= 27 && data[0] == 0x07.toByte() && data[1] == 0x19.toByte()

    fun getModelName(data: ByteArray): String {
        require(isValid(data)) { "Expected a complete AirPods proximity message" }
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        // Do not reject new models merely because their ID is not in this table.
        return modelNames[modelId] ?: "Unknown ($modelId)"
    }
}
