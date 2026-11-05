package me.kavishdevar.librepods.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryConsumerPlannerTest {
    @Test
    fun leftOnlyReadingDoesNotInventRightBudOrCase() {
        val plan = planBatteryPresentation(
            listOf(Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING))
        )

        assertEquals(BatteryReadingPresentation(level = 58, charging = false), plan.left)
        assertNull(plan.right)
        assertNull(plan.case)
        assertNull(plan.combinedBuds)
        assertEquals(58, plan.minimumBudLevel)
        assertEquals("L: 58%", plan.notificationText())
    }

    @Test
    fun rightOnlyReadingDoesNotInventLeftBudOrCase() {
        val plan = planBatteryPresentation(
            listOf(Battery(BatteryComponent.RIGHT, 76, BatteryStatus.CHARGING))
        )

        assertNull(plan.left)
        assertEquals(BatteryReadingPresentation(level = 76, charging = true), plan.right)
        assertNull(plan.case)
        assertNull(plan.combinedBuds)
        assertEquals(76, plan.minimumBudLevel)
        assertEquals("R: ⚡ 76%", plan.notificationText())
    }

    @Test
    fun emptyReadingsProduceEmptyPresentation() {
        val plan = planBatteryPresentation(emptyList())

        assertNull(plan.left)
        assertNull(plan.right)
        assertNull(plan.case)
        assertNull(plan.combinedBuds)
        assertNull(plan.minimumBudLevel)
        assertEquals("", plan.notificationText())
    }

    @Test
    fun missingCaseStaysAbsentWhilePresentBudsCanCombine() {
        val plan = planBatteryPresentation(
            listOf(
                Battery(BatteryComponent.LEFT, 58, BatteryStatus.NOT_CHARGING),
                Battery(BatteryComponent.RIGHT, 60, BatteryStatus.NOT_CHARGING),
            )
        )

        assertEquals(BatteryReadingPresentation(level = 58, charging = false), plan.combinedBuds)
        assertNull(plan.case)
        assertEquals("L: 58% R: 60%", plan.notificationText())
    }

    @Test
    fun validNonzeroDisconnectedReadingRemainsPresent() {
        val plan = planBatteryPresentation(
            listOf(Battery(BatteryComponent.LEFT, 57, BatteryStatus.DISCONNECTED))
        )

        assertEquals(BatteryReadingPresentation(level = 57, charging = false), plan.left)
        assertNull(plan.right)
        assertEquals(57, plan.minimumBudLevel)
        assertEquals("L: 57%", plan.notificationText())
    }

    @Test
    fun metadataPayloadsAreOnlyPlannedForPresentReadings() {
        val payloads = planBatteryMetadata(
            listOf(
                Battery(BatteryComponent.LEFT, 57, BatteryStatus.DISCONNECTED),
                Battery(BatteryComponent.RIGHT, 76, BatteryStatus.CHARGING),
            )
        )

        assertEquals(
            listOf(
                BatteryMetadataPayload(BatteryMetadataField.LEFT_LEVEL, "57"),
                BatteryMetadataPayload(BatteryMetadataField.LEFT_CHARGING, "0"),
                BatteryMetadataPayload(BatteryMetadataField.RIGHT_LEVEL, "76"),
                BatteryMetadataPayload(BatteryMetadataField.RIGHT_CHARGING, "1"),
            ),
            payloads,
        )
        assertFalse(payloads.any { it.value == "null" })
        assertEquals(emptyList<BatteryMetadataPayload>(), planBatteryMetadata(emptyList()))
    }
}
