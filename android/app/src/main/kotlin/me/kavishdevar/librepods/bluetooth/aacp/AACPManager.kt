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

package me.kavishdevar.librepods.bluetooth.aacp

import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.bluetooth.aacp.packet.AACPPacketType
import me.kavishdevar.librepods.bluetooth.aacp.packet.AudioSourcePacket
import me.kavishdevar.librepods.bluetooth.aacp.packet.BatteryInfoPacket
import me.kavishdevar.librepods.bluetooth.aacp.packet.ConnectedDevicesPacket
import me.kavishdevar.librepods.bluetooth.aacp.packet.ControlCommandPacket
import me.kavishdevar.librepods.bluetooth.aacp.packet.CustomEqPacket
import me.kavishdevar.librepods.bluetooth.aacp.packet.EarDetectionResponsePacket
import me.kavishdevar.librepods.bluetooth.aacp.packet.InformationPacket
import me.kavishdevar.librepods.bluetooth.aacp.packet.MagicKeyResponsePacket
import me.kavishdevar.librepods.bluetooth.aacp.packet.RTBuddyPacket
import me.kavishdevar.librepods.bluetooth.aacp.packet.RenamePacket
import me.kavishdevar.librepods.bluetooth.aacp.packet.StemPressPacket
import me.kavishdevar.librepods.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.bluetooth.aacp.rtbuddy.proto.SensorServiceType
import me.kavishdevar.librepods.bluetooth.aacp.types.AppleEvent
import me.kavishdevar.librepods.bluetooth.aacp.types.Capability
import me.kavishdevar.librepods.bluetooth.aacp.types.CapabilityEntry
import me.kavishdevar.librepods.bluetooth.aacp.types.ConnectOpcode
import me.kavishdevar.librepods.bluetooth.aacp.types.ControlCommand
import me.kavishdevar.librepods.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.bluetooth.aacp.types.CustomEq
import me.kavishdevar.librepods.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.bluetooth.aacp.types.MessageOpcode
import me.kavishdevar.librepods.bluetooth.aacp.types.RTBuddyDescriptor
import me.kavishdevar.librepods.bluetooth.aacp.types.SensorDataWxBuddyPayload
import me.kavishdevar.librepods.data.apple.BuddyState
import me.kavishdevar.librepods.data.audio.MicrophoneFrame
import me.kavishdevar.librepods.data.heartrate.HeartRateSample
import me.kavishdevar.librepods.devices.AppleDevice
import me.kavishdevar.librepods.devices.BatteryStatus
import me.kavishdevar.librepods.utils.HeadTracking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AACPManager(private val device: AppleDevice) {
    private val macParts = device.macAddress.value.split(":")
    private val TAG = "AACPManager[${macParts[0]}:${macParts[1]}:${macParts[2]}]"

    private var socket: BluetoothSocket? = null

    private val rtBuddyManager = RTBuddyManager(::sendPacket)

    private suspend fun sendDelayedInitPackets() {
        socket?.let {
            while (!it.isConnected) {
                Log.i(TAG, "waiting for connection...")
                delay(500.milliseconds)
            }
            Log.i(TAG, "initializing connection")

            connectService4()
            delay(200.milliseconds)
            sendSourceFeatureCapabilities()
            delay(200.milliseconds)
            sendNotificationRequest()
            delay(200.milliseconds)
            sendCountryCode()
            delay(200.milliseconds)
            sendRequestMagicKeys((MagicKeyType.IRK.value + MagicKeyType.ENC_KEY.value).toByte())
        } ?: run {
            Log.e(TAG, "socket is null after creation")
        }
    }

    fun connect(): Boolean {
        if (socket != null && socket!!.isConnected) {
            Log.i(TAG, "Already connected")
            return true
        }
        try {
            socket = device.createSocket(ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"),  4097)
        } catch (e: Exception) {
            Log.e(TAG, "failed to create socket", e)
            return false
        }

        socket?.let { socket ->
            try {
                Log.i(TAG, "connecting...")
                socket.connect()
            } catch (e: Exception) {
                Log.e(TAG, "failed to connect", e)
                return false
            }

            if (socket.isConnected) {
                Log.i(TAG, "connected!")
            }

            CoroutineScope(Dispatchers.IO).launch {
                sendDelayedInitPackets()
                delay(3.seconds) // just android stuff ig
                sendDelayedInitPackets()
            }

            CoroutineScope(Dispatchers.IO).launch {
                Log.i(TAG, "starting to read data...")

                while (socket.isConnected) {
                    try {
                        val buffer = ByteArray(1024)
                        val bytesRead = socket.inputStream.read(buffer)
                        var data: ByteArray
                        if (bytesRead > 0) {
                            data = buffer.copyOfRange(0, bytesRead)
                            try {
                                processPacket(data)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing received packet: ${e.message}")
                                e.printStackTrace()
                            }

                        } else if (bytesRead == -1) {
                            Log.i("AirPodsService", "socket closed (bytesRead = -1)")
                        }
                    } catch (e: Exception) {
                        Log.i(TAG, "Error reading data, we have probably disconnected.")
                        e.printStackTrace()
                    }
                }
            }
        } ?: run {
            Log.e(TAG, "socket is null after creation")
        }
        return true
    }

    fun sendRawPacket(data: ByteArray): Boolean {
        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.fromByte(data[4]),
            payload = data.copyOfRange(6, data.size),
            type = AACPPacketType.fromByte(data[0]),
            service = data[2]
        )
        return sendPacket(packet)
    }

    fun sendPacket(packet: AACPPacket): Boolean {
        try {
            Log.d(TAG, "Sending packet: ${packet.rawPacket.joinToString(" ") { "%02X".format(it) }}")

            val socket = this.socket ?: run {
                Log.e(TAG, "Can't send packet: Socket is null")
                return false
            }

            if (socket.isConnected) {
                socket.outputStream?.write(packet.rawPacket)
                socket.outputStream?.flush()

                device.updateState {
                    it.copy(
                        aacpPackets = it.aacpPackets + packet
                    )
                }

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

    @OptIn(ExperimentalStdlibApi::class)
    fun processPacket(packet: ByteArray) {
        if (!packet.toHexString().startsWith("04000400")) {
            Log.w(
                TAG, "Received packet does not start with expected header: ${
                    packet.joinToString(" ") {
                        "%02X".format(it)
                    }
                }")
            return
        }
        if (packet.size < 6) {
            Log.w(
                TAG, "Received packet too short: ${packet.joinToString(" ") { "%02X".format(it) }}"
            )
            return
        }
//        Log.d(TAG, "received packet: ${packet.toHexString()}")
        val opcode = packet[4]
        when (MessageOpcode.fromByte(opcode)) {
            MessageOpcode.BUD_ROLE -> {
                val payload = packet.copyOfRange(6, packet.size)
                val packet = AACPPacket.createUnknownPacket(
                    opcode = MessageOpcode.BUD_ROLE,
                    payload = payload
                )

                device.updateState {
                    it.copy(
                        leftIsPrimary = payload[0] == 0x01.toByte(),
                        aacpPackets = it.aacpPackets + packet
                    )
                }
            }
            MessageOpcode.CAPABILITIES -> {
                val payload = packet.copyOfRange(6, packet.size)
                val packet = AACPPacket.createUnknownPacket(
                    opcode = MessageOpcode.CAPABILITIES,
                    payload = payload
                )

                device.updateState {
                    it.copy(
                        capabilities = parseCapabilitiesResponse(packet.rawPacket),
                        aacpPackets = it.aacpPackets + packet
                    )
                }
            }
            MessageOpcode.BATTERY_INFO -> {
                val batteryPacket = BatteryInfoPacket.parse(packet)

                val cacheDisconnectedComponentBattery = device.settings.value.cacheDisconnectedComponentBattery

                if (!cacheDisconnectedComponentBattery) {
                    device.updateState {
                        it.copy(
                            battery = batteryPacket.batteries,
                            aacpPackets = it.aacpPackets + batteryPacket
                        )
                    }
                } else {
                    device.updateState { state ->
                        val previous = state.battery.associateBy { it.component }

                        val updated = batteryPacket.batteries.map { battery ->
                            val existing = previous[battery.component]

                            if (battery.status == BatteryStatus.DISCONNECTED && existing != null) {
                                battery.copy(level = existing.level)
                            } else {
                                battery
                            }
                        }.toSet()

                        state.copy(
                            battery = updated,
                            aacpPackets = state.aacpPackets + batteryPacket
                        )
                    }
                }
            }

            MessageOpcode.CONTROL_COMMAND -> {
                val controlCommandPacket = ControlCommandPacket.parse(packet)
                val controlCommand = controlCommandPacket.controlCommand

                Log.i(TAG, "Received control command: ${controlCommand.identifier}, value: ${controlCommand.value.toHexString()}")

                device.updateState {
                    it.copy(
                        controlStates = it.controlStates.toMutableMap().apply {
                            put(controlCommand.identifier, controlCommand.value)
                        }
                    )
                }

                if (controlCommand.identifier == ControlCommandIdentifier.OWNS_CONNECTION) {
                    val owns = controlCommand.value[0] == 0x01.toByte()
                    device.updateState {
                        it.copy(
                            owns = owns
                        )
                    }
                    Log.i(TAG, "Owns connection: $owns")
                }

                device.updateState {
                    it.copy(
                        aacpPackets = it.aacpPackets + controlCommandPacket
                    )
                }
            }

            MessageOpcode.EAR_DETECTION -> {
                val earDetectionResponsePacket = EarDetectionResponsePacket.parse(packet, device.state.value.leftIsPrimary)

                device.updateState {
                    it.copy(
                        componentState = earDetectionResponsePacket.componentStates,
                        aacpPackets = it.aacpPackets + earDetectionResponsePacket
                    )
                }
            }

            MessageOpcode.CONVERSATION_AWARENESS -> {
                val payload = packet.copyOfRange(6, packet.size)
                val packet = AACPPacket.createUnknownPacket(
                    opcode = MessageOpcode.CONVERSATION_AWARENESS,
                    payload = packet.copyOfRange(6, packet.size)
                )
                device.updateState {
                    it.copy(
                        conversationalAwarenessState = payload.getOrElse(3, {0}).toInt(),
                        aacpPackets = it.aacpPackets + packet
                    )
                }
            }

            MessageOpcode.BUDDY_COMMAND -> {
                val packet = RTBuddyPacket.parse(packet)

                val rtBuddyPayload = packet.rtBuddyPayload

                rtBuddyManager.handlePacket(packet)

                when (rtBuddyPayload.descriptor) {
                    RTBuddyDescriptor.SENSOR_DATA_WX -> {
                        val sensorDataWxBuddyPayload = rtBuddyPayload as SensorDataWxBuddyPayload
                        val data = sensorDataWxBuddyPayload.data

                        handleSensorData(data)
                    } else -> {
                        Log.d(TAG, "Unhandled descriptor: ${rtBuddyPayload.descriptor}")
                    }
                }

//                device.updateState {
//                    it.copy(
//                        aacpPackets = it.aacpPackets + packet
//                    )
//                }
            }

            MessageOpcode.MAGIC_KEYS_RESPONSE -> {
                val packet = MagicKeyResponsePacket.parse(packet)

                device.updateState {
                    it.copy(magicKeys = packet.magicKeys)
                }

                device.updateState {
                    it.copy(
                        aacpPackets = it.aacpPackets + packet
                    )
                }
            }

            MessageOpcode.STEM_PRESS -> {
                val packet = StemPressPacket.parse(packet)

                CoroutineScope(Dispatchers.IO).launch {
                    device.emitEvent(
                        AppleEvent.StemPress(
                            pressType = packet.stemPressType,
                            bud = packet.stemPressBud
                        )
                    )
                }

                device.updateState {
                    it.copy(
                        aacpPackets = it.aacpPackets + packet
                    )
                }
            }

            MessageOpcode.AUDIO_SOURCE -> {
                try {
                    val packet = AudioSourcePacket.parse(packet)

                    device.updateState {
                        it.copy(
                            audioSource = packet.audioSource,
                            aacpPackets = it.aacpPackets + packet
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing audio source response: ${e.message}")
                }
            }

            MessageOpcode.CONNECTED_DEVICES -> {
                try {
                    val packet = ConnectedDevicesPacket.parse(packet)

                    device.updateState {
                        it.copy (
                            connectedDevices = packet.connectedDevices,
                            aacpPackets = it.aacpPackets + packet
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing connected devices response: ${e.message}")
                }
            }

            MessageOpcode.SMART_ROUTING_RESPONSE -> {
                val packetString = packet.decodeToString()
                val sender =
                    packet.sliceArray(6..11).reversedArray().joinToString(":") { "%02X".format(it) }

                // if (connectedDevices.find { it.mac == sender }?.type == null && packetString.contains("btName")) {
                //     val nameStartIndex = packetString.indexOf("btName") + 8
                //     val nameEndIndex = if (packetString.contains("other")) (packetString.indexOf("otherDevice") - 1) else (packetString.indexOf("nearbyAudio") - 1)
                //     val name = packet.sliceArray(nameStartIndex..nameEndIndex).decodeToString()
                //     connectedDevices.find { it.mac == sender }?.type = name
                //     Log.d(TAG, "Device $sender is named $name")
                // } // doesn't work, it's different for Mac and iPad. just hardcoding for now
                if ("iPad" in packetString) {
                    device.state.value.connectedDevices.find { it.macAddress.value == sender }?.type = "iPad"
                } else if ("Mac" in packetString) {
                    device.state.value.connectedDevices.find { it.macAddress.value == sender }?.type = "Mac"
                } else if ("iPhone" in packetString) { // not sure if this is it - don't have an iphone
                    device.state.value.connectedDevices.find { it.macAddress.value == sender }?.type = "iPhone"
                } else if ("Linux" in packetString) {
                    device.state.value.connectedDevices.find { it.macAddress.value == sender }?.type = "Linux"
                } else if ("Android" in packetString) {
                    device.state.value.connectedDevices.find { it.macAddress.value == sender }?.type = "Android"
                }
                Log.i(TAG, "Smart Routing Response from $sender: $packetString, type: ${device.state.value.connectedDevices.find { it.macAddress.value == sender }?.type}")
                if (packetString.contains("SetOwnershipToFalse")) {
                    CoroutineScope(Dispatchers.IO).launch {
                        device.emitEvent(
                            AppleEvent.OwnershipToFalseRequest(
                                sender = sender,
                                reverseTapped = packetString.contains("ReverseBannerTapped")
                            )
                        )
                    }
                }
                if (packetString.contains("ShowNearbyUI")) {
                    CoroutineScope(Dispatchers.IO).launch {
                        device.emitEvent(
                            AppleEvent.ShowNearbyUi(
                                sender = sender
                            )
                        )
                    }
                }

                val packet = AACPPacket.createUnknownPacket(
                    opcode = MessageOpcode.SMART_ROUTING_RESPONSE,
                    payload = packet.copyOfRange(6, packet.size)
                )

                device.updateState {
                    it.copy(
                        aacpPackets = it.aacpPackets + packet
                    )
                }
            }

            MessageOpcode.HEADPHONE_ACCOMMODATION -> {
                if (packet.size != 140) {
                    Log.w(
                        TAG,
                        "Received HEADPHONE_ACCOMMODATION packet of unexpected size: ${packet.size}, expected 140"
                    )
                    return
                }
                if (packet[6] != 0x84.toByte()) {
                    Log.w(
                        TAG,
                        "Received HEADPHONE_ACCOMMODATION packet with unexpected identifier: ${packet[6].toHexString()}, expected 0x84"
                    )
                    return
                }

                val eqOnMedia = (packet[10] == 0x01.toByte())
                val eqOnPhone = (packet[11] == 0x01.toByte())
                // there are 4 eqs. i am not sure what those are for, maybe all 4 listening modes, or maybe phone+media left+right, but then there shouldn't be another flag for phone/media visible. just directly the EQ... weird.
                // the EQs are little endian floats
                val eq1 = ByteBuffer.wrap(packet, 12, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ByteBuffer.wrap(packet, 44, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ByteBuffer.wrap(packet, 76, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ByteBuffer.wrap(packet, 108, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

                // for now, taking just the first EQ
                val eqData = FloatArray(8) { i -> eq1.get(i) }

                Log.d(
                    TAG,
                    "EQ Data set to: ${eqData.toList()}, eqOnPhone: $eqOnPhone, eqOnMedia: $eqOnMedia"
                )

                val packet = AACPPacket.createUnknownPacket(
                    opcode = MessageOpcode.HEADPHONE_ACCOMMODATION,
                    payload = packet.copyOfRange(6, packet.size)
                )

                device.updateState {
                    it.copy(
                        headphoneAccommodation = eqData,
                        headphoneAccommodationEnabledForMedia = eqOnMedia,
                        headphoneAccommodationEnabledForPhone = eqOnPhone,
                        aacpPackets = it.aacpPackets + packet
                    )
                }
            }

            MessageOpcode.INFORMATION -> {
                val informationPacket = InformationPacket.parse(packet)

                device.updateState {
                    it.copy(
                        aacpPackets = it.aacpPackets + informationPacket
                    )
                }
                device.updateMetadata { informationPacket.metadata }
            }

            MessageOpcode.CUSTOM_EQ -> {
                try {
                    val customEqPacket = CustomEqPacket.parse(packet)

                    val customEq = customEqPacket.customEq

                    device.updateState {
                        it.copy(
                            customEq = customEq,
                            aacpPackets = it.aacpPackets + customEqPacket
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse custom EQ packet", e)
                }
            }

            MessageOpcode.MICROPHONE_STREAM -> {
                try {
                    MicrophoneFrame.parsePacket(packet).forEach{ frame ->
                        CoroutineScope(Dispatchers.IO).launch {
                            device.emitMicrophoneFrame(frame)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse microphoneState packet", e)
                }
            }

            MessageOpcode.MAC_ADDRESS -> {
                try {
                    val macAddress = packet.copyOfRange(6, 12).reversedArray().joinToString(":") { "%02X".format(it) }
                    val extra1 = packet[12]
                    val extra2 = packet[13]
                    Log.i(TAG, "Received MAC address packet: $macAddress, extra1: ${extra1.toHexString()}, extra2: ${extra2.toHexString()}")
                    device.updateState {
                        it.copy(
                            aacpPackets = it.aacpPackets + AACPPacket.createUnknownPacket(
                                opcode = MessageOpcode.MAC_ADDRESS,
                                payload = packet.copyOfRange(6, packet.size)
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse MAC address packet", e)
                }
            }

            else -> {
                val packet = AACPPacket.createUnknownPacket(
                    opcode = MessageOpcode.fromByte(opcode),
                    payload = packet.copyOfRange(6, packet.size)
                )
                device.updateState {
                    it.copy(
                        aacpPackets = it.aacpPackets + packet
                    )
                }
                Log.w(TAG, "Unhandled messageOpcode received: ${opcode.toHexString()}")
            }
        }
    }

    fun sendControlCommand(identifier: Byte, value: ByteArray): Boolean {
        val controlCommand = ControlCommand(ControlCommandIdentifier.fromByte(identifier) ?: return false, value)

        val packet = ControlCommandPacket.create(controlCommand)

        device.updateState {
            it.copy(
                controlStates = it.controlStates.toMutableMap().apply {
                    put(ControlCommandIdentifier.fromByte(identifier) ?: return false, value)
                }
            )
        }

        return sendPacket(packet)
    }

    fun sendControlCommand(identifier: Byte, value: Byte): Boolean = sendControlCommand(identifier, byteArrayOf(value))
    fun sendControlCommand(identifier: Byte, value: Boolean): Boolean = sendControlCommand(identifier, if (value) byteArrayOf(0x01) else byteArrayOf(0x02))
    fun sendControlCommand(identifier: Byte, value: Int): Boolean = sendControlCommand(identifier, byteArrayOf(value.toByte()))

    fun sendRequestMagicKeys(type: Byte): Boolean {
        Log.d(TAG, "Requesting proximity keys of type: ${type.toString(16)}")

        val payload = byteArrayOf(type, 0x00)

        val packet = AACPPacket.createUnknownPacket(
            MessageOpcode.MAGIC_KEYS_REQUEST,
            payload
        )

        return sendPacket(packet)
    }

    fun sendNotificationRequest(): Boolean {
        // note to self #1: third byte is 0xfd when ear detection is disabled
        // note to self #2: this can be sent any time, not just at the start of the aacp connection
        val payload = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val packet = AACPPacket.createUnknownPacket(
            MessageOpcode.REQUEST_NOTIFICATIONS,
            payload
        )

        return sendPacket(packet)
    }

    // todo: implement adaptive volume
    fun sendSourceFeatureCapabilities(): Boolean {
        val payload = byteArrayOf(0xd7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SOURCE_FEATURE_CAPABILITIES,
            payload
        )
        return sendPacket(packet)
    }

    fun connectService4(): Boolean {
        val payload = byteArrayOf(0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val packet = AACPPacket.createUnknownPacket(
            type = AACPPacketType.CONNECT,
            opcode = ConnectOpcode.SOMETHING,
            payload = payload,
        )

        return sendPacket(packet)
    }

    fun setSensorServiceReportInterval(sensorServiceType: SensorServiceType, interval: Duration): Boolean = rtBuddyManager.setSensorServiceReportInterval(sensorServiceType, interval)

    fun sendRename(name: String): Boolean {
        val packet = RenamePacket.create(name)

        device.updateMetadata {
            it.copy(
                name = name
            )
        }

        return sendPacket(packet)
    }

    fun sendMediaInformationNewDevice(selfMacAddress: String, targetMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) || targetMacAddress.length != 17 || !targetMacAddress.matches(
                Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
            )
        ) {
            Log.w(
                TAG,
                "Invalid MAC address format, got: selfMacAddress=$selfMacAddress, targetMacAddress=$targetMacAddress"
            )
            return false
        }

        Log.d(TAG, "SELFMAC: ${selfMacAddress}, TARGETMAC: $targetMacAddress")
        Log.d(TAG, "Sending Media Information packet to $targetMacAddress")

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

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SMART_ROUTING,
            payload = buffer.array()
        )

        return sendPacket(packet)
    }

    fun sendHijackRequest(selfMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
            Log.w(TAG, "Invalid MAC address format, got: selfMacAddress=$selfMacAddress")
            return false
        }
        var success = false
        for (connectedDevice in device.state.value.connectedDevices) {
            if (connectedDevice.macAddress.value != selfMacAddress) {
                Log.d(TAG, "Sending Hijack Request packet to ${connectedDevice.macAddress}")
                success = sendPacket(createHijackRequestPacket(connectedDevice.macAddress.value)) || success
            }
        }
        return success
    }

    fun createHijackRequestPacket(targetMacAddress: String): AACPPacket {
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

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SMART_ROUTING,
            payload = buffer.array()
        )

        return packet
    }

    fun sendMediaInformataion(selfMacAddress: String, streamingState: Boolean = false): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.d(TAG, "Invalid MAC address format, got: selfMacAddress=$selfMacAddress")
            return false
        }
        Log.d(TAG, "SELFMAC: $selfMacAddress")
        val targetMac = device.state.value.connectedDevices.find { it.macAddress.value != selfMacAddress }?.macAddress
        if (targetMac == null) {
            Log.w(TAG, "Cannot send Media Information packet: No connected device found")
            return false
        }
        Log.d(TAG, "Sending Media Information packet to $targetMac")

        val buffer = ByteBuffer.allocate(138)
        buffer.put(
            targetMac.value.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(
            byteArrayOf(
                0x82.toByte(), // related to the length
                0x00
            )
        )
        buffer.put(byteArrayOf(0x01, 0xE5.toByte(), 0x4A)) // unknown, constant
        buffer.put("PlayingApp".toByteArray())
        buffer.put(byteArrayOf(0x56)) // 'V', seems like an identifier or a separator
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

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SMART_ROUTING,
            payload = buffer.array()
        )

        return sendPacket(packet)
    }

    fun sendSmartRoutingShowUI(selfMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.w(TAG, "Invalid MAC address format, got: selfMacAddress=$selfMacAddress")
            return false
        }

        val targetMac = device.state.value.connectedDevices.find { it.macAddress.value != selfMacAddress }?.macAddress
        if (targetMac == null) {
            Log.w(TAG, "Cannot send Smart Routing Show UI packet: No connected device found")
            return false
        }
        Log.d(TAG, "Sending Smart Routing Show UI packet to $targetMac")

        val buffer = ByteBuffer.allocate(134)
        buffer.put(
            targetMac.value.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
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

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SMART_ROUTING,
            payload = buffer.array()
        )

        return sendPacket(packet)
    }

    fun sendHijackReversed(selfMacAddress: String): Boolean {
        var success = false
        for (connectedDevice in device.state.value.connectedDevices) {
            if (connectedDevice.macAddress.value != selfMacAddress) {
                Log.d(TAG, "Sending Hijack Reversed packet to ${connectedDevice.macAddress}")
                success = sendPacket(createHijackReversedPacket(connectedDevice.macAddress.value)) || success
            }
        }
        return success
    }

    fun createHijackReversedPacket(targetMacAddress: String): AACPPacket {
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

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SMART_ROUTING,
            payload = buffer.array()
        )

        return packet
    }

    fun sendAddTiPiDevice(selfMacAddress: String, targetMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) || targetMacAddress.length != 17 || !targetMacAddress.matches(
                Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
            )
        ) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.w(
                TAG,
                "Invalid MAC address format, got: selfMacAddress=$selfMacAddress, targetMacAddress=$targetMacAddress"
            )
            return false
        }
        Log.d(TAG, "Sending Add TiPi Device packet to $targetMacAddress")

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

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SMART_ROUTING,
            payload = buffer.array()
        )

        return sendPacket(packet)
    }

    fun sendRawGesturesConfig(
        singlePressCustomized: Boolean = false,
        doublePressCustomized: Boolean = false,
        triplePressCustomized: Boolean = false,
        longPressCustomized: Boolean = false
    ): Boolean {
        val value = (
            (if (singlePressCustomized) 0x01 else 0) or
            (if (doublePressCustomized) 0x02 else 0) or
            (if (triplePressCustomized) 0x04 else 0) or
            (if (longPressCustomized) 0x08 else 0)
        ).toByte()

        return sendControlCommand(
            ControlCommandIdentifier.RAW_GESTURES_CONFIG.value, value
        )
    }

    fun sendPhoneMediaEQ(eq: FloatArray, phone: Byte = 0x02.toByte(), media: Byte = 0x02.toByte()) {
        if (eq.size != 8) throw IllegalArgumentException("EQ must be 8 floats")
        val header = byteArrayOf(
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

        val payload = header + buffer.array()

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.HEADPHONE_ACCOMMODATION,
            payload = payload
        )

        sendPacket(packet)

        device.updateState {
            it.copy(
                headphoneAccommodation = eq.copyOf(),
                headphoneAccommodationEnabledForMedia = media == 0x01.toByte(),
                headphoneAccommodationEnabledForPhone = phone == 0x01.toByte()
            )
        }
    }

    fun sendCountryCode() {
        val payload = byteArrayOf(
            0x00, 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
        )

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SET_COUNTRY_CODE,
            payload = payload
        )

        sendPacket(packet)
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }
        Log.i(TAG, "disconnected")
        socket = null
        device.updateState {
            it.copy(
                battery = emptySet(),
                connectedDevices = emptyList(),
                controlStates = emptyMap(),
                aacpPackets = emptyList(),
                headTrackingState = BuddyState.INACTIVE,
                hrmState = BuddyState.INACTIVE,
                currentHeartRate = null
            )
        }
    }

    fun setCustomEq(customEq: CustomEq): Boolean {
        device.updateState {
            it.copy(
                customEq = customEq
            )
        }

        val payload = customEq.toAACPPayload()

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.CUSTOM_EQ,
            payload = payload
        )

        return sendPacket(packet)
    }

    fun requestMicrophoneStream(): Boolean {
        val payload = byteArrayOf(
            0x00, 0x00,
            0x09, 0x00, // length
            0x00, 0x01,
            0x82.toByte(), 0x00,
            0x00, 0x00,
            0x04, 0x96.toByte(),
            0x00
        )

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.MICROPHONE_STREAM,
            payload = payload
        )

        return sendPacket(packet)
    }

    fun endMicrophoneStream(): Boolean {
        val payload = byteArrayOf(
            0x00, 0x00,
            0x02, 0x00, // length
            0x03, 0x01
        )

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.MICROPHONE_STREAM,
            payload = payload
        )

        return sendPacket(packet)
    }

    fun parseCapabilitiesResponse(packet: ByteArray): Set<CapabilityEntry> {
        require(packet.size >= 7) { "Packet too short" }
        require(packet[4] == MessageOpcode.CAPABILITIES.value) {
            "Not a capabilities packet"
        }

        var offset = 6
        val capabilityCount = packet[offset++].toInt() and 0xFF

        val capabilities = mutableSetOf<CapabilityEntry>()

        repeat(capabilityCount) {
            if (offset >= packet.size) {
                throw IllegalArgumentException("Unexpected end of packet")
            }

            val capabilityId = packet[offset++]
            val capability = Capability.fromByte(capabilityId)
                ?: throw IllegalArgumentException(
                    "Unknown capability 0x%02X".format(capabilityId.toInt() and 0xFF)
                )

            if (offset + capability.valueSize > packet.size) {
                throw IllegalArgumentException("Truncated packet")
            }

            capabilities += CapabilityEntry(
                capability,
                packet.copyOfRange(offset, offset + capability.valueSize)
            )

            offset += capability.valueSize
        }

        for (capability in capabilities) {
            Log.d(
                TAG,
                "Capability: ${capability.capability.name} - ${capability.value.joinToString(" ")}"
            )
        }

        return capabilities
    }

    private fun handleSensorData(data: SensorDataWX) {
        if (data.hasCommand()) {
            when (data.command.service) {
                SensorServiceType.ACTIVITY, SensorServiceType.DEVMOTION6 -> {
                    val payload = data.command.payload.toByteArray()

                    if (payload.size != 58) {
                        Log.w(
                            TAG,
                            "Unexpected payload size for ACTIVITY/DEVMOTION6: ${payload.size}, payload: ${payload.toHexString()}"
                        )
                        return
                    }

                    fun i16(offset: Int): Int =
                        (payload[offset].toInt() and 0xFF) or
                            ((payload[offset + 1].toInt() and 0xFF) shl 8)
                                .let { value ->
                                    if (value and 0x8000 != 0) value - 0x10000 else value
                                }

                    if (device.state.value.headTrackingState != BuddyState.ACTIVE) {
                        device.updateState {
                            it.copy(
                                headTrackingState = BuddyState.ACTIVE
                            )
                        }
                    }

                    HeadTracking.addAccel(i16(device.settings.value.headGesturesVerticalOffset).toFloat(), i16(device.settings.value.headGesturesHorizontalOffset).toFloat())
                }

                SensorServiceType.HEARTRATE,
                SensorServiceType.HEARTRATEv2 -> {
                    val payload = data.command.payload.toByteArray()
                    val timestamp = Clock.System.now()
                    if (payload.size == 18) {
                        // unsigned: a signed read turns anything >= 128 bpm negative and drops it
                        val heartRate = payload[1].toInt() and 0xFF

                        // bit 0 of the last byte is set for the first few samples after the sensor starts (payload[2],
                        // which looks like a confidence value, is also very low then); those readings are unreliable,
                        // e.g. 169 bpm at rest, so skip them and stay in WAITING until the sensor settles
                        if (payload[17].toInt() and 0x01 != 0) {
                            Log.d(TAG, "skipping heart rate sample while the sensor is acquiring: ${payload.toHexString()}")
                            return
                        }

                        // same as healthconnect's datatype. 300 isn't possible anyway, but whatever
                        if (heartRate !in 1..300) {
                            Log.w(
                                TAG,
                                "Invalid heart rate value: $heartRate"
                            )
                            return
                        }

                        if (device.state.value.hrmState != BuddyState.ACTIVE) {
                            device.updateState {
                                it.copy(
                                    hrmState = BuddyState.ACTIVE
                                )
                            }
                        }

                        Log.i(
                            TAG,
                            "hr: $heartRate bpm, payload: ${payload.toHexString()}"
                        )

                        val heartRateSample = HeartRateSample(
                            bpm = heartRate,
                            timestamp = timestamp
                        )

                        device.updateState {
                            it.copy(
                                currentHeartRate = heartRateSample
                            )
                        }

                    } else {
                        Log.w(
                            TAG,
                            "Unexpected payload size for HEARTRATEv2: ${payload.size}, payload: ${payload.toHexString()}"
                        )
                    }
                }

                else -> {
                    val payload = data.command.payload.toByteArray()
                    Log.d(
                        TAG,
                        "Unhandled sensor command service: ${data.command.service}, payload: ${payload.toHexString()}"
                    )
                }
            }
        }
    }
}
