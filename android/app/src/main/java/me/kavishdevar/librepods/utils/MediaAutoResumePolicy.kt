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

internal class MediaAutoResumePolicy {
    var isResumeArmed = false
        private set

    fun handlePauseRequest(
        isMusicActive: Boolean,
        userPlayedMedia: Boolean,
        force: Boolean,
        armAutoResume: Boolean = true,
        isMusicActiveWhenArming: () -> Boolean = { isMusicActive },
    ): Boolean {
        if (!isMusicActive || (userPlayedMedia && !force)) {
            return false
        }

        if (armAutoResume) {
            isResumeArmed = if (force) isMusicActiveWhenArming() else true
        }
        return true
    }

    fun shouldDispatchPlay(force: Boolean = false): Boolean = isResumeArmed || force

    fun setResumeArmed(armed: Boolean) {
        isResumeArmed = armed
    }

    fun updateAfterPlayRequest(isMusicActive: Boolean) {
        if (!isMusicActive) {
            isResumeArmed = false
        }
    }

    fun onAacpSessionCleanup(endedActiveSession: Boolean) {
        if (endedActiveSession) {
            isResumeArmed = false
        }
    }
}
