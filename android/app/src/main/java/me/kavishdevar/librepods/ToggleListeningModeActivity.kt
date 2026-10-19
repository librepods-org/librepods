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

package me.kavishdevar.librepods

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.data.NoiseControlMode
import me.kavishdevar.librepods.services.ServiceManager

// Headless target of the "Toggle listening mode" app shortcut. Switches between the two listening
// modes picked in app settings without opening the UI.
class ToggleListeningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = ServiceManager.getService()
        if (service == null || BluetoothConnectionManager.aacpSocket?.isConnected != true) {
            Toast.makeText(this, R.string.airpods_not_connected, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val (first, second) = getModes(getSharedPreferences("settings", MODE_PRIVATE))
        val nextMode = if (service.getANC() == first) second else first
        Log.d("ToggleListeningMode", "Switching listening mode to: $nextMode")
        service.aacpManager.sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
            nextMode
        )
        Toast.makeText(this, getModeLabel(nextMode), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun getModeLabel(mode: Int): Int {
        return when (mode) {
            NoiseControlMode.TRANSPARENCY.ordinal + 1 -> R.string.transparency
            NoiseControlMode.ADAPTIVE.ordinal + 1 -> R.string.adaptive
            else -> R.string.noise_cancellation
        }
    }

    companion object {
        const val PREF_KEY = "toggle_listening_modes"

        // Off is left out on purpose. AirPods refuse it unless "Off Listening Mode" is enabled, which
        // would leave the shortcut sending a mode that never applies.
        val SELECTABLE_MODES = listOf(
            NoiseControlMode.TRANSPARENCY,
            NoiseControlMode.ADAPTIVE,
            NoiseControlMode.NOISE_CANCELLATION
        )

        private val DEFAULT_MODES = listOf(
            NoiseControlMode.NOISE_CANCELLATION.ordinal + 1,
            NoiseControlMode.TRANSPARENCY.ordinal + 1
        )

        // The two listening modes the shortcut switches between, least recently picked first.
        fun getModes(sharedPreferences: SharedPreferences): List<Int> {
            val modes = sharedPreferences.getString(PREF_KEY, null)
                ?.split(",")
                ?.mapNotNull { it.toIntOrNull() }
                ?.filter { value -> SELECTABLE_MODES.any { it.ordinal + 1 == value } }
            return if (modes?.size == 2) modes else DEFAULT_MODES
        }
    }
}
