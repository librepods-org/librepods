/*
 * LibrePods - AirPods liberated from Apple's ecosystem
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
@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.utils

import android.util.Log
import me.kavishdevar.librepods.utils.AACPManager.Companion.ControlCommandIdentifiers.entries
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Manager class for Apple Accessory Communication Protocol (AACP)
 * This class is responsible for handling the L2CAP socket management,
 * constructing and parsing packets for communication with Apple accessories.
 */
class AACPManager {
    companion object {
        private const val TAG = "AACPManager"

        object Opcodes {
            const val SET_FEATURE_FLAGS: Byte = 0x4d
            const val REQUEST_NOTIFICATIONS: Byte = 0x0f
            const val BATTERY_INFO: Byte = 0x04
            const val CONTROL_COMMAND: Byte = 0x09
            const val EAR_DETECTION: Byte = 0x06
            const val CONVERSATION_AWARENESS: Byte = 0x4b
            const val DEVICE_METADATA: Byte = 0x1d
            const val RENAME: Byte = 0x1E
            const val HEADTRACKING: Byte = 0x17
            const val PROXIMITY_KEYS_REQ: Byte = 0x30
            const val PROXIMITY_KEYS_RSP: Byte = 0x31
        }

        private val HEADER_BYTES = byteArrayOf(0x04, 0x00, 0x04, 0x00)

        data class ControlCommandStatus(
            val identifier: ControlCommandIdentifiers,
            val value: ByteArray
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as ControlCommandStatus

                if (identifier != other.identifier) return false
                if (!value.contentEquals(other.value)) return false

                return true
            }

            override fun hashCode(): Int {
                var result: Int = identifier.hashCode()
                result = 31 * result + value.contentHashCode()
                return result
            }
        }

//        @Suppress("unused")
        enum class ControlCommandIdentifiers(val value: Byte) {
            MIC_MODE(0x01),
            BUTTON_SEND_MODE(0x05),
            VOICE_TRIGGER(0x12),
            SINGLE_CLICK_MODE(0x14),
            DOUBLE_CLICK_MODE(0x15),
            CLICK_HOLD_MODE(0x16),
            DOUBLE_CLICK_INTERVAL(0x17),
            CLICK_HOLD_INTERVAL(0x18),
            LISTENING_MODE_CONFIGS(0x1A),
            ONE_BUD_ANC_MODE(0x1B),
            CROWN_ROTATION_DIRECTION(0x1C),
            LISTENING_MODE(0x0D),
            AUTO_ANSWER_MODE(0x1E),
            CHIME_VOLUME(0x1F),
            VOLUME_SWIPE_INTERVAL(0x23),
            CALL_MANAGEMENT_CONFIG(0x24),
            VOLUME_SWIPE_MODE(0x25),
            ADAPTIVE_VOLUME_CONFIG(0x26),
            SOFTWARE_MUTE_CONFIG(0x27),
            CONVERSATION_DETECT_CONFIG(0x28),
            SSL(0x29),
            HEARING_AID(0x2C),
            AUTO_ANC_STRENGTH(0x2E),
            HPS_GAIN_SWIPE(0x2F),
            HRM_STATE(0x30),
            IN_CASE_TONE_CONFIG(0x31),
            SIRI_MULTITONE_CONFIG(0x32),
            HEARING_ASSIST_CONFIG(0x33),
            ALLOW_OFF_OPTION(0x34);

            companion object {
                fun fromByte(byte: Byte): ControlCommandIdentifiers? =
                    entries.find { it.value == byte }
            }
        }

        enum class ProximityKeyType(val value: Byte) {
            IRK(0x01),
            ENC_KEY(0x04);

            companion object {
                fun fromByte(byte: Byte): ProximityKeyType =
                    ProximityKeyType.entries.find { it.value == byte }?: throw IllegalArgumentException("Unknown ProximityKeyType: $byte")
            }
        }
    }
    var controlCommandStatusList: MutableList<ControlCommandStatus> = mutableListOf<ControlCommandStatus>()
    var controlCommandListeners: MutableMap<ControlCommandIdentifiers, MutableList<ControlCommandListener>> = mutableMapOf()

    fun getControlCommandStatus(identifier: ControlCommandIdentifiers): ControlCommandStatus? {
        return controlCommandStatusList.find { it.identifier == identifier }
    }

    private fun setControlCommandStatusValue(identifier: ControlCommandIdentifiers, value: ByteArray) {
        val existingStatus = getControlCommandStatus(identifier)
        if (existingStatus == value) {
            controlCommandStatusList.remove(existingStatus)
        }
        if (existingStatus != null) {
            controlCommandStatusList.remove(existingStatus)
        }
        controlCommandListeners[identifier]?.forEach { listener ->
            listener.onControlCommandReceived(ControlCommand(identifier.value, value))
        }
        controlCommandStatusList.add(ControlCommandStatus(identifier, value))
    }

    interface PacketCallback {
        fun onBatteryInfoReceived(batteryInfo: ByteArray)
        fun onEarDetectionReceived(earDetection: ByteArray)
        fun onConversationAwarenessReceived(conversationAwareness: ByteArray)
        fun onControlCommandReceived(controlCommand: ByteArray)
        fun onDeviceMetadataReceived(deviceMetadata: ByteArray)
        fun onHeadTrackingReceived(headTracking: ByteArray)
        fun onUnknownPacketReceived(packet: ByteArray)
        fun onProximityKeysReceived(proximityKeys: ByteArray)
    }

    interface ControlCommandListener {
        fun onControlCommandReceived(controlCommand: ControlCommand)
    }

    fun registerControlCommandListener(identifier: ControlCommandIdentifiers, callback: ControlCommandListener) {
        controlCommandListeners.getOrPut(identifier) { mutableListOf() }.add(callback)
    }

    private var callback: PacketCallback? = null

    fun setPacketCallback(callback: PacketCallback) {
        this.callback = callback
    }

    fun createDataPacket(data: ByteArray): ByteArray {
        return HEADER_BYTES + data
    }

    fun createControlCommandPacket(identifier: Byte, data: ByteArray): ByteArray {
        val opcode = byteArrayOf(Opcodes.CONTROL_COMMAND, 0x00)
        val payload = ByteArray(7)

        System.arraycopy(opcode, 0, payload, 0, 2)
        payload[2] = identifier

        val dataLength = minOf(data.size, 4)
        System.arraycopy(data, 0, payload, 3, dataLength)

        return payload
    }

    fun sendDataPacket(data: ByteArray): Boolean {
        return sendPacket(createDataPacket(data))
    }

    fun sendControlCommand(identifier: Byte, value: ByteArray): Boolean {
        val controlPacket = createControlCommandPacket(identifier, value)
        setControlCommandStatusValue(
            ControlCommandIdentifiers.fromByte(identifier) ?: return false,
            value
        )
        return sendDataPacket(controlPacket)
    }

    fun sendControlCommand(identifier: Byte, value: Byte): Boolean {
        val controlPacket = createControlCommandPacket(identifier, byteArrayOf(value))
        setControlCommandStatusValue(
            ControlCommandIdentifiers.fromByte(identifier) ?: return false,
            byteArrayOf(value)
        )
        return sendDataPacket(controlPacket)
    }

    fun sendControlCommand(identifier: Byte, value: Boolean): Boolean {
        val controlPacket = createControlCommandPacket(identifier, if (value) byteArrayOf(0x01) else byteArrayOf(0x02))
        setControlCommandStatusValue(
            ControlCommandIdentifiers.fromByte(identifier) ?: return false,
            if (value) byteArrayOf(0x01) else byteArrayOf(0x02)
        )
        return sendDataPacket(controlPacket)
    }

    fun sendControlCommand(identifier: Byte, value: Int): Boolean {
        val controlPacket = createControlCommandPacket(identifier, byteArrayOf(value.toByte()))
        setControlCommandStatusValue(
            ControlCommandIdentifiers.fromByte(identifier) ?: return false,
            byteArrayOf(value.toByte())
        )
        return sendDataPacket(controlPacket)
    }

    fun parseProximityKeysResponse(data: ByteArray): Map<ProximityKeyType, ByteArray> {
        Log.d(TAG, "Parsing Proximity Keys Response: ${data.joinToString(" ") { "%02X".format(it) }}")
        if (data.size < 4) {
            throw IllegalArgumentException("Data array too short to parse Proximity Keys Response")
        }
        if (data[4] != Opcodes.PROXIMITY_KEYS_RSP) {
            throw IllegalArgumentException("Data array does not start with PROXIMITY_KEYS_RSP opcode")
        }
        val keyCount = data[6].toInt()
        val keys = mutableMapOf<ProximityKeyType, ByteArray>()
        var offset = 7
        for (i in 0 until keyCount) {
            Log.d(TAG, "Parsing Proximity Key $i")
            if (offset + 3 >= data.size) {
                throw IllegalArgumentException("Data array too short to parse Proximity Keys Response")
            }
            val keyType = data[offset]
            val keyLength = data[offset + 2].toInt()
            Log.d(TAG, "Key Type: ${keyType.toString(16)}, Key Length: $keyLength")
            offset += 4
            if (offset + keyLength > data.size) {
                throw IllegalArgumentException("Data array too short to parse Proximity Keys Response")
            }
            val key = ByteArray(keyLength)
            System.arraycopy(data, offset, key, 0, keyLength)
            keys[ProximityKeyType.fromByte(keyType)] = key
            offset += keyLength
            Log.d(TAG, "Parsed Proximity Key: Type: ${keyType}, Length: $keyLength, Key: ${key.joinToString(" ") { "%02X".format(it) }}")
        }
        return keys
    }

    fun sendRequestProximityKeys(type: Byte): Boolean {
        Log.d(TAG, "Requesting proximity keys of type: ${type.toString(16)}")
        return sendDataPacket(createRequestProximityKeysPacket(type))
    }

    fun createRequestProximityKeysPacket(type: Byte): ByteArray {
        val opcode = byteArrayOf(Opcodes.PROXIMITY_KEYS_REQ, 0x00)
        val data = byteArrayOf(type, 0x00)
        return opcode + data
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun receivePacket(packet: ByteArray) {
        if (!packet.toHexString().startsWith("04000400")) {
            Log.w(TAG, "Received packet does not start with expected header: ${packet.joinToString(" ") { "%02X".format(it) }}")
            return
        }
        if (packet.size < 6) {
            Log.w(TAG, "Received packet too short: ${packet.joinToString(" ") { "%02X".format(it) }}")
            return
        }

        val opcode = packet[4]

        when (opcode) {
            Opcodes.BATTERY_INFO -> {
                callback?.onBatteryInfoReceived(packet)
            }
            Opcodes.CONTROL_COMMAND -> {
                val controlCommand = ControlCommand.fromByteArray(packet)
                setControlCommandStatusValue(
                    ControlCommandIdentifiers.fromByte(controlCommand.identifier) ?: return,
                    controlCommand.value
                )
                Log.d(TAG, "Control command received: ${controlCommand.identifier.toHexString()} - ${controlCommand.value.joinToString(" ") { "%02X".format(it) }}")
                Log.d(TAG, "Control command list is now: ${
                    controlCommandStatusList.joinToString(", ") { "${it.identifier.name} (${it.identifier.value.toHexString()}) - ${it.value.joinToString(" ") { "%02X".format(it) }}" }
                }")

                val controlCommandIdentifier = ControlCommandIdentifiers.fromByte(controlCommand.identifier)
                if (controlCommandIdentifier != null) {
                    controlCommandListeners[controlCommandIdentifier]?.forEach { listener ->
                        listener.onControlCommandReceived(controlCommand)
                    }
                } else {
                    Log.w(TAG, "Unknown control command identifier: ${controlCommand.identifier.toHexString()}")
                }

                callback?.onControlCommandReceived(packet)
            }
            Opcodes.EAR_DETECTION -> {
                callback?.onEarDetectionReceived(packet)
            }
            Opcodes.CONVERSATION_AWARENESS -> {
                callback?.onConversationAwarenessReceived(packet)
            }
            Opcodes.DEVICE_METADATA -> {
                callback?.onDeviceMetadataReceived(packet)
            }
            Opcodes.HEADTRACKING -> {
                if (packet.size < 70) {
                    Log.w(TAG, "Received HEADTRACKING packet too short: ${packet.joinToString(" ") { "%02X".format(it) }}")
                    return
                }
                callback?.onHeadTrackingReceived(packet)
            }
            Opcodes.PROXIMITY_KEYS_RSP -> {
                callback?.onProximityKeysReceived(packet)
            }
            else -> {
                callback?.onUnknownPacketReceived(packet)
            }
        }
    }

    fun sendNotificationRequest(): Boolean {
        return sendDataPacket(createRequestNotificationPacket())
    }

    fun createRequestNotificationPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.REQUEST_NOTIFICATIONS, 0x00)
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        return opcode + data
    }

    fun sendSetFeatureFlagsPacket(): Boolean {
        return sendDataPacket(createSetFeatureFlagsPacket())
    }

    fun createSetFeatureFlagsPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.SET_FEATURE_FLAGS, 0x00)
        val data = byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        return opcode + data
    }

    fun createHandshakePacket(): ByteArray {
        return byteArrayOf(
            0x00, 0x00, 0x04, 0x00,
            0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        )
    }

    fun sendStartHeadTracking(): Boolean {
        return sendDataPacket(createStartHeadTrackingPacket())
    }

    fun createStartHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00, 0x00, 0x10, 0x00, 0x10, 0x00, 0x08, 0xA1.toByte(), 0x02, 0x42, 0x0B, 0x08, 0x0E, 0x10, 0x02, 0x1A, 0x05, 0x01, 0x40, 0x9C.toByte(), 0x00, 0x00,
        )
        return opcode + data
    }

    fun createAlternateStartHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00, 0x00, 0x10, 0x00, 0x0F, 0x00, 0x08, 0x73, 0x42, 0x0B, 0x08, 0x10, 0x10, 0x02, 0x1A, 0x05, 0x01, 0x40, 0x9C.toByte(), 0x00, 0x00
        )
        return opcode + data
    }

    fun sendStopHeadTracking(): Boolean {
        return sendDataPacket(createStopHeadTrackingPacket())
    }

    fun createStopHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00, 0x00, 0x10, 0x00, 0x11, 0x00, 0x08, 0x7E, 0x10, 0x02, 0x42, 0x0B, 0x08, 0x4E, 0x10, 0x02, 0x1A, 0x05, 0x01, 0x00, 0x00, 0x00, 0x00
        )
        return opcode + data
    }

    fun createAlternateStopHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00, 0x00, 0x10, 0x00, 0x0F, 0x00, 0x08, 0x75, 0x42, 0x0B, 0x08, 0x10, 0x10, 0x02, 0x1A, 0x05, 0x01, 0x00, 0x00, 0x00, 0x00
        )
        return opcode + data
    }

    fun sendRename(name: String): Boolean {
        return sendDataPacket(createRenamePacket(name))
    }

    fun createRenamePacket(name: String): ByteArray {
        val nameBytes = name.toByteArray()
        val size = nameBytes.size
        val packet = ByteArray(5 + size)
        packet[0] = Opcodes.RENAME
        packet[1] = 0x00
        packet[2] = size.toByte()
        packet[3] = 0x00
        System.arraycopy(nameBytes, 0, packet, 4, size)

        return packet
    }


    data class ControlCommand(
        val identifier: Byte,
        val value: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ControlCommand

            if (identifier != other.identifier) return false
            if (!value.contentEquals(other.value)) return false

            return true
        }

        override fun hashCode(): Int {
            var result: Int = identifier.toInt()
            result = 31 * result + value.contentHashCode()
            return result
        }

        companion object {
            fun fromByteArray(data: ByteArray): ControlCommand {
                if (data.size < 4) {
                    throw IllegalArgumentException("Data array too short to parse ControlCommand")
                }
                if (data[0] == 0x04.toByte() && data[1] == 0x00.toByte() && data[2] == 0x04.toByte() && data[3] == 0x00.toByte()) {
                    val newData = ByteArray(data.size - 4)
                    System.arraycopy(data, 4, newData, 0, data.size - 4)
                    return fromByteArray(newData)
                }
                if (data[0] != Opcodes.CONTROL_COMMAND) {
                    throw IllegalArgumentException("Data array does not start with CONTROL_COMMAND opcode")
                }
                val identifier = data[2]

                val value = ByteArray(4)
                System.arraycopy(data, 3, value, 0, 4)

                // drop trailing zeroes in the array, and return the bytearray of the reduced array
                val trimmedValue = value.takeWhile { it != 0x00.toByte() }.toByteArray()
                return ControlCommand(identifier, trimmedValue)
            }
        }
    }

   @OptIn(ExperimentalStdlibApi::class)
   fun sendPacket(packet: ByteArray): Boolean {
        try {
            Log.d(TAG, "Sending packet: ${packet.joinToString(" ") { "%02X".format(it) }}")

            if (packet[4] == Opcodes.CONTROL_COMMAND) {
                val controlCommand = ControlCommand.fromByteArray(packet)
                Log.d(TAG, "Control command: ${controlCommand.identifier.toHexString()} - ${controlCommand.value.joinToString(" ") { "%02X".format(it) }}")
                setControlCommandStatusValue(
                    ControlCommandIdentifiers.fromByte(controlCommand.identifier) ?: return false,
                    controlCommand.value
                )
            }

            val socket = BluetoothConnectionManager.getCurrentSocket()
            if (socket?.isConnected == true) {
                socket.outputStream?.write(packet)
                socket.outputStream?.flush()
                return true
            } else {
                Log.d(TAG, "Can't send packet: Socket not initialized or connected")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet: ${e.message}")
            return false
        }
    }
}
