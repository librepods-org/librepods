/*
    LibrePods - AirPods liberated from Apple's ecosystem
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

package me.kavishdevar.librepods.presentation.overlays

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandWindowTest {
    @Test
    fun phoneWidthUsesScreenRatio() {
        assertEquals(PHONE_EXPECTED_WIDTH_PX, calculateIslandWindowWidth(PHONE_SCREEN_WIDTH_PX, DISPLAY_DENSITY))
    }

    @Test
    fun tabletWidthIsCapped() {
        assertEquals(TABLET_EXPECTED_WIDTH_PX, calculateIslandWindowWidth(TABLET_SCREEN_WIDTH_PX, DISPLAY_DENSITY))
    }

    private companion object {
        private const val DISPLAY_DENSITY = 3f
        private const val PHONE_SCREEN_WIDTH_PX = 1080
        private const val PHONE_EXPECTED_WIDTH_PX = 1026
        private const val TABLET_SCREEN_WIDTH_PX = 3392
        private const val TABLET_EXPECTED_WIDTH_PX = 1200
    }
}
