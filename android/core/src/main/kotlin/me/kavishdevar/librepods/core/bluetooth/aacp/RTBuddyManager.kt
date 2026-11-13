package me.kavishdevar.librepods.core.bluetooth.aacp

import android.util.Log
import com.google.protobuf.ByteString
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RTBuddyPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.RequestAllDescriptors
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorServiceSetting
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorServiceType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.SensorDataWxBuddyPayload
import me.kavishdevar.librepods.core.devices.PacketDestination
import kotlin.time.Duration

class RTBuddyManager(
    private val sendPacket: (AACPPacket) -> Boolean,
) {
    companion object {
        private const val TAG = "RTBuddyManager"
    }

    private var sequence = 0

    private fun nextSequence(): Int {
        val next = sequence
        sequence = (sequence + 1) and 0x7FFFFFFF
        return next
    }

    private fun observeSequence(data: SensorDataWX) {
        Log.d(TAG, "Received SensorDataWX seq=${data.seq}")
    }

    fun handlePacket(packet: RTBuddyPacket) {
        when (val payload = packet.rtBuddyPayload) {
            is SensorDataWxBuddyPayload -> {
                val data = payload.data
                observeSequence(data)
            }

            else -> {
                Log.d(
                    TAG,
                    "Unhandled RTBuddy payload: ${payload.descriptor}"
                )
            }
        }
    }

    fun sendSensorData(
        data: SensorDataWX,
    ): Boolean {
        val dataWithSequence = data.toBuilder()
            .setSeq(nextSequence())
            .build()

        val packet = RTBuddyPacket.create(
            rtBuddyPayload = SensorDataWxBuddyPayload(
                data = dataWithSequence,
            ),
            destination = PacketDestination.DEVICE,
        )

        return sendPacket(packet)
    }

    private fun sendSensorServiceSetting(
        service: SensorServiceType,
        configuration: ByteArray,
    ): Boolean {
        val data = SensorDataWX.newBuilder()
            .setServiceSettings(
                SensorServiceSetting.newBuilder()
                    .setService(service)
                    .setSetting(2)
                    .setConfiguration(
                        ByteString.copyFrom(configuration)
                    )
            )
            .build()

        return sendSensorData(data)
    }

    /** Ask the buds to announce every sensor service with its HID descriptor. */
    fun requestAllDescriptors(): Boolean = sendSensorData(
        SensorDataWX.newBuilder()
            .setRequestAllDescriptors(RequestAllDescriptors.getDefaultInstance())
            .build()
    )

    /**
     * Service setting addressed by raw service number, for numbers the buds announced themselves.
     * Configuration is `[report][u32 LE value]`; heart rate uses log type 2.
     */
    fun sendServiceSetting(service: Int, report: Int, value: Int, logType: Int? = null): Boolean {
        if (service !in 1..127 || value < 0) return false
        val setting = SensorServiceSetting.newBuilder()
            .setServiceValue(service)
            .setSetting(2)
            .setConfiguration(
                ByteString.copyFrom(byteArrayOf(report.toByte()) + ByteArray(4) { (value ushr (it * 8)).toByte() })
            )
        val data = SensorDataWX.newBuilder().setServiceSettings(setting)
        if (logType != null) data.setLogType(logType)
        return sendSensorData(data.build())
    }

    fun setSensorServiceReportInterval(sensorServiceType: SensorServiceType, interval: Duration): Boolean = sendSensorServiceSetting(
        service = sensorServiceType,
        configuration = byteArrayOf(0x01) + interval.toMicrosUINT32LE()
    )
}

private fun Duration.toMicrosUINT32LE(): ByteArray {
    val microseconds = this.inWholeMicroseconds
    return byteArrayOf(
        microseconds.toByte(),
        (microseconds shr 8).toByte(),
        (microseconds shr 16).toByte(),
        (microseconds shr 24).toByte()
    )
}
