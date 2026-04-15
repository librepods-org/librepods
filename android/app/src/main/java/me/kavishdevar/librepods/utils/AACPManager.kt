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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.utils

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Manager class for Apple Accessory Communication Protocol (AACP)
 * This class is responsible for handling the L2CAP socket management,
 * constructing and parsing packets for communication with AirPods.
 */
class AACPManager {
    companion object {
        private const val TAG = "AACPManager"

        @Suppress("unused")
        object Opcodes {
            const val SET_FEATURE_FLAGS: Byte = 0x4D
            const val REQUEST_NOTIFICATIONS: Byte = 0x0F
            const val BATTERY_INFO: Byte = 0x04
            const val CONTROL_COMMAND: Byte = 0x09
            const val EAR_DETECTION: Byte = 0x06
            const val CONVERSATION_AWARENESS: Byte = 0x4B
            const val INFORMATION: Byte = 0x1D
            const val RENAME: Byte = 0x1E
            const val HEADTRACKING: Byte = 0x17
            const val PROXIMITY_KEYS_REQ: Byte = 0x30
            const val PROXIMITY_KEYS_RSP: Byte = 0x31
            const val STEM_PRESS: Byte = 0x19
            const val EQ_DATA: Byte = 0x53
            const val CONNECTED_DEVICES: Byte = 0x2E // TiPi 1
            const val AUDIO_SOURCE: Byte = 0x0E // TiPi 2
            const val SMART_ROUTING: Byte = 0x10
            const val TIPI_3: Byte = 0x0C // Don't know this one
            const val SMART_ROUTING_RESP: Byte = 0x11
            const val SEND_CONNECTED_MAC: Byte = 0x14
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
            ALLOW_OFF_OPTION(0x34),
            STEM_CONFIG(0x39),
            SLEEP_DETECTION_CONFIG(0x35),
            ALLOW_AUTO_CONNECT(0x36), // not sure what this does, AUTOMATIC_CONNECTION is the only one used, but this is newer... so ¯\_(ツ)_/¯
            EAR_DETECTION_CONFIG(0x0A),
            AUTOMATIC_CONNECTION_CONFIG(0x20),
            OWNS_CONNECTION(0x06),
            PPE_TOGGLE_CONFIG(0x37),
            PPE_CAP_LEVEL_CONFIG(0x38);

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
                    ProximityKeyType.entries.find { it.value == byte }
                        ?: throw IllegalArgumentException("Unknown ProximityKeyType: $byte")
            }
        }

        enum class StemPressType(val value: Byte) {
            SINGLE_PRESS(0x05),
            DOUBLE_PRESS(0x06),
            TRIPLE_PRESS(0x07),
            LONG_PRESS(0x08);

            companion object {
                fun fromByte(byte: Byte): StemPressType? =
                    entries.find { it.value == byte }
            }
        }

        enum class StemPressBudType(val value: Byte) {
            LEFT(0x01),
            RIGHT(0x02);

            companion object {
                fun fromByte(byte: Byte): StemPressBudType? =
                    entries.find { it.value == byte }
            }
        }

        enum class AudioSourceType(val value: Byte) {
            NONE(0x00),
            CALL(0x01),
            MEDIA(0x02);

            companion object {
                fun fromByte(byte: Byte): AudioSourceType? =
                    entries.find { it.value == byte }
            }
        }

        data class AudioSource(
            val mac: String,
            val type: AudioSourceType
        )

        data class ConnectedDevice(
            val mac: String,
            val info1: Byte,
            val info2: Byte,
            var type: String?
        )

        data class AirPodsInformation(
            val name: String,
            val modelNumber: String,
            val manufacturer: String,
            val serialNumber: String,
            val version1: String,
            val version2: String,
            val hardwareRevision: String,
            val updaterIdentifier: String,
            val leftSerialNumber: String,
            val rightSerialNumber: String,
            val version3: String
        )
    }

    var controlCommandStatusList: MutableList<ControlCommandStatus> =
        mutableListOf<ControlCommandStatus>()
    var controlCommandListeners: MutableMap<ControlCommandIdentifiers, MutableList<ControlCommandListener>> =
        mutableMapOf()

    var owns: Boolean = false
        private set

    var oldConnectedDevices: List<ConnectedDevice> = listOf()
        private set

    var connectedDevices: List<ConnectedDevice> = listOf()
        private set

    var audioSource: AudioSource? = null
        private set

    var eqData = FloatArray(8) { 0.0f }
        private set

    var eqOnPhone: Boolean = false
        private set

    var eqOnMedia: Boolean = false
        private set

    fun getControlCommandStatus(identifier: ControlCommandIdentifiers): ControlCommandStatus? {
        return controlCommandStatusList.find { it.identifier == identifier }
    }

    private fun setControlCommandStatusValue(
        identifier: ControlCommandIdentifiers,
        value: ByteArray
    ) {
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

        if (identifier == ControlCommandIdentifiers.OWNS_CONNECTION) {
            owns = value.isNotEmpty() && value[0] == 0x01.toByte()
        }
    }

    interface PacketCallback {
        fun onBatteryInfoReceived(batteryInfo: ByteArray)
        fun onEarDetectionReceived(earDetection: ByteArray)
        fun onConversationAwarenessReceived(conversationAwareness: ByteArray)
        fun onControlCommandReceived(controlCommand: ByteArray)
        fun onDeviceInformationReceived(deviceInformation: AirPodsInformation)
        fun onHeadTrackingReceived(headTracking: ByteArray)
        fun onUnknownPacketReceived(packet: ByteArray)
        fun onProximityKeysReceived(proximityKeys: ByteArray)
        fun onStemPressReceived(stemPress: ByteArray)
        fun onAudioSourceReceived(audioSource: ByteArray)
        fun onOwnershipChangeReceived(owns: Boolean)
        fun onConnectedDevicesReceived(connectedDevices: List<ConnectedDevice>)
        fun onOwnershipToFalseRequest(sender: String, reasonReverseTapped: Boolean)
        fun onShowNearbyUI(sender: String)
    }

    fun parseStemPressResponse(data: ByteArray): Pair<StemPressType, StemPressBudType> {
        Log.d(TAG, "Parsing Stem Press Response: ${data.joinToString(" ") { "%02X".format(it) }}")
        if (data.size != 8) {
            throw IllegalArgumentException("Data array too short to parse Stem Press Response")
        }
        if (data[4] != Opcodes.STEM_PRESS) {
            throw IllegalArgumentException("Data array does not start with STEM_PRESS opcode")
        }
        val type = StemPressType.fromByte(data[6])
            ?: throw IllegalArgumentException("Unknown Stem Press Type: ${data[5]}")
        val bud = StemPressBudType.fromByte(data[7])
            ?: throw IllegalArgumentException("Unknown Stem Press Bud Type: ${data[6]}")
        return Pair(type, bud)
    }

    interface ControlCommandListener {
        fun onControlCommandReceived(controlCommand: ControlCommand)
    }

    fun registerControlCommandListener(
        identifier: ControlCommandIdentifiers,
        callback: ControlCommandListener
    ) {
        controlCommandListeners.getOrPut(identifier) { mutableListOf() }.add(callback)
    }

    fun unregisterControlCommandListener(
        identifier: ControlCommandIdentifiers,
        callback: ControlCommandListener
    ) {
        controlCommandListeners[identifier]?.remove(callback)
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

    @OptIn(ExperimentalStdlibApi::class)
    fun sendControlCommand(identifier: Byte, value: Byte): Boolean {
        val controlPacket = createControlCommandPacket(identifier, byteArrayOf(value))
        setControlCommandStatusValue(
            ControlCommandIdentifiers.fromByte(identifier) ?: return false,
            byteArrayOf(value)
        )
        return sendDataPacket(controlPacket)
    }

    fun sendControlCommand(identifier: Byte, value: Boolean): Boolean {
        val controlPacket = createControlCommandPacket(
            identifier,
            if (value) byteArrayOf(0x01) else byteArrayOf(0x02)
        )
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
        Log.d(
            TAG,
            "Parsing Proximity Keys Response: ${data.joinToString(" ") { "%02X".format(it) }}"
        )
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
            Log.d(
                TAG,
                "Parsed Proximity Key: Type: ${keyType}, Length: $keyLength, Key: ${
                    key.joinToString(" ") { "%02X".format(it) }
                }"
            )
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
            Log.w(
                TAG,
                "Received packet does not start with expected header: ${
                    packet.joinToString(" ") {
                        "%02X".format(it)
                    }
                }"
            )
            return
        }
        if (packet.size < 6) {
            Log.w(
                TAG,
                "Received packet too short: ${packet.joinToString(" ") { "%02X".format(it) }}"
            )
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
                Log.d(
                    TAG,
                    "Control command received: ${controlCommand.identifier.toHexString()} - ${
                        controlCommand.value.joinToString(" ") { "%02X".format(it) }
                    }"
                )
                Log.d(
                    TAG, "Control command list is now: ${
                    controlCommandStatusList.joinToString(", ") { it ->
                        "${it.identifier.name} (${it.identifier.value.toHexString()}) - ${
                            it.value.joinToString(
                                " "
                            ) { "%02X".format(it) }
                        }"
                    }
                }")

                val controlCommandIdentifier =
                    ControlCommandIdentifiers.fromByte(controlCommand.identifier)
                if (controlCommandIdentifier != null) {
                    controlCommandListeners[controlCommandIdentifier]?.forEach { listener ->
                        listener.onControlCommandReceived(controlCommand)
                    }
                } else {
                    Log.w(
                        TAG,
                        "Unknown control command identifier: ${controlCommand.identifier.toHexString()}"
                    )
                }

                if (controlCommandIdentifier == ControlCommandIdentifiers.OWNS_CONNECTION) {
                    callback?.onOwnershipChangeReceived(owns)
                }

                callback?.onControlCommandReceived(packet)
            }

            Opcodes.EAR_DETECTION -> {
                callback?.onEarDetectionReceived(packet)
            }

            Opcodes.CONVERSATION_AWARENESS -> {
                callback?.onConversationAwarenessReceived(packet)
            }

            Opcodes.HEADTRACKING -> {
                if (packet.size < 70) {
                    Log.w(
                        TAG,
                        "Received HEADTRACKING packet too short: ${
                            packet.joinToString(" ") {
                                "%02X".format(it)
                            }
                        }"
                    )
                    return
                }
                callback?.onHeadTrackingReceived(packet)
            }

            Opcodes.PROXIMITY_KEYS_RSP -> {
                callback?.onProximityKeysReceived(packet)
            }

            Opcodes.STEM_PRESS -> {
                callback?.onStemPressReceived(packet)
            }

            Opcodes.AUDIO_SOURCE -> {
                try {
                    val (mac, type) = parseAudioSourceResponse(packet)
                    audioSource = AudioSource(mac, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing audio source response: ${e.message}")
                }
                callback?.onAudioSourceReceived(packet)
            }

            Opcodes.CONNECTED_DEVICES -> {
                oldConnectedDevices = connectedDevices
                connectedDevices = parseConnectedDevicesResponse(packet)
                callback?.onConnectedDevicesReceived(connectedDevices)
            }

            Opcodes.SMART_ROUTING_RESP -> {
                val packetString = packet.decodeToString()
                val sender = packet.sliceArray(6..11).reversedArray().joinToString(":") { "%02X".format(it) }

                // if (connectedDevices.find { it.mac == sender }?.type == null && packetString.contains("btName")) {
                //     val nameStartIndex = packetString.indexOf("btName") + 8
                //     val nameEndIndex = if (packetString.contains("other")) (packetString.indexOf("otherDevice") - 1) else (packetString.indexOf("nearbyAudio") - 1)
                //     val name = packet.sliceArray(nameStartIndex..nameEndIndex).decodeToString()
                //     connectedDevices.find { it.mac == sender }?.type = name
                //     Log.d(TAG, "Device $sender is named $name")
                // } // doesn't work, it's different for Mac and iPad. just hardcoding for now
                if ("iPad" in packetString) {
                    connectedDevices.find { it.mac == sender }?.type = "iPad"
                } else if ("Mac" in packetString) {
                    connectedDevices.find { it.mac == sender }?.type = "Mac"
                } else if ("iPhone" in packetString) { // not sure if this is it - don't have an iphone
                    connectedDevices.find { it.mac == sender }?.type = "iPhone"
                } else if ("Linux" in packetString) {
                    connectedDevices.find { it.mac == sender }?.type = "Linux"
                } else if ("Android" in packetString) {
                    connectedDevices.find { it.mac == sender }?.type = "Android"
                }
                Log.d(TAG, "Smart Routing Response from $sender: $packetString, type: ${connectedDevices.find { it.mac == sender }?.type}")
                if (packetString.contains("SetOwnershipToFalse")) {
                    callback?.onOwnershipToFalseRequest(sender, packetString.contains("ReverseBannerTapped"))
                }
                if (packetString.contains("ShowNearbyUI")) {
                    callback?.onShowNearbyUI(sender)
                }
            }

            Opcodes.EQ_DATA -> {
                if (packet.size != 140) {
                    Log.w(
                        TAG,
                        "Received EQ_DATA packet of unexpected size: ${packet.size}, expected 140"
                    )
                    return
                }
                if (packet[6] != 0x84.toByte()) {
                    Log.w(
                        TAG,
                        "Received EQ_DATA packet with unexpected identifier: ${packet[6].toHexString()}, expected 0x84"
                    )
                    return
                }

                eqOnMedia = (packet[10] == 0x01.toByte())
                eqOnPhone = (packet[11] == 0x01.toByte())
                // there are 4 eqs. i am not sure what those are for, maybe all 4 listening modes, or maybe phone+media left+right, but then there shouldn't be another flag for phone/media enabled. just directly the EQ... weird.
                // the EQs are little endian floats
                val eq1 = ByteBuffer.wrap(packet, 12, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val eq2 = ByteBuffer.wrap(packet, 44, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val eq3 = ByteBuffer.wrap(packet, 76, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val eq4 = ByteBuffer.wrap(packet, 108, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

                // for now, taking just the first EQ
                eqData = FloatArray(8) { i -> eq1.get(i) }
                Log.d(TAG, "EQ Data set to: ${eqData.toList()}, eqOnPhone: $eqOnPhone, eqOnMedia: $eqOnMedia")
            }

            Opcodes.INFORMATION -> {
                Log.e(TAG, "Parsing Information Packet")
                val information = parseInformationPacket(packet)
                callback?.onDeviceInformationReceived(information)
            }
            else -> {
                Log.d(TAG, "Unknown opcode received: ${opcode.toHexString()}")
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
        // note to self #1: third byte is 0xfd when ear detection is disabled
        // note to self #2: this can be sent any time, not just at the start of the aacp connection
        return opcode + data
    }

    fun sendSetFeatureFlagsPacket(): Boolean {
        return sendDataPacket(createSetFeatureFlagsPacket())
    }

    fun createSetFeatureFlagsPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.SET_FEATURE_FLAGS, 0x00)
        val data = byteArrayOf(0xD7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
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
            0x00,
            0x00,
            0x10,
            0x00,
            0x10,
            0x00,
            0x08,
            0xA1.toByte(),
            0x02,
            0x42,
            0x0B,
            0x08,
            0x0E,
            0x10,
            0x02,
            0x1A,
            0x05,
            0x01,
            0x40,
            0x9C.toByte(),
            0x00,
            0x00,
        )
        return opcode + data
    }

    fun createAlternateStartHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00,
            0x00,
            0x10,
            0x00,
            0x0F,
            0x00,
            0x08,
            0x73,
            0x42,
            0x0B,
            0x08,
            0x10,
            0x10,
            0x02,
            0x1A,
            0x05,
            0x01,
            0x40,
            0x9C.toByte(),
            0x00,
            0x00
        )
        return opcode + data
    }

    fun sendStopHeadTracking(): Boolean {
        return sendDataPacket(createStopHeadTrackingPacket())
    }

    fun createStopHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00,
            0x00,
            0x10,
            0x00,
            0x11,
            0x00,
            0x08,
            0x7E,
            0x10,
            0x02,
            0x42,
            0x0B,
            0x08,
            0x4E,
            0x10,
            0x02,
            0x1A,
            0x05,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00
        )
        return opcode + data
    }

    fun createAlternateStopHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00,
            0x00,
            0x10,
            0x00,
            0x0F,
            0x00,
            0x08,
            0x75,
            0x42,
            0x0B,
            0x08,
            0x10,
            0x10,
            0x02,
            0x1A,
            0x05,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00
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

    fun sendMediaInformationNewDevice(selfMacAddress: String, targetMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) || targetMacAddress.length != 17 || !targetMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.w(TAG, "Invalid MAC address format, got: selfMacAddress=$selfMacAddress, targetMacAddress=$targetMacAddress")
            return false
        }
        Log.d(TAG, "SELFMAC: ${selfMacAddress}, TARGETMAC: $targetMacAddress")
        Log.d(TAG, "Sending Media Information packet to $targetMacAddress")
        return sendDataPacket(createMediaInformationNewDevicePacket(selfMacAddress, targetMacAddress))
    }

    fun createMediaInformationNewDevicePacket(selfMacAddress: String, targetMacAddress: String): ByteArray {
        val opcode = byteArrayOf(Opcodes.SMART_ROUTING, 0x00)
        val buffer = ByteBuffer.allocate(116)
        buffer.put(
            targetMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(byteArrayOf(0x6C, 0x00))
        buffer.put(byteArrayOf(0x01, 0xE5.toByte(), 0x4A))
        buffer.put("playingApp".toByteArray())
        buffer.put(0x42)
        buffer.put("NA".toByteArray())
        buffer.put(0x52)
        buffer.put("hostStreamingState".toByteArray())
        buffer.put(0x42)
        buffer.put("NO".toByteArray())
        buffer.put(0x49)
        buffer.put("btAddress".toByteArray())
        buffer.put(0x51)
        buffer.put(selfMacAddress.toByteArray())
        buffer.put(0x46)
        buffer.put("btName".toByteArray())
        buffer.put(0x47)
        buffer.put("Android".toByteArray())
        buffer.put(0x58)
        buffer.put("otherDevice".toByteArray())
        buffer.put("AudioCategory".toByteArray())
        buffer.put(byteArrayOf(0x30, 0x64))

        return opcode + buffer.array()
    }

    fun sendHijackRequest(selfMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.w(TAG, "Invalid MAC address format, got: selfMacAddress=$selfMacAddress")
            return false
        }
        var success = false
        for (connectedDevice in connectedDevices) {
            if (connectedDevice.mac != selfMacAddress) {
                Log.d(TAG, "Sending Hijack Request packet to ${connectedDevice.mac}")
                success = sendDataPacket(createHijackRequestPacket(connectedDevice.mac)) || success
            }
        }
        return success
    }

    fun createHijackRequestPacket(targetMacAddress: String): ByteArray {
        val opcode = byteArrayOf(Opcodes.SMART_ROUTING, 0x00)
        val buffer = ByteBuffer.allocate(106)
        buffer.put(
            targetMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(byteArrayOf(0x62, 0x00))
        buffer.put(byteArrayOf(0x01, 0xE5.toByte()))
        buffer.put(0x4A)
        buffer.put("localscore".toByteArray())
        buffer.put(byteArrayOf(0x30, 0x64))
        buffer.put(0x46)
        buffer.put("reason".toByteArray())
        buffer.put(0x48)
        buffer.put("Hijackv2".toByteArray())
        buffer.put(0x51)
        buffer.put("audioRoutingScore".toByteArray())
        buffer.put(byteArrayOf(0x31, 0x2D, 0x01, 0x5F))
        buffer.put("audioRoutingSetOwnershipToFalse".toByteArray())
        buffer.put(0x01)
        buffer.put(0x4B)
        buffer.put("remotescore".toByteArray())
        buffer.put(0xA5.toByte())

        return opcode + buffer.array()
    }

    fun sendMediaInformataion(selfMacAddress: String, streamingState: Boolean = false): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.d(TAG, "Invalid MAC address format, got: selfMacAddress=$selfMacAddress")
            return false
        }
        Log.d(TAG, "SELFMAC: $selfMacAddress")
        val targetMac = connectedDevices.find { it.mac != selfMacAddress }?.mac
        if (targetMac == null) {
            Log.w(TAG, "Cannot send Media Information packet: No connected device found")
            return false
        }
        Log.d(TAG, "Sending Media Information packet to $targetMac")
        return sendDataPacket(
            createMediaInformationPacket(
                selfMacAddress,
                targetMac,
                streamingState
            )
        )
    }

    fun createMediaInformationPacket(
        selfMacAddress: String,
        targetMacAddress: String,
        streamingState: Boolean = true
    ): ByteArray {
        val opcode = byteArrayOf(Opcodes.SMART_ROUTING, 0x00)
        val buffer = ByteBuffer.allocate(138)
        buffer.put(
            targetMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(
            byteArrayOf(
                0x82.toByte(), // related to the length
                0x00
            )
        )
        buffer.put(byteArrayOf(0x01, 0xE5.toByte(), 0x4A)) // unknown, constant
        buffer.put("PlayingApp".toByteArray())
        buffer.put(byteArrayOf(0x56)) // 'V', seems like a identifier or a separator
        buffer.put("com.google.ios.youtube".toByteArray()) // package name, hardcoding for now, aforementioned reason
        buffer.put(byteArrayOf(0x52)) // 'R'
        buffer.put("HostStreamingState".toByteArray())
        buffer.put(byteArrayOf(0x42)) // 'B'
        buffer.put((if (streamingState) "YES" else "NO").toByteArray()) // streaming state
        buffer.put(0x49) // 'I'
        buffer.put("btAddress".toByteArray()) // self MAC
        buffer.put(0x51) // 'Q'
        buffer.put(selfMacAddress.toByteArray()) // self MAC
        buffer.put("btName".toByteArray()) // self name
        buffer.put(0x47) // 'D'
        buffer.put("Android".toByteArray()) // if set to iPad, shows "Moved to iPad", but most likely we're running on a phone. setting to anything else of the same length will show iPhone instead.
        buffer.put(0x58) // 'X'
        buffer.put("otherDevice".toByteArray())
        buffer.put("AudioCategory".toByteArray())
        buffer.put(byteArrayOf(0x31, 0x2D, 0x01))

        return opcode+buffer.array()
    }

    fun sendSmartRoutingShowUI(selfMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.w(TAG, "Invalid MAC address format, got: selfMacAddress=$selfMacAddress")
            return false
        }

        val targetMac = connectedDevices.find { it.mac != selfMacAddress }?.mac
        if (targetMac == null) {
            Log.w(TAG, "Cannot send Smart Routing Show UI packet: No connected device found")
            return false
        }
        Log.d(TAG, "Sending Smart Routing Show UI packet to $targetMac")
        return sendDataPacket(createSmartRoutingShowUIPacket(targetMac))
    }

    fun createSmartRoutingShowUIPacket(targetMacAddress: String): ByteArray {
        val opcode = byteArrayOf(Opcodes.SMART_ROUTING, 0x00)
        val buffer = ByteBuffer.allocate(134)
        buffer.put(
            targetMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(byteArrayOf(0x7E, 0x00))
        buffer.put(byteArrayOf(0x01, 0xE6.toByte(), 0x5B))
        buffer.put("SmartRoutingKeyShowNearbyUI".toByteArray())
        buffer.put(0x01) // separator?
        buffer.put(0x4A)
        buffer.put("localscore".toByteArray())
        buffer.put(0x31, 0x2D)
        buffer.put(0x01)
        buffer.put(0x46)
        buffer.put("reasonHhijackv2".toByteArray())
        buffer.put(0x51.toByte())
        buffer.put("audioRoutingScore".toByteArray())
        buffer.put(0xA2.toByte())
        buffer.put(0x5F)
        buffer.put("audioRoutingSetOwnershipToFalse".toByteArray())
        buffer.put(0x01)
        buffer.put(0x4B)
        buffer.put("remotescore".toByteArray())
        buffer.put(0xA2.toByte())
        return opcode + buffer.array()
    }

    fun sendHijackReversed(selfMacAddress: String): Boolean {
        var success = false
        for (connectedDevice in connectedDevices) {
            if (connectedDevice.mac != selfMacAddress) {
                Log.d(TAG, "Sending Hijack Reversed packet to ${connectedDevice.mac}")
                success = sendDataPacket(createHijackReversedPacket(connectedDevice.mac)) || success
            }
        }
        return success
    }

    fun createHijackReversedPacket(targetMacAddress: String): ByteArray {
        val opcode = byteArrayOf(Opcodes.SMART_ROUTING, 0x00)
        val buffer = ByteBuffer.allocate(97)
        buffer.put(
            targetMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(byteArrayOf(0x59, 0x00))
        buffer.put(byteArrayOf(0x01, 0xE3.toByte()))
        buffer.put(0x5F)
        buffer.put("audioRoutingSetOwnershipToFalse".toByteArray())
        buffer.put(0x01)
        buffer.put(0x59)
        buffer.put("audioRoutingShowReverseUI".toByteArray())
        buffer.put(0x01)
        buffer.put(0x46)
        buffer.put("reason".toByteArray())
        buffer.put(0x53)
        buffer.put("ReverseBannerTapped".toByteArray())

        return opcode + buffer.array()
    }


    fun sendAddTiPiDevice(selfMacAddress: String, targetMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) || targetMacAddress.length != 17 || !targetMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.w(TAG, "Invalid MAC address format, got: selfMacAddress=$selfMacAddress, targetMacAddress=$targetMacAddress")
            return false
        }
        Log.d(TAG, "Sending Add TiPi Device packet to $targetMacAddress")
        return sendDataPacket(createAddTiPiDevicePacket(selfMacAddress, targetMacAddress))
    }

    fun createAddTiPiDevicePacket(selfMacAddress: String, targetMacAddress: String): ByteArray {
        val opcode = byteArrayOf(Opcodes.SMART_ROUTING, 0x00)
        val buffer = ByteBuffer.allocate(90)
        buffer.put(
            targetMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(byteArrayOf(0x52, 0x00))
        buffer.put(byteArrayOf(0x01, 0xE5.toByte()))
        buffer.put(0x48) // 'H'
        buffer.put("idleTime".toByteArray())
        buffer.put(byteArrayOf(0x08, 0x47))
        buffer.put("newTipi".toByteArray())
        buffer.put(byteArrayOf(0x01, 0x49))
        buffer.put("btAddress".toByteArray())
        buffer.put(0x51)
        buffer.put(selfMacAddress.toByteArray())
        buffer.put(0x46)
        buffer.put("btName".toByteArray())
        buffer.put(0x47)
        buffer.put("Android".toByteArray())
        buffer.put(0x50)
        buffer.put("nearbyAudioScore".toByteArray())
        buffer.put(byteArrayOf(0x0E))
        return opcode + buffer.array()
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

                val trimmedValue = value.dropLastWhile { it == 0x00.toByte() }.toByteArray()
                val finalValue = if (trimmedValue.isEmpty()) byteArrayOf(0x00) else trimmedValue
                return ControlCommand(identifier, finalValue)
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun sendStemConfigPacket(
        singlePressCustomized: Boolean = false,
        doublePressCustomized: Boolean = false,
        triplePressCustomized: Boolean = false,
        longPressCustomized: Boolean = false
    ): Boolean {
        val value = ((if (singlePressCustomized) 0x01 else 0) or
            (if (doublePressCustomized) 0x02 else 0) or
            (if (triplePressCustomized) 0x04 else 0) or
            (if (longPressCustomized) 0x08 else 0)).toByte()
        Log.d(TAG, "Sending Stem Config Packet with value: ${value.toHexString()}")
        return sendControlCommand(
            ControlCommandIdentifiers.STEM_CONFIG.value, value
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun sendPacket(packet: ByteArray): Boolean {
        try {
            Log.d(TAG, "Sending packet: ${packet.joinToString(" ") { "%02X".format(it) }}")

            if (packet[4] == Opcodes.CONTROL_COMMAND) {
                val controlCommand = ControlCommand.fromByteArray(packet)
                Log.d(
                    TAG,
                    "Control command: ${controlCommand.identifier.toHexString()} - ${
                        controlCommand.value.joinToString(" ") { "%02X".format(it) }
                    }"
                )
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

    fun sendPhoneMediaEQ(eq: FloatArray, phone: Byte = 0x02.toByte(), media: Byte = 0x02.toByte()) {
        if (eq.size != 8) throw IllegalArgumentException("EQ must be 8 floats")
        val header = byteArrayOf(
            0x04.toByte(),
            0x00.toByte(),
            0x04.toByte(),
            0x00.toByte(),
            0x53.toByte(),
            0x00.toByte(),
            0x84.toByte(),
            0x00.toByte(),
            0x02.toByte(),
            0x02.toByte(),
            phone,
            media
        )
        val buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        for (block in 0..3) {
            for (i in 0..7) {
                buffer.putFloat(eq[i])
            }
        }
        val payload = buffer.array()
        val packet = header + payload
        sendPacket(packet)
        this.eqData = eq.copyOf()
        this.eqOnPhone = phone == 0x01.toByte()
        this.eqOnMedia = media == 0x01.toByte()
    }

    fun parseAudioSourceResponse(data: ByteArray): Pair<String, AudioSourceType> {
        Log.d(TAG, "Parsing Audio Source Response: ${data.joinToString(" ") { "%02X".format(it) }}")
        if (data.size < 9) {
            throw IllegalArgumentException("Data array too short to parse Audio Source Response")
        }
        if (data[4] != Opcodes.AUDIO_SOURCE) {
            throw IllegalArgumentException("Data array does not start with AUDIO_SOURCE opcode")
        }
        val macBytes = data.sliceArray(6..11).reversedArray()
        val mac = macBytes.joinToString(":") { "%02X".format(it) }
        val typeByte = data[12]
        val type = AudioSourceType.fromByte(typeByte)
            ?: throw IllegalArgumentException("Unknown Audio Source Type: $typeByte")
        return Pair(mac, type)
    }

    fun parseConnectedDevicesResponse(data: ByteArray): List<ConnectedDevice> {
        Log.d(
            TAG,
            "Parsing Connected Devices Response: ${data.joinToString(" ") { "%02X".format(it) }}"
        )
        if (data.size < 8) {
            throw IllegalArgumentException("Data array too short to parse Connected Devices Response")
        }
        if (data[4] != Opcodes.CONNECTED_DEVICES) {
            throw IllegalArgumentException("Data array does not start with CONNECTED_DEVICES opcode")
        }
        val deviceCount = data[8].toInt()
        val devices = mutableListOf<ConnectedDevice>()

        var offset = 9
        for (i in 0 until deviceCount) {
            if (offset + 8 > data.size) {
                Log.w(TAG, "Data array too short to parse all connected devices, returning what we have")
                break
            }
            val macBytes = data.sliceArray(offset until offset + 6)
            val mac = macBytes.joinToString(":") { "%02X".format(it) }
            val info1 = data[offset + 6]
            val info2 = data[offset + 7]
            val existingDevice = devices.find { it.mac == mac }
            devices.add(ConnectedDevice(mac, info1, info2, existingDevice?.type))
            offset += 8
        }

        return devices
    }
    fun sendSomePacketIDontKnowWhatItIs() {
        // 2900 00ff ffff ffff ffff -- enables setting EQ
        sendDataPacket(
            byteArrayOf(
                0x29, 0x00,
                0x00, 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
            )
        )
    }

    fun disconnected() {
        Log.d(TAG, "Disconnected, clearing state")
        controlCommandStatusList.clear()
        controlCommandListeners.clear()
        owns = false
        oldConnectedDevices = listOf()
        connectedDevices = listOf()
        audioSource = null
    }

    fun parseInformationPacket(packet: ByteArray): AirPodsInformation {
        val data = packet.sliceArray(6 until packet.size)

        var index = 0
        while (index < data.size && data[index] != 0x00.toByte()) index++

        val strings = mutableListOf<String>()
        while (index < data.size) {
            // skip 0x00 bytes
            while (index < data.size && data[index] == 0x00.toByte()) index++
            if (index >= data.size) break
            val start = index
            // find next 0x00 byte
            while (index < data.size && data[index] != 0x00.toByte()) index++
            val str = data.sliceArray(start until index).decodeToString()
            strings.add(str)
        }

        strings.removeAt(0) // I'm too lazy to adjust, just removing the first empty string

        return AirPodsInformation(
            name = strings.getOrNull(0) ?: "",
            modelNumber = strings.getOrNull(1) ?: "",
            manufacturer = strings.getOrNull(2) ?: "",
            serialNumber = strings.getOrNull(3) ?: "",
            version1 = strings.getOrNull(4) ?: "",
            version2 = strings.getOrNull(5) ?: "",
            hardwareRevision = strings.getOrNull(6) ?: "",
            updaterIdentifier = strings.getOrNull(7) ?: "",
            leftSerialNumber = strings.getOrNull(8) ?: "",
            rightSerialNumber = strings.getOrNull(9) ?: "",
            version3 = strings.getOrNull(10) ?: "",
        )
    }
}
