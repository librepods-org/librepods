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

package me.kavishdevar.librepods.data.workout

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateZonesTest {
    @Test
    fun boundariesAreDeterministicPercentagesOfConfiguredMax() {
        val max = 200
        assertEquals(0, HeartRateZones.zoneIndex(99, max))
        assertEquals(1, HeartRateZones.zoneIndex(100, max))
        assertEquals(1, HeartRateZones.zoneIndex(119, max))
        assertEquals(2, HeartRateZones.zoneIndex(120, max))
        assertEquals(3, HeartRateZones.zoneIndex(140, max))
        assertEquals(4, HeartRateZones.zoneIndex(160, max))
        assertEquals(5, HeartRateZones.zoneIndex(180, max))
        assertEquals(5, HeartRateZones.zoneIndex(205, max))
    }
}
