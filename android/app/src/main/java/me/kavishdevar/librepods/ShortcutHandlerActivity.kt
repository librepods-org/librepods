/*
 * LibrePods - AirPods liberated from Apple's ecosystem
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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import me.kavishdevar.librepods.services.ServiceManager
import me.kavishdevar.librepods.utils.AACPManager
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Activity that handles Google Assistant shortcuts for LibrePods features.
 * This activity processes shortcut intents and communicates with the AirPodsService
 * to execute the requested commands.
 */
class ShortcutHandlerActivity : Activity() {
    
    companion object {
        private const val TAG = "ShortcutHandler"
        private const val ACTION_NOISE_CONTROL = "me.kavishdevar.librepods.SHORTCUT_NOISE_CONTROL"
        private const val ACTION_CONVERSATIONAL_AWARENESS = "me.kavishdevar.librepods.SHORTCUT_CONVERSATIONAL_AWARENESS"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "Handling shortcut intent: ${intent.action}")
        
        val service = ServiceManager.getService()
        if (service == null) {
            Log.w(TAG, "LibrePods service not running")
            showToast("LibrePods service not running. Please ensure the app is running.")
            finish()
            return
        }
        
        if (!isAirPodsConnected(service)) {
            Log.w(TAG, "AirPods not connected")
            showToast("AirPods not connected. Please connect your AirPods first.")
            finish()
            return
        }
        
        when (intent.action) {
            ACTION_NOISE_CONTROL -> {
                handleNoiseControlShortcut(intent, service)
            }
            ACTION_CONVERSATIONAL_AWARENESS -> {
                handleConversationalAwarenessShortcut(intent, service)
            }
            else -> {
                Log.w(TAG, "Unknown shortcut action: ${intent.action}")
                showToast("Unknown shortcut action")
            }
        }
        
        finish()
    }
    
    /**
     * Checks if AirPods are currently connected to the device.
     */
    private fun isAirPodsConnected(service: me.kavishdevar.librepods.services.AirPodsService): Boolean {
        // You might want to implement a proper connection check here
        // For now, we'll assume if the service is running, AirPods are connected
        return true
    }
    
    /**
     * Handles noise control mode shortcuts.
     */
    private fun handleNoiseControlShortcut(intent: Intent, service: me.kavishdevar.librepods.services.AirPodsService) {
        val mode = intent.getIntExtra("mode", -1)
        
        if (mode !in 1..4) {
            Log.e(TAG, "Invalid noise control mode: $mode")
            showToast("Invalid noise control mode")
            return
        }
        
        try {
            service.aacpManager.sendControlCommand(
                AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
                mode
            )
            
            val modeName = when (mode) {
                1 -> "Off"
                2 -> "Noise Cancellation"
                3 -> "Transparency"
                4 -> "Adaptive"
                else -> "Unknown"
            }
            
            showToast("Switched to $modeName mode")
            Log.d(TAG, "Successfully set noise control mode to $mode ($modeName)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set noise control mode", e)
            showToast("Failed to change noise control mode")
        }
    }
    
    /**
     * Handles conversational awareness shortcuts.
     */
    private fun handleConversationalAwarenessShortcut(intent: Intent, service: me.kavishdevar.librepods.services.AirPodsService) {
        val enabled = intent.getBooleanExtra("enabled", true)
        
        try {
            service.aacpManager.sendControlCommand(
                AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value,
                enabled
            )
            
            val status = if (enabled) "enabled" else "disabled"
            showToast("Conversational Awareness $status")
            Log.d(TAG, "Successfully set conversational awareness to $enabled")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set conversational awareness", e)
            showToast("Failed to change conversational awareness")
        }
    }
    
    /**
     * Shows a toast message to the user.
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}