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

package me.kavishdevar.librepods.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAutoResumePolicyTest {
    @Test
    fun forcedEarDetectionPauseArmsResumeOnlyForCurrentAirPodsSession() {
        val policy = MediaAutoResumePolicy()

        assertTrue(
            policy.handlePauseRequest(
                isMusicActive = true,
                userPlayedMedia = true,
                force = true,
            )
        )
        assertTrue(policy.shouldDispatchPlay())

        policy.onAirPodsSessionDisconnected()

        assertFalse(policy.shouldDispatchPlay())
    }
}
