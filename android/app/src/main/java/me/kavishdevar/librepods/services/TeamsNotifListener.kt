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

package me.kavishdevar.librepods.services

import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Watches the ongoing-call notification posted by Microsoft Teams (and a few
 * variants) and caches the action PendingIntents. AirPodsService can then call
 * [setMuted] to fire the right one — Teams reacts as if the user tapped the
 * Mute / Unmute button in the notification, which keeps its in-app UI in sync.
 *
 * Requires the user to grant Notification access (Settings → Apps → Special
 * access → Notification access). Use [isAccessGranted] / [openAccessSettings]
 * from UI to drive the grant flow.
 */
class TeamsNotifListener : NotificationListenerService() {

    companion object {
        private const val TAG = "TeamsNotifListener"

        private val TEAMS_PACKAGES = setOf(
            "com.microsoft.teams",
            "com.microsoft.teams.ipphone",
            "com.microsoft.teams2",
        )

        @Volatile private var muteAction: Notification.Action? = null
        @Volatile private var unmuteAction: Notification.Action? = null
        @Volatile private var lastSeenKey: String? = null

        fun isAccessGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val cn = "${context.packageName}/${TeamsNotifListener::class.java.name}"
            return flat.split(":").any { it == cn }
        }

        fun openAccessSettings(context: Context) {
            val intent = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun setMuted(muted: Boolean): Boolean {
            val action = if (muted) muteAction else unmuteAction
            if (action == null) {
                Log.d(TAG, "setMuted($muted): no cached action (muteAction=${muteAction != null}, unmuteAction=${unmuteAction != null})")
                return false
            }
            return try {
                action.actionIntent.send()
                Log.d(TAG, "setMuted($muted): fired ${action.title}")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "setMuted($muted) failed: ${t.message}")
                false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected")
        // Re-scan currently posted notifications so we pick up an in-progress call.
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "scan active notifications failed: ${t.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in TEAMS_PACKAGES) return
        if (sbn.key == lastSeenKey) {
            Log.d(TAG, "Call notification removed; clearing cached actions")
            muteAction = null
            unmuteAction = null
            lastSeenKey = null
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in TEAMS_PACKAGES) return
        val n = sbn.notification ?: return
        val actions = n.actions ?: return

        var foundMute: Notification.Action? = null
        var foundUnmute: Notification.Action? = null
        for (a in actions) {
            val title = a.title?.toString().orEmpty()
            val lower = title.lowercase()
            // Order matters: "unmute" contains "mute".
            if (lower.contains("unmute") || lower.contains("réactiver") || lower.contains("activar")) {
                foundUnmute = a
            } else if (lower.contains("mute") || lower.contains("muet") || lower.contains("silenc") || lower.contains("stumm")) {
                foundMute = a
            }
        }

        if (foundMute != null || foundUnmute != null) {
            muteAction = foundMute ?: muteAction
            unmuteAction = foundUnmute ?: unmuteAction
            lastSeenKey = sbn.key
            Log.d(
                TAG,
                "Cached actions from ${sbn.packageName}: mute=${foundMute?.title}, unmute=${foundUnmute?.title}, " +
                    "all=${actions.joinToString { it.title?.toString().orEmpty() }}"
            )
        }
    }
}
