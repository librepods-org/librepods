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

        policy.onAacpSessionCleanup(endedActiveSession = true)

        assertFalse(policy.shouldDispatchPlay())
    }

    @Test
    fun forcedPauseUsesLatestMusicActivityWhenArmingResume() {
        val policy = MediaAutoResumePolicy()

        assertTrue(
            policy.handlePauseRequest(
                isMusicActive = true,
                userPlayedMedia = false,
                force = true,
                isMusicActiveWhenArming = { false },
            )
        )

        assertFalse(policy.shouldDispatchPlay())
    }

    @Test
    fun failedOrStaleCleanupDoesNotClearReplacementResume() {
        val policy = MediaAutoResumePolicy()
        assertTrue(
            policy.handlePauseRequest(
                isMusicActive = true,
                userPlayedMedia = false,
                force = true,
            )
        )

        policy.onAacpSessionCleanup(endedActiveSession = false)

        assertTrue(policy.shouldDispatchPlay())
    }

    @Test
    fun teardownPauseCannotRearmResumeAfterSessionCleanup() {
        val policy = MediaAutoResumePolicy()
        assertTrue(
            policy.handlePauseRequest(
                isMusicActive = true,
                userPlayedMedia = false,
                force = true,
            )
        )
        policy.onAacpSessionCleanup(endedActiveSession = true)

        assertTrue(
            policy.handlePauseRequest(
                isMusicActive = true,
                userPlayedMedia = false,
                force = false,
                armAutoResume = false,
            )
        )

        assertFalse(policy.shouldDispatchPlay())
    }
}
