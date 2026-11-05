/*
 * LibrePods - AirPods liberated from Apple’s ecosystem
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

package me.kavishdevar.librepods.constants

import kotlin.math.abs

internal data class BatteryReadingPresentation(
    val level: Int,
    val charging: Boolean,
)

internal data class BatteryPresentationPlan(
    val left: BatteryReadingPresentation?,
    val right: BatteryReadingPresentation?,
    val case: BatteryReadingPresentation?,
) {
    val combinedBuds: BatteryReadingPresentation?
        get() {
            val leftReading = left ?: return null
            val rightReading = right ?: return null
            if (leftReading.charging != rightReading.charging ||
                abs(leftReading.level - rightReading.level) > 3
            ) {
                return null
            }
            return BatteryReadingPresentation(
                level = minOf(leftReading.level, rightReading.level),
                charging = leftReading.charging,
            )
        }

    val minimumBudLevel: Int?
        get() = listOfNotNull(left?.level, right?.level).minOrNull()

    fun notificationText(): String = listOfNotNull(
        left?.notificationPart("L"),
        right?.notificationPart("R"),
        case?.notificationPart("Case"),
    ).joinToString(" ")
}

private fun BatteryReadingPresentation.notificationPart(label: String): String =
    "$label: ${if (charging) "⚡ " else ""}$level%"

private fun Battery.toPresentation() = BatteryReadingPresentation(
    level = level,
    charging = status == BatteryStatus.CHARGING,
)

internal fun planBatteryPresentation(batteries: List<Battery>?): BatteryPresentationPlan {
    val byComponent = batteries.orEmpty().associateBy(Battery::component)
    return BatteryPresentationPlan(
        left = byComponent[BatteryComponent.LEFT]?.toPresentation(),
        right = byComponent[BatteryComponent.RIGHT]?.toPresentation(),
        case = byComponent[BatteryComponent.CASE]?.toPresentation(),
    )
}
