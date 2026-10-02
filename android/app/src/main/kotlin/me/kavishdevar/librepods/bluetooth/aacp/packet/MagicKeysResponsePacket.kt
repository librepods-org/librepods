package me.kavishdevar.librepods.bluetooth.aacp.packet

import android.util.Log
import me.kavishdevar.librepods.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.bluetooth.aacp.types.MessageOpcode
import me.kavishdevar.librepods.devices.PacketDestination

private const val TAG = "MagicKeyResponsePacket"

data class MagicKeyResponsePacket(
    val magicKeys: Map<MagicKeyType, ByteArray>,
    override val payload: ByteArray,
): AACPPacket {
    override val destination: PacketDestination = PacketDestination.HOST

    override val type: AACPPacketType = AACPPacketType.MESSAGE
    override val service: Byte = 0x04

    override val opcode = MessageOpcode.MAGIC_KEYS_RESPONSE

    companion object {
        fun parse(
            packet: ByteArray,
        ): MagicKeyResponsePacket {
            val payload = packet.copyOfRange(6, packet.size)
            val keyCount = payload[0].toInt()
            val keys = mutableMapOf<MagicKeyType, ByteArray>()
            var offset = 1
            for (i in 0 until keyCount) {
                Log.d(TAG, "Parsing Proximity Key $i")
                if (offset + 3 >= payload.size) {
                    throw IllegalArgumentException("Data array too short to parse Proximity Keys Response")
                }
                val keyType = payload[offset]
                val keyLength = payload[offset + 2].toInt()
                Log.d(TAG, "Key Type: ${keyType.toString(16)}, Key Length: $keyLength")
                offset += 4
                if (offset + keyLength > payload.size) {
                    throw IllegalArgumentException("Data array too short to parse Proximity Keys Response")
                }
                val key = ByteArray(keyLength)
                System.arraycopy(payload, offset, key, 0, keyLength)
                try {
                    keys[MagicKeyType.fromByte(keyType)] = key
                } catch (e: Exception) {
                    Log.e(TAG, "incorrect key type received: $keyType", e)
                }
                offset += keyLength
                // never log the key itself: the IRK/encryption key let anyone resolve and decrypt these AirPods' BLE
                // advertisements, and logs end up in bug reports
                Log.d(TAG, "Parsed Proximity Key: Type: ${keyType}, Length: $keyLength")
            }

            return MagicKeyResponsePacket(keys, payload)
        }
    }
}
