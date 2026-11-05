/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 *
 * Copyright (C) 2025 LibrePods Contributors
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

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log

object BluetoothConnectionManager {
    private const val TAG = "BluetoothConnectionManager"

    private var currentSocket: BluetoothSocket? = null
    private var currentDevice: BluetoothDevice? = null
    private val expectedSocket = ThreadLocal<BluetoothSocket?>()

    fun <Result> withExpectedSocket(
        socket: BluetoothSocket,
        action: () -> Result,
    ): Result {
        val previousSocket = expectedSocket.get()
        expectedSocket.set(socket)
        return try {
            action()
        } finally {
            if (previousSocket == null) {
                expectedSocket.remove()
            } else {
                expectedSocket.set(previousSocket)
            }
        }
    }

    @Synchronized
    fun setCurrentConnection(socket: BluetoothSocket, device: BluetoothDevice) {
        currentSocket = socket
        currentDevice = device
        Log.d(TAG, "Current connection set to device: ${device.address}")
    }

    @Synchronized
    fun clearCurrentConnection(expectedSocket: BluetoothSocket): Boolean {
        if (currentSocket !== expectedSocket) return false

        currentSocket = null
        currentDevice = null
        Log.d(TAG, "Current connection cleared")
        return true
    }

    @Synchronized
    fun getCurrentSocket(): BluetoothSocket? {
        val scopedSocket = expectedSocket.get()
        return if (scopedSocket == null) {
            currentSocket
        } else {
            currentSocket?.takeIf { it === scopedSocket }
        }
    }
}
