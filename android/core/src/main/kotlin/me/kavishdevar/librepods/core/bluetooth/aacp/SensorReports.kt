package me.kavishdevar.librepods.core.bluetooth.aacp

import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX

/**
 * Maps device-announced sensor service numbers and decodes heart rate reports.
 *
 * Firmware announces its sensor services (RequestAllDescriptors reply). A service is only trusted
 * when its HID descriptor dictionary exactly matches a known layout. Once a heart rate descriptor
 * was seen, only that service number is accepted; before that, the legacy numbers 19/20/84 are.
 */
internal class SensorReports {
    data class HeartRate(val bpm: Int, val confidence: Int)
    data class Batch(val mappingChanged: Boolean, val heartRate: HeartRate?)

    var motionService: Int? = null
        private set
    var heartRateService: Int? = null
        private set
    private var heartDescriptorSeen = false
    private var lastDescriptorSequence: Int? = null

    fun receive(data: SensorDataWX): Batch = Batch(discover(data), heartRate(data))

    private fun discover(data: SensorDataWX): Boolean {
        if (data.logType !in setOf(1, 3) || data.sensorDescriptorCount !in 1..96) return false
        val announced = data.sensorDescriptorList.mapNotNull { descriptor ->
            val service = descriptor.serviceValue.takeIf { it in 1..127 } ?: return false
            SensorDescriptorLayout.parse(descriptor.sensorDescriptor.toByteArray())?.let { service to it }
        }
        // seq restarting at 1 means the buds re-announced from scratch: drop the old mapping.
        val restart = data.seq == 1 && (lastDescriptorSequence ?: 0) > 1
        if (announced.any { item -> announced.any {
                (item.first == it.first) != (item.second == it.second) } }) return false
        var motion = if (restart) null else motionService
        var heart = if (restart) null else heartRateService
        for ((service, kind) in announced) when (kind) {
            SensorDescriptorLayout.Kind.MOTION -> { if (heart == service) return false; motion = service }
            SensorDescriptorLayout.Kind.HEART_RATE -> { if (motion == service) return false; heart = service }
        }
        val changed = restart || motion != motionService || heart != heartRateService
        motionService = motion
        heartRateService = heart
        heartDescriptorSeen = heartDescriptorSeen || heart != null
        lastDescriptorSequence = data.seq
        return changed
    }

    private fun heartRate(data: SensorDataWX): HeartRate? {
        if (data.logType !in setOf(1, 3) || !data.hasCommand()) return null
        val service = data.command.serviceValue
        if (heartRateService != null) {
            if (service != heartRateService) return null
        } else if (heartDescriptorSeen || service !in LEGACY_SERVICES || service == motionService) return null
        val bytes = data.command.payload.toByteArray()
        if (bytes.size != 18 || bytes[1].toInt() and 255 !in 30..220 || bytes[2].toInt() and 255 < 0x80 ||
            HEART_TAILS.none { it.indices.all { index -> it[index] == bytes[15 + index] } }) return null
        return HeartRate(bytes[1].toInt() and 255, bytes[2].toInt() and 255)
    }

    companion object {
        /** HEARTRATE, HEARTRATEv2 and HEARTRATE_COMMAND before firmware announces its own numbers. */
        val LEGACY_SERVICES = setOf(19, 20, 84)
        private val HEART_TAILS = listOf("100000", "100080", "200000", "208000", "200280", "208280")
            .map { tail -> tail.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
    }
}
