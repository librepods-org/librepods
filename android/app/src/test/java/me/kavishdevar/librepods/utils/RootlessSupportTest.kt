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

package me.kavishdevar.librepods.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootlessSupportTest {
    @Test
    fun android16XiaomiFamilyDevicesAreSupported() {
        assertTrue(isRootlessSupported(ANDROID_16_SDK, XIAOMI_MANUFACTURER, GENERIC_ANDROID_16_BUILD_ID, false))
        assertTrue(isRootlessSupported(ANDROID_16_SDK, POCO_MANUFACTURER, GENERIC_ANDROID_16_BUILD_ID, false))
        assertTrue(isRootlessSupported(ANDROID_16_SDK, REDMI_MANUFACTURER, GENERIC_ANDROID_16_BUILD_ID, false))
    }

    @Test
    fun android16OppoFamilyDevicesRemainSupported() {
        assertTrue(isRootlessSupported(ANDROID_16_SDK, ONEPLUS_MANUFACTURER, GENERIC_ANDROID_16_BUILD_ID, false))
        assertTrue(isRootlessSupported(ANDROID_16_SDK, OPPO_MANUFACTURER, GENERIC_ANDROID_16_BUILD_ID, false))
        assertTrue(isRootlessSupported(ANDROID_16_SDK, REALME_MANUFACTURER, GENERIC_ANDROID_16_BUILD_ID, false))
    }

    @Test
    fun pixelAndroid16RequiresMarchUpdateBuildPrefix() {
        assertTrue(isRootlessSupported(ANDROID_16_SDK, GOOGLE_MANUFACTURER, SUPPORTED_PIXEL_ANDROID_16_BUILD_ID, false))
        assertFalse(isRootlessSupported(ANDROID_16_SDK, GOOGLE_MANUFACTURER, GENERIC_ANDROID_16_BUILD_ID, false))
    }

    @Test
    fun bypassFlagSupportsOtherwiseUnsupportedDevices() {
        assertTrue(isRootlessSupported(ANDROID_16_SDK, UNSUPPORTED_MANUFACTURER, GENERIC_ANDROID_16_BUILD_ID, true))
    }

    @Test
    fun android17AndLaterAreSupported() {
        assertTrue(isRootlessSupported(ANDROID_17_SDK, UNSUPPORTED_MANUFACTURER, GENERIC_ANDROID_16_BUILD_ID, false))
    }

    private companion object {
        private const val GENERIC_ANDROID_16_BUILD_ID = "BP2A.250605.031"
        private const val SUPPORTED_PIXEL_ANDROID_16_BUILD_ID = "CP1A.250305.019"
        private const val UNSUPPORTED_MANUFACTURER = "samsung"
    }
}
