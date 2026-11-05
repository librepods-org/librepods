package me.kavishdevar.librepods.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryNotificationTest {
    private fun packet(vararg readings: Battery): ByteArray {
        val packet = mutableListOf<Byte>(
            0x04, 0x00, 0x04, 0x00, 0x04, 0x00, readings.size.toByte(),
        )
        readings.forEach { reading ->
            packet += reading.component.toByte()
            packet += 0x01
            packet += reading.level.toByte()
            packet += reading.status.toByte()
            packet += 0x01
        }
        return packet.toByteArray()
    }

    private fun readings(
        notification: AirPodsNotifications.BatteryNotification,
    ): Map<Int, Battery> = notification.getBattery().associateBy { it.component }

    @Test
    fun unknownLevelsDoNotReplaceLastKnownBatteryReadings() {
        val notification = AirPodsNotifications.BatteryNotification()
        notification.setBattery(
            packet(
                Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.RIGHT, 77, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.CASE, 86, BatteryStatus.NOT_CHARGING),
            )
        )

        notification.setBattery(
            packet(
                Battery(BatteryComponent.LEFT, 57, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.RIGHT, 127, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.CASE, 0, BatteryStatus.DISCONNECTED),
            )
        )

        val readings = readings(notification)
        assertEquals(57, readings.getValue(BatteryComponent.LEFT).level)
        assertEquals(77, readings.getValue(BatteryComponent.RIGHT).level)
        assertEquals(86, readings.getValue(BatteryComponent.CASE).level)
    }

    @Test
    fun rightFirstInitialPacketKeepsValidLeftWhenRightIsUnknown() {
        val notification = AirPodsNotifications.BatteryNotification()

        notification.setBattery(
            packet(
                Battery(BatteryComponent.RIGHT, 127, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING),
            )
        )

        assertEquals(
            listOf(Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING)),
            notification.getBattery(),
        )
    }

    @Test
    fun reorderedPartialPacketMergesByComponent() {
        val notification = AirPodsNotifications.BatteryNotification()
        notification.setBattery(
            packet(
                Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.RIGHT, 77, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.CASE, 86, BatteryStatus.NOT_CHARGING),
            )
        )

        notification.setBattery(
            packet(
                Battery(BatteryComponent.CASE, 84, BatteryStatus.CHARGING),
                Battery(BatteryComponent.RIGHT, 76, BatteryStatus.NOT_CHARGING),
            )
        )

        assertEquals(
            listOf(
                Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.RIGHT, 76, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.CASE, 84, BatteryStatus.CHARGING),
            ),
            notification.getBattery(),
        )
    }

    @Test
    fun sessionResetPreventsPreviousComponentsFromLeakingIntoPartialReconnect() {
        val notification = AirPodsNotifications.BatteryNotification()
        notification.setBattery(
            packet(
                Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.RIGHT, 77, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.CASE, 86, BatteryStatus.NOT_CHARGING),
            )
        )

        notification.clear()
        notification.setBattery(
            packet(Battery(BatteryComponent.LEFT, 57, BatteryStatus.NOT_CHARGING))
        )

        assertEquals(
            listOf(Battery(BatteryComponent.LEFT, 57, BatteryStatus.NOT_CHARGING)),
            notification.getBattery(),
        )
    }

    @Test
    fun nonzeroDisconnectedReadingIsRetained() {
        val notification = AirPodsNotifications.BatteryNotification()
        notification.setBattery(
            packet(Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING))
        )

        notification.setBattery(
            packet(Battery(BatteryComponent.LEFT, 57, BatteryStatus.DISCONNECTED))
        )

        assertEquals(
            Battery(BatteryComponent.LEFT, 57, BatteryStatus.DISCONNECTED),
            readings(notification).getValue(BatteryComponent.LEFT),
        )
    }

    @Test
    fun directUpdatesMergeOnlyPresentLevels() {
        val notification = AirPodsNotifications.BatteryNotification()

        notification.setBatteryDirect(
            leftLevel = 58,
            leftCharging = false,
            rightLevel = null,
            rightCharging = false,
            caseLevel = null,
            caseCharging = false,
        )
        notification.setBatteryDirect(
            leftLevel = null,
            leftCharging = false,
            rightLevel = 77,
            rightCharging = true,
            caseLevel = null,
            caseCharging = false,
        )

        assertEquals(
            listOf(
                Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.RIGHT, 77, BatteryStatus.CHARGING),
            ),
            notification.getBattery(),
        )
    }

    @Test
    fun partialPacketIsRecognizedFromDeclaredCount() {
        val notification = AirPodsNotifications.BatteryNotification()

        assertTrue(
            notification.isBatteryData(
                packet(Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING))
            )
        )
    }

    @Test
    fun malformedOrTruncatedPacketDoesNotMutateState() {
        val notification = AirPodsNotifications.BatteryNotification()
        notification.setBattery(
            packet(
                Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.RIGHT, 77, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.CASE, 86, BatteryStatus.NOT_CHARGING),
            )
        )
        val expected = notification.getBattery()
        val malformed = packet(
            Battery(BatteryComponent.LEFT, 20, BatteryStatus.CHARGING),
            Battery(BatteryComponent.RIGHT, 30, BatteryStatus.CHARGING),
            Battery(BatteryComponent.CASE, 40, BatteryStatus.CHARGING),
        ).also { it[8] = 0x00 }
        val complete = packet(
            Battery(BatteryComponent.LEFT, 20, BatteryStatus.CHARGING),
            Battery(BatteryComponent.RIGHT, 30, BatteryStatus.CHARGING),
        )
        val truncated = complete.copyOf(complete.size - 1)

        assertFalse(notification.isBatteryData(malformed))
        notification.setBattery(malformed)
        assertEquals(expected, notification.getBattery())

        assertFalse(notification.isBatteryData(truncated))
        notification.setBattery(truncated)
        assertEquals(expected, notification.getBattery())
    }

    @Test
    fun unknownComponentsAndStatusesDoNotCorruptKnownState() {
        val notification = AirPodsNotifications.BatteryNotification()
        notification.setBattery(
            packet(
                Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.RIGHT, 77, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.CASE, 86, BatteryStatus.NOT_CHARGING),
            )
        )

        notification.setBattery(
            packet(
                Battery(0x10, 90, BatteryStatus.CHARGING),
                Battery(BatteryComponent.LEFT, 20, 0x00),
                Battery(BatteryComponent.CASE, 84, BatteryStatus.CHARGING),
            )
        )

        assertEquals(
            listOf(
                Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.RIGHT, 77, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.CASE, 84, BatteryStatus.CHARGING),
            ),
            notification.getBattery(),
        )
    }
}
