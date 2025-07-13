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

class ShortcutHandlerActivity : Activity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d("ShortcutHandler", "Handling shortcut intent: ${intent.action}")
        
        val service = ServiceManager.getService()
        if (service == null) {
            Toast.makeText(this, "LibrePods service not running", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        when (intent.action) {
            "me.kavishdevar.librepods.SHORTCUT_NOISE_CONTROL" -> {
                handleNoiseControlShortcut(intent, service)
            }
            "me.kavishdevar.librepods.SHORTCUT_CONVERSATIONAL_AWARENESS" -> {
                handleConversationalAwarenessShortcut(intent, service)
            }
            else -> {
                Log.w("ShortcutHandler", "Unknown shortcut action: ${intent.action}")
                Toast.makeText(this, "Unknown shortcut action", Toast.LENGTH_SHORT).show()
            }
        }
        
        finish()
    }
    
    private fun handleNoiseControlShortcut(intent: Intent, service: me.kavishdevar.librepods.services.AirPodsService) {
        val mode = intent.getIntExtra("mode", -1)
        
        if (mode !in 1..4) {
            Log.e("ShortcutHandler", "Invalid noise control mode: $mode")
            Toast.makeText(this, "Invalid noise control mode", Toast.LENGTH_SHORT).show()
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
            
            Toast.makeText(this, "Switched to $modeName mode", Toast.LENGTH_SHORT).show()
            Log.d("ShortcutHandler", "Set noise control mode to $mode ($modeName)")
            
        } catch (e: Exception) {
            Log.e("ShortcutHandler", "Failed to set noise control mode", e)
            Toast.makeText(this, "Failed to change noise control mode", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun handleConversationalAwarenessShortcut(intent: Intent, service: me.kavishdevar.librepods.services.AirPodsService) {
        val enabled = intent.getBooleanExtra("enabled", true)
        
        try {
            service.aacpManager.sendControlCommand(
                AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value,
                enabled
            )
            
            val status = if (enabled) "enabled" else "disabled"
            Toast.makeText(this, "Conversational Awareness $status", Toast.LENGTH_SHORT).show()
            Log.d("ShortcutHandler", "Set conversational awareness to $enabled")
            
        } catch (e: Exception) {
            Log.e("ShortcutHandler", "Failed to set conversational awareness", e)
            Toast.makeText(this, "Failed to change conversational awareness", Toast.LENGTH_SHORT).show()
        }
    }
}