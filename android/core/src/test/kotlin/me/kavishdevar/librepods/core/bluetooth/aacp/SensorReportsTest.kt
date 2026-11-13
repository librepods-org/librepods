package me.kavishdevar.librepods.core.bluetooth.aacp

import com.google.protobuf.ByteString
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorCommand
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SensorReportsTest {
    private fun word(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }

    /** Builds a descriptor dictionary in the layout the buds announce. */
    internal fun dictionary(
        name: String = "devmotion6", vendor: Long = 1452,
        descriptor: ByteArray = SensorDescriptorLayout.MOTION_DESCRIPTOR, maximum: Long = 191,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xd3.toByte(), 0, 0, 0, 4, 0, 0, 0x81.toByte()))
        fun item(kind: Int, count: Int, bytes: ByteArray) {
            out.write(word((kind shl 24) or count))
            out.write(bytes)
            while (out.size() % 4 != 0) out.write(0)
        }
        fun number(value: Long) = ByteArray(8) { (value ushr (it * 8)).toByte() }
        for ((key, kind, value) in listOf(
            Triple("VendorID", 4, number(vendor)),
            Triple(if (name == "HeartRate") "HeartRateService" else "AccessoryService", 9, (name + '\u0000').toByteArray()),
            Triple("MaxReportSize", 4, number(maximum)),
            Triple("ReportDescriptor", 10, descriptor),
        )) {
            val keyBytes = (key + '\u0000').toByteArray()
            item(9, keyBytes.size, keyBytes)
            item(kind, if (kind == 4) 64 else value.size, value)
        }
        return out.toByteArray()
    }

    private fun heartDictionary() =
        dictionary("HeartRate", descriptor = SensorDescriptorLayout.HEART_DESCRIPTOR, maximum = 601)

    private fun announcement(sequence: Int, service: Int, descriptor: ByteArray) = SensorDataWX.newBuilder()
        .setSeq(sequence).setLogType(3).addSensorDescriptor(
            SensorDescriptor.newBuilder().setServiceValue(service).setSensorDescriptor(ByteString.copyFrom(descriptor))
        ).build()

    private fun command(service: Int, bytes: ByteArray, log: Int = 3) = SensorDataWX.newBuilder()
        .setSeq(10).setLogType(log).setCommand(
            SensorCommand.newBuilder().setServiceValue(service).setPayload(ByteString.copyFrom(bytes))
        ).build()

    private fun report(confidence: Int = 180, bpm: Int = 72, tail: String = "100080") = ByteArray(18).apply {
        this[1] = bpm.toByte(); this[2] = confidence.toByte()
        tail.chunked(2).map { it.toInt(16).toByte() }.toByteArray().copyInto(this, 15)
    }

    @Test fun exactDescriptorDictionariesRejectUnknownVendorsNamesLayoutsAndEveryTruncation() {
        val motion = dictionary()
        assertEquals(SensorDescriptorLayout.Kind.MOTION, SensorDescriptorLayout.parse(motion))
        assertEquals(SensorDescriptorLayout.Kind.HEART_RATE, SensorDescriptorLayout.parse(heartDictionary()))
        for (length in motion.indices) assertNull(SensorDescriptorLayout.parse(motion.copyOf(length)))
        for (bad in listOf(
            dictionary(vendor = 1), dictionary(name = "SPL0"), dictionary(maximum = 190),
            dictionary(descriptor = SensorDescriptorLayout.HEART_DESCRIPTOR),
            dictionary(descriptor = SensorDescriptorLayout.MOTION_DESCRIPTOR.copyOf().also { it[0] = 0 }),
            motion + byteArrayOf(0), motion.copyOf().also { it[4] = 0xff.toByte() },
        )) {
            assertNull(SensorDescriptorLayout.parse(bad))
        }
    }

    @Test fun heartRateRequiresTheCurrentServiceConfidenceRangeAndCapturedStatusTail() {
        val parser = SensorReports()
        for (tail in listOf("100000", "100080", "200000", "208000", "200280", "208280")) {
            assertEquals(SensorReports.HeartRate(72, 180), parser.receive(command(20, report(tail = tail))).heartRate)
        }
        for (bad in listOf(
            command(20, report(confidence = 127)), command(20, report(bpm = 29)),
            command(20, report(bpm = 221)), command(20, report(tail = "010203")),
            command(20, report().copyOf(17)), command(20, report(), log = 2), command(14, report()),
        )) {
            assertNull(parser.receive(bad).heartRate)
        }
        parser.receive(announcement(3, 27, heartDictionary()))
        assertNull(parser.receive(command(20, report())).heartRate)
        assertEquals(72, parser.receive(command(27, report())).heartRate?.bpm)
    }

    @Test fun announcedServicesReplaceLegacyNumbersAndRestartRetiresThem() {
        val parser = SensorReports()
        for (service in SensorReports.LEGACY_SERVICES) {
            assertEquals(72, parser.receive(command(service, report())).heartRate?.bpm)
        }
        assertTrue(parser.receive(announcement(1, 27, heartDictionary())).mappingChanged)
        assertEquals(27, parser.heartRateService)
        assertEquals(72, parser.receive(command(27, report())).heartRate?.bpm)
        assertNull(parser.receive(command(19, report())).heartRate)

        // Unknown descriptors keep the mapping; a restart at seq 1 drops it.
        assertFalse(parser.receive(announcement(2, 31, dictionary(name = "SPL0"))).mappingChanged)
        assertEquals(27, parser.heartRateService)
        val restart = announcement(1, 31, dictionary(name = "SPL0"))
        assertTrue(parser.receive(restart).mappingChanged)
        assertNull(parser.heartRateService)
        // Once a heart descriptor was seen, legacy numbers stay off for this connection.
        for (service in listOf(19, 20, 84, 27)) assertNull(parser.receive(command(service, report())).heartRate)
        assertFalse(parser.receive(restart).mappingChanged)
        parser.receive(announcement(2, 30, heartDictionary()))
        assertEquals(72, parser.receive(command(30, report())).heartRate?.bpm)
    }

    @Test fun oneServiceCannotBeBothMotionAndHeartRate() {
        val parser = SensorReports()
        parser.receive(announcement(1, 27, dictionary()))
        assertEquals(27, parser.motionService)
        assertFalse(parser.receive(announcement(2, 27, heartDictionary())).mappingChanged)
        assertNull(parser.heartRateService)
        assertNull(parser.receive(command(27, report())).heartRate)
    }
}
