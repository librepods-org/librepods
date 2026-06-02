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

package me.kavishdevar.librepods.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.io.encoding.ExperimentalEncodingApi
import me.kavishdevar.librepods.services.AirPodsService

class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> try { context?.startForegroundService(
                Intent(
                    context,
                    AirPodsService::class.java
                )
            ) } catch (e: Exception) { e.printStackTrace() }
            Intent.ACTION_BOOT_COMPLETED -> try { context?.startForegroundService(
                Intent(
                    context,
                    AirPodsService::class.java
                )
            ) } catch (e: Exception) { e.printStackTrace() }
        }
    }
}