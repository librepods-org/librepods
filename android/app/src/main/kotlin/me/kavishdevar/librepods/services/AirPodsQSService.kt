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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.core.devices.NoiseControlMode
import kotlin.io.encoding.ExperimentalEncodingApi

class AirPodsQSService : TileService() {

    private lateinit var sharedPreferences: SharedPreferences
    private var currentAncMode: Int = NoiseControlMode.OFF.ordinal + 1
    private var isAirPodsConnected: Boolean = false

    private val ancStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AirPodsNotifications.ANC_DATA.action) {
                val newMode = intent.getIntExtra("data", NoiseControlMode.OFF.ordinal + 1)
                Log.d("AirPodsQSService", "Received ANC update: $newMode")
                currentAncMode = newMode
                updateTile()
            }
        }
    }

    private val availabilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AirPodsNotifications.AIRPODS_CONNECTED.action -> {
                    Log.d("AirPodsQSService", "Received AIRPODS_CONNECTED")
                    isAirPodsConnected = true
                    currentAncMode = 3
//                    currentAncMode = ServiceManager.getService()?.getANC() ?: (NoiseControlMode.OFF.ordinal + 1)
                    updateTile()
                }
                AirPodsNotifications.AIRPODS_DISCONNECTED.action -> {
                    Log.d("AirPodsQSService", "Received AIRPODS_DISCONNECTED")
                    isAirPodsConnected = false
                    updateTile()
                }
            }
        }
    }

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "off_listening_mode") {
            Log.d("AirPodsQSService", "Preference changed: $key")
            if (currentAncMode == NoiseControlMode.OFF.ordinal + 1 && !isOffModeEnabled()) {
                currentAncMode = NoiseControlMode.TRANSPARENCY.ordinal + 1
            }
            updateTile()
        }
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
    }

    @SuppressLint("InlinedApi", "UnspecifiedRegisterReceiverFlag")
    override fun onStartListening() {
        super.onStartListening()
        Log.d("AirPodsQSService", "onStartListening")

//        val service = ServiceManager.getService()
//        isAirPodsConnected =
//        currentAncMode = service?.getANC() ?: (NoiseControlMode.OFF.ordinal + 1)

        if (currentAncMode == NoiseControlMode.OFF.ordinal + 1 && !isOffModeEnabled()) {
             currentAncMode = NoiseControlMode.TRANSPARENCY.ordinal + 1
        }

        val ancIntentFilter = IntentFilter(AirPodsNotifications.ANC_DATA.action)
        val availabilityIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTED.action)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED.action)
        }

        try {
            registerReceiver(ancStatusReceiver, ancIntentFilter, RECEIVER_EXPORTED)
            registerReceiver(availabilityReceiver, availabilityIntentFilter, RECEIVER_EXPORTED)
            sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
            Log.d("AirPodsQSService", "Receivers registered")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error registering receivers: $e")
        }

        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d("AirPodsQSService", "onStopListening")
        try {
            unregisterReceiver(ancStatusReceiver)
            unregisterReceiver(availabilityReceiver)
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
            Log.d("AirPodsQSService", "Receivers unregistered")
        } catch (e: IllegalArgumentException) {
            Log.e("AirPodsQSService", "Receiver not registered or already unregistered: $e")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error unregistering receivers: $e")
        }
    }

    override fun onClick() {
        super.onClick()
        Log.d("AirPodsQSService", "onClick - Current state: $isAirPodsConnected, Current mode: $currentAncMode")
        if (!isAirPodsConnected) {
            Log.d("AirPodsQSService", "Tile clicked but AirPods not connected.")
            return
        }

        cycleAncMode()
    }

    private fun cycleAncMode() {
//        val service = ServiceManager.getService()
//        if (service == null) {
//            Log.d("AirPodsQSService", "Tile clicked (cycle mode) but service is null.")
//            return
//        }
//        val nextMode = getNextAncMode()
//        Log.d("AirPodsQSService", "Cycling ANC mode to: $nextMode")
//        service.aacpManager.sendControlCommand(
//            ControlCommandIdentifier.LISTENING_MODE.value,
//            nextMode
//        )
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        Log.d("AirPodsQSService", "updateTile - Connected: $isAirPodsConnected, Mode: $currentAncMode")

        val deviceName = sharedPreferences.getString("name", "AirPods") ?: "AirPods"

        if (isAirPodsConnected) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getModeLabel(currentAncMode)
            tile.subtitle = deviceName
            tile.icon = Icon.createWithResource(this, getModeIcon(currentAncMode))
        } else {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "AirPods"
            tile.subtitle = getString(R.string.disconnected)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_airpods)
        }

        try {
            tile.updateTile()
            Log.d("AirPodsQSService", "Tile updated successfully")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error updating tile: $e")
        }
    }

    private fun isOffModeEnabled(): Boolean {
        return sharedPreferences.getBoolean("off_listening_mode", true)
    }

    private fun getAvailableModes(): List<Int> {
        val modes = mutableListOf(
            NoiseControlMode.TRANSPARENCY.ordinal + 1,
            NoiseControlMode.ADAPTIVE.ordinal + 1,
            NoiseControlMode.NOISE_CANCELLATION.ordinal + 1
        )
        if (isOffModeEnabled()) {
            modes.add(0, NoiseControlMode.OFF.ordinal + 1)
        }
        return modes
    }

    private fun getNextAncMode(): Int {
        val availableModes = getAvailableModes()
        Log.d("AirPodsQSService", "availableModes: $availableModes, currentAncMode: $currentAncMode")
        val currentIndex = availableModes.indexOf(currentAncMode)
        val nextIndex = (currentIndex + 1) % availableModes.size
        Log.d("AirPodsQSService", "nextIndex: $nextIndex")
        return availableModes[nextIndex]
    }

    private fun getModeLabel(mode: Int): String {
        return when (mode) {
            NoiseControlMode.OFF.ordinal + 1 -> getString(R.string.off)
            NoiseControlMode.TRANSPARENCY.ordinal + 1 -> getString(R.string.transparency)
            NoiseControlMode.ADAPTIVE.ordinal + 1 -> getString(R.string.adaptive)
            NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> getString(R.string.noise_cancellation)
            else -> getString(R.string.unknown)
        }
    }

     private fun getModeIcon(mode: Int): Int {
         return when (mode) {
             NoiseControlMode.OFF.ordinal + 1 -> R.drawable.ic_noise_cancellation
             NoiseControlMode.TRANSPARENCY.ordinal + 1 -> R.drawable.ic_transparency
             NoiseControlMode.ADAPTIVE.ordinal + 1 -> R.drawable.ic_adaptive
             NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> R.drawable.ic_noise_cancellation
             else -> R.drawable.ic_airpods
         }
     }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d("AirPodsQSService", "Tile added")
    }
}
