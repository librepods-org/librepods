/*
 * LibrePods - AirPods liberated from Apple’s ecosystem
 *
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package me.kavishdevar.librepods.constants

import android.os.Parcelable
import android.util.Log
import kotlinx.parcelize.Parcelize

enum class Enums(val value: ByteArray) {
    NOISE_CANCELLATION(Capabilities.NOISE_CANCELLATION),
    PREFIX(byteArrayOf(0x04, 0x00, 0x04, 0x00)),
    SETTINGS(byteArrayOf(0x09, 0x00)),
    NOISE_CANCELLATION_PREFIX(PREFIX.value + SETTINGS.value + NOISE_CANCELLATION.value),
    CONVERSATION_AWARENESS_RECEIVE_PREFIX(PREFIX.value + byteArrayOf(0x4b, 0x00, 0x02, 0x00)),
}

object BatteryComponent {
    const val LEFT = 4
    const val RIGHT = 2
    const val CASE = 8
}

object BatteryStatus {
    const val CHARGING = 1
    const val NOT_CHARGING = 2
    const val DISCONNECTED = 4
}

@Parcelize
data class Battery(val component: Int, val level: Int, val status: Int) : Parcelable {
    fun getComponentName(): String? {
        return when (component) {
            BatteryComponent.LEFT -> "LEFT"
            BatteryComponent.RIGHT -> "RIGHT"
            BatteryComponent.CASE -> "CASE"
            else -> null
        }
    }

    fun getStatusName(): String? {
        return when (status) {
            BatteryStatus.CHARGING -> "CHARGING"
            BatteryStatus.NOT_CHARGING -> "NOT_CHARGING"
            BatteryStatus.DISCONNECTED -> "DISCONNECTED"
            else -> null
        }
    }
}

enum class NoiseControlMode {
    OFF,  NOISE_CANCELLATION, TRANSPARENCY, ADAPTIVE
}

class AirPodsNotifications {
    companion object {
        const val AIRPODS_CONNECTED = "me.kavishdevar.librepods.AIRPODS_CONNECTED"
        const val AIRPODS_DATA = "me.kavishdevar.librepods.AIRPODS_DATA"
        const val EAR_DETECTION_DATA = "me.kavishdevar.librepods.EAR_DETECTION_DATA"
        const val ANC_DATA = "me.kavishdevar.librepods.ANC_DATA"
        const val BATTERY_DATA = "me.kavishdevar.librepods.BATTERY_DATA"
        const val CA_DATA = "me.kavishdevar.librepods.CA_DATA"
        const val AIRPODS_DISCONNECTED = "me.kavishdevar.librepods.AIRPODS_DISCONNECTED"
        const val AIRPODS_CONNECTION_DETECTED = "me.kavishdevar.librepods.AIRPODS_CONNECTION_DETECTED"
        const val DISCONNECT_RECEIVERS = "me.kavishdevar.librepods.DISCONNECT_RECEIVERS"
    }

    class EarDetection {
        private val notificationBit = Capabilities.EAR_DETECTION
        private val notificationPrefix = Enums.PREFIX.value + notificationBit

        var status: List<Byte> = listOf(0x01, 0x01)

        fun setStatus(data: ByteArray) {
            status = listOf(data[6], data[7])
        }

        fun isEarDetectionData(data: ByteArray): Boolean {
            if (data.size != 8) {
                return false
            }
            val prefixHex = notificationPrefix.joinToString("") { "%02x".format(it) }
            val dataHex = data.joinToString("") { "%02x".format(it) }
            return dataHex.startsWith(prefixHex)
        }
    }

    class ANC {
        private val notificationPrefix = Enums.NOISE_CANCELLATION_PREFIX.value

        var status: Int = 1
            private set

        fun isANCData(data: ByteArray): Boolean {
            if (data.size != 11) {
                return false
            }
            val prefixHex = notificationPrefix.joinToString("") { "%02x".format(it) }
            val dataHex = data.joinToString("") { "%02x".format(it) }
            return dataHex.startsWith(prefixHex)
        }

        fun setStatus(data: ByteArray) {
            when (data.size) {
                // if the whole packet is given
                11 -> {
                    status = data[7].toInt()
                }
                // if only the data is given
                1 -> {
                    status = data[0].toInt()
                }
                // if the value of control command is given
                4 -> {
                    status = data[0].toInt()
                }
                else -> {
                    Log.d("ANC", "Invalid ANC data size: ${data.size}")
                }
            }
        }

        val name: String =
            when (status) {
                1 -> "OFF"
                2 -> "ON"
                3 -> "TRANSPARENCY"
                4 -> "ADAPTIVE"
                else -> "UNKNOWN"
            }

    }

    class BatteryNotification {
        private val batteries = mutableMapOf<Int, Battery>()
        private val componentOrder = listOf(
            BatteryComponent.LEFT,
            BatteryComponent.RIGHT,
            BatteryComponent.CASE,
        )
        private val validStatuses = setOf(
            BatteryStatus.CHARGING,
            BatteryStatus.NOT_CHARGING,
            BatteryStatus.DISCONNECTED,
        )

        fun isBatteryData(data: ByteArray): Boolean = parseBatteryRecords(data) != null

        fun setBatteryDirect(
            leftLevel: Int?,
            leftCharging: Boolean,
            rightLevel: Int?,
            rightCharging: Boolean,
            caseLevel: Int?,
            caseCharging: Boolean
        ) {
            mergeDirectBattery(BatteryComponent.LEFT, leftLevel, leftCharging)
            mergeDirectBattery(BatteryComponent.RIGHT, rightLevel, rightCharging)
            mergeDirectBattery(BatteryComponent.CASE, caseLevel, caseCharging)
        }

        private fun mergeDirectBattery(component: Int, level: Int?, charging: Boolean) {
            if (level == null || level !in 0..100) return
            batteries[component] = Battery(
                component,
                level,
                if (charging) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING,
            )
        }

        private fun parseBatteryRecords(data: ByteArray): List<Battery>? {
            if (data.size < BATTERY_HEADER_SIZE ||
                BATTERY_PREFIX.indices.any { data[it] != BATTERY_PREFIX[it] }
            ) {
                return null
            }

            val count = data[BATTERY_COUNT_INDEX].toInt() and 0xFF
            if (count > componentOrder.size || data.size != BATTERY_HEADER_SIZE + count * BATTERY_RECORD_SIZE) {
                return null
            }

            val records = ArrayList<Battery>(count)
            repeat(count) { index ->
                val offset = BATTERY_HEADER_SIZE + index * BATTERY_RECORD_SIZE
                if (data[offset + 1] != BATTERY_RECORD_MARKER ||
                    data[offset + 4] != BATTERY_RECORD_MARKER
                ) {
                    return null
                }
                records += Battery(
                    component = data[offset].toInt() and 0xFF,
                    level = data[offset + 2].toInt() and 0xFF,
                    status = data[offset + 3].toInt() and 0xFF,
                )
            }
            return records
        }

        fun setBattery(data: ByteArray) {
            val observations = parseBatteryRecords(data) ?: return
            observations.forEach { observation ->
                if (observation.component !in componentOrder || observation.status !in validStatuses) {
                    return@forEach
                }
                if (observation.level > 100 ||
                    (observation.level == 0 && observation.status == BatteryStatus.DISCONNECTED)
                ) {
                    return@forEach
                }
                batteries[observation.component] = observation
            }
        }

        fun clear() {
            batteries.clear()
        }

        fun getBattery(): List<Battery> = componentOrder.mapNotNull(batteries::get)

        private companion object {
            val BATTERY_PREFIX = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x04, 0x00)
            const val BATTERY_COUNT_INDEX = 6
            const val BATTERY_HEADER_SIZE = 7
            const val BATTERY_RECORD_SIZE = 5
            const val BATTERY_RECORD_MARKER: Byte = 0x01
        }
    }

    class ConversationalAwarenessNotification {
        @Suppress("PrivatePropertyName")
        private val NOTIFICATION_PREFIX = Enums.CONVERSATION_AWARENESS_RECEIVE_PREFIX.value

        var status: Byte = 0
            private set

        fun isConversationalAwarenessData(data: ByteArray): Boolean {
            if (data.size != 10) {
                return false
            }
            val prefixHex = NOTIFICATION_PREFIX.joinToString("") { "%02x".format(it) }
            val dataHex = data.joinToString("") { "%02x".format(it) }
            return dataHex.startsWith(prefixHex)
        }

        fun setData(data: ByteArray) {
            status = data[9]
        }
    }
}

class Capabilities {
    companion object {
        val NOISE_CANCELLATION = byteArrayOf(0x0d)
        val EAR_DETECTION = byteArrayOf(0x06)
    }
}

fun isHeadTrackingData(data: ByteArray): Boolean {
    if (data.size <= 60) return false

    val prefixPattern = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x17, 0x00, 0x00, 0x00,
        0x10, 0x00
    )

    for (i in prefixPattern.indices) {
        if (data[i] != prefixPattern[i]) return false
    }

    if (data[10] != 0x44.toByte() && data[10] != 0x45.toByte()) return false

    if (data[11] != 0x00.toByte()) return false

    return true
}
