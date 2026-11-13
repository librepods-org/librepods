package me.kavishdevar.librepods.core.bluetooth.aacp

import com.google.protobuf.ByteString
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RTBuddyPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorCommand
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.bluetooth.aacp.types.SensorDataWxBuddyPayload
import me.kavishdevar.librepods.core.data.apple.BuddyState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class HeartRateSessionTest {
    private data class Setting(val service: Int, val report: Int, val value: Int, val logType: Int?)

    private class Wire {
        val raw = CopyOnWriteArrayList<ByteArray>()
        val settings = CopyOnWriteArrayList<Setting>()
        val states = CopyOnWriteArrayList<BuddyState>()
        val samples = CopyOnWriteArrayList<SensorReports.HeartRate>()
        var descriptorRequests = 0
        var acceptRaw = true
        var current = true
        val settingsSent = CountDownLatch(2)
        val inactive = CountDownLatch(1)

        fun session() = HeartRateSession(
            sendSetting = { s, r, v, l -> settings += Setting(s, r, v, l); settingsSent.countDown(); true },
            requestAllDescriptors = { descriptorRequests++; true },
            sendRaw = { raw += it; acceptRaw },
            isCurrent = { current },
            interval = { 1.seconds },
            onHeartRate = { samples += it },
            onState = { states += it; if (it == BuddyState.INACTIVE) inactive.countDown() },
        )
    }

    private fun sample(service: Int) = SensorDataWX.newBuilder().setSeq(3).setLogType(3).setCommand(
        SensorCommand.newBuilder().setServiceValue(service).setPayload(ByteString.copyFrom(ByteArray(18).apply {
            this[1] = 64; this[2] = 0xc0.toByte(); this[15] = 0x20
        }))
    ).build()

    @Test fun startOpensServicesTurnsHrmOnAndConfiguresLegacyServices() {
        val wire = Wire()
        wire.session().use { session ->
            assertTrue(session.setEnabled(true))
            assertEquals(BuddyState.WAITING, wire.states.last())
            assertTrue(wire.settingsSent.await(5, TimeUnit.SECONDS))
            val expectedRaw = HeartRateSession.OPEN_SERVICES.map { it.first } + listOf(HeartRateSession.HRM_ON)
            assertEquals(expectedRaw.size, wire.raw.size)
            expectedRaw.zip(wire.raw).forEach { (want, got) -> assertArrayEquals(want, got) }
            assertEquals(listOf(Setting(84, 1, 1_000_000, 2), Setting(19, 1, 1_000_000, 2)), wire.settings.toList())

            session.receive(sample(19))
            assertEquals(BuddyState.ACTIVE, wire.states.last())
            assertEquals(64, wire.samples.single().bpm)

            wire.raw.clear(); wire.settings.clear()
            assertTrue(session.setEnabled(false))
            assertEquals(listOf(Setting(84, 1, 0, 2), Setting(19, 1, 0, 2)), wire.settings.toList())
            assertArrayEquals(HeartRateSession.HRM_OFF, wire.raw.single())
            assertEquals(BuddyState.INACTIVE, wire.states.last())
        }
    }

    @Test fun samplesAreIgnoredWhileNotRequested() {
        val wire = Wire()
        wire.session().use { session ->
            session.receive(sample(19))
            assertTrue(wire.samples.isEmpty())
            assertTrue(wire.states.isEmpty())
        }
    }

    @Test fun failedInitializationLeavesWaiting() {
        val wire = Wire().apply { acceptRaw = false }
        wire.session().use { session ->
            assertTrue(session.setEnabled(true))
            assertTrue("failed start must end in INACTIVE", wire.inactive.await(2, TimeUnit.SECONDS))
            assertTrue("no start settings after a failed open", wire.settings.all { it.value == 0 })
        }
    }

    @Test fun descriptorsAreRequestedOncePerConnectionAndStaleSessionsStaySilent() {
        val wire = Wire()
        val session = wire.session()
        session.requestDescriptors(); session.requestDescriptors()
        assertEquals(1, wire.descriptorRequests)
        wire.current = false
        assertFalse(session.setEnabled(true))
        session.close()
        assertTrue(wire.raw.isEmpty())
    }

    @Test fun framesSurviveEveryReadBoundaryAndSkipOversizedLengths() {
        val record = RTBuddyPacket.create(SensorDataWxBuddyPayload(sample(19))).rawPacket
        for (split in 1 until record.size) {
            val frames = SensorFrames()
            assertTrue(frames.feed(record.copyOfRange(0, split)).isEmpty())
            assertArrayEquals(record, frames.feed(record.copyOfRange(split, record.size)).single())
        }
        val frames = SensorFrames()
        val oversized = record.copyOf(12).apply { this[10] = 0xff.toByte(); this[11] = 0xff.toByte() }
        val output = frames.feed(byteArrayOf(4, 0, 4) + oversized + record + record)
        assertEquals(2, output.size)
        output.forEach { assertArrayEquals(record, it) }
        assertTrue(frames.feed(ByteArray(65_536)).isEmpty())
        assertArrayEquals(record, frames.feed(record).single())
    }
}
