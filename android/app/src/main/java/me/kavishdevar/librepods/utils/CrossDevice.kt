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

package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.services.ServiceManager
import java.io.IOException
import java.util.UUID
import kotlin.io.encoding.ExperimentalEncodingApi

enum class CrossDevicePackets(val packet: ByteArray) {
    AIRPODS_CONNECTED(byteArrayOf(0x00, 0x01, 0x00, 0x01)),
    AIRPODS_DISCONNECTED(byteArrayOf(0x00, 0x01, 0x00, 0x00)),
    REQUEST_DISCONNECT(byteArrayOf(0x00, 0x02, 0x00, 0x00)),
    REQUEST_BATTERY_BYTES(byteArrayOf(0x00, 0x02, 0x00, 0x01)),
    REQUEST_ANC_BYTES(byteArrayOf(0x00, 0x02, 0x00, 0x02)),
    REQUEST_CONNECTION_STATUS(byteArrayOf(0x00, 0x02, 0x00, 0x03)),
    AIRPODS_DATA_HEADER(byteArrayOf(0x00, 0x04, 0x00, 0x01)),
}


object CrossDevice {
    var initialized = false
    private val uuid = UUID.fromString("1abbb9a4-10e4-4000-a75c-8953c5471342")
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeAdvertiser: BluetoothLeAdvertiser
    private const val MANUFACTURER_ID = 0x1234
    private const val MANUFACTURER_DATA = "ALN_AirPods"
    var isAvailable: Boolean = false // set to true when airpods are connected to another device
    var batteryBytes: ByteArray = byteArrayOf()
    var ancBytes: ByteArray = byteArrayOf()
    private lateinit var sharedPreferences: SharedPreferences
    private const val PACKET_LOG_KEY = "packet_log"
    private var earDetectionStatus = listOf(false, false)
    var disconnectionRequested = false

    @SuppressLint("MissingPermission")
    fun init(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d("CrossDevice", "Initializing CrossDevice")
            sharedPreferences = context.getSharedPreferences("packet_logs", Context.MODE_PRIVATE)
            sharedPreferences.edit { putBoolean("CrossDeviceIsAvailable", false)}
            this@CrossDevice.bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
            this@CrossDevice.bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
            // startAdvertising()
            startServer()
            initialized = true
        }
    }

    @SuppressLint("MissingPermission")
    private fun startServer() {
        CoroutineScope(Dispatchers.IO).launch {
            if (!bluetoothAdapter.isEnabled) return@launch
//            serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("ALNCrossDevice", uuid)
            Log.d("CrossDevice", "Server started")
            while (serverSocket != null) {
                if (!bluetoothAdapter.isEnabled) {
                    serverSocket?.close()
                    break
                }
                if (clientSocket != null) {
                    try {
                        clientSocket!!.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
                try {
                    val socket = serverSocket!!.accept()
                    handleClientConnection(socket)
                } catch (e: IOException) { }
            }
        }
    }

    @SuppressLint("MissingPermission", "unused")
    private fun startAdvertising() {
        CoroutineScope(Dispatchers.IO).launch {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addManufacturerData(MANUFACTURER_ID, MANUFACTURER_DATA.toByteArray())
                .addServiceUuid(ParcelUuid(uuid))
                .build()
            try {
                bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
            } catch (e: Exception) {
                Log.e("CrossDevice", "Failed to start BLE Advertising: ${e.message}")
            }
            Log.d("CrossDevice", "BLE Advertising started")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d("CrossDevice", "BLE Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("CrossDevice", "BLE Advertising failed with error code: $errorCode")
        }
    }

    fun setAirPodsConnected(connected: Boolean) {
        if (connected) {
            isAvailable = false
            sharedPreferences.edit { putBoolean("CrossDeviceIsAvailable", false)}
            clientSocket?.outputStream?.write(CrossDevicePackets.AIRPODS_CONNECTED.packet)
        } else {
            clientSocket?.outputStream?.write(CrossDevicePackets.AIRPODS_DISCONNECTED.packet)
            // Reset state variables
            isAvailable = true
        }
    }

    fun sendReceivedPacket(packet: ByteArray) {
        if (clientSocket == null || clientSocket!!.outputStream != null) {
            return
        }
        clientSocket?.outputStream?.write(CrossDevicePackets.AIRPODS_DATA_HEADER.packet + packet)
    }

    private fun logPacket(packet: ByteArray, source: String) {
        val packetHex = packet.joinToString(" ") { "%02X".format(it) }
        val logEntry = "$source: $packetHex"
        val logs = sharedPreferences.getStringSet(PACKET_LOG_KEY, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        logs.add(logEntry)
        sharedPreferences.edit { putStringSet(PACKET_LOG_KEY, logs)}
    }

    @SuppressLint("MissingPermission")
    private fun handleClientConnection(socket: BluetoothSocket) {
        Log.d("CrossDevice", "Client connected")
        notifyAirPodsConnectedRemotely(ServiceManager.getService()?.applicationContext!!)
        clientSocket = socket
        val inputStream = socket.inputStream
        val buffer = ByteArray(1024)
        var bytes: Int
        setAirPodsConnected(ServiceManager.getService()?.isConnectedLocally == true)
        while (true) {
            try {
                bytes = inputStream.read(buffer)
            } catch (e: IOException) {
                e.printStackTrace()
                notifyAirPodsDisconnectedRemotely(ServiceManager.getService()?.applicationContext!!)
                val s = serverSocket?.accept()
                if (s != null) {
                    handleClientConnection(s)
                }
                break
            }
            var packet = buffer.copyOf(bytes)
            logPacket(packet, "Relay")
            Log.d("CrossDevice", "Received packet: ${packet.joinToString("") { "%02x".format(it) }}")
            if (bytes == -1) {
                notifyAirPodsDisconnectedRemotely(ServiceManager.getService()?.applicationContext!!)
                break
            } else if (packet.contentEquals(CrossDevicePackets.REQUEST_DISCONNECT.packet) || packet.contentEquals(CrossDevicePackets.REQUEST_DISCONNECT.packet + CrossDevicePackets.AIRPODS_DATA_HEADER.packet)) {
                ServiceManager.getService()?.disconnectForCD()
                disconnectionRequested = true
                CoroutineScope(Dispatchers.IO).launch {
                    delay(1000)
                    disconnectionRequested = false
                }
            } else if (packet.contentEquals(CrossDevicePackets.AIRPODS_CONNECTED.packet)) {
                isAvailable = true
                sharedPreferences.edit { putBoolean("CrossDeviceIsAvailable", true)}
            } else if (packet.contentEquals(CrossDevicePackets.AIRPODS_DISCONNECTED.packet)) {
                isAvailable = false
                sharedPreferences.edit { putBoolean("CrossDeviceIsAvailable", false)}
            } else if (packet.contentEquals(CrossDevicePackets.REQUEST_BATTERY_BYTES.packet)) {
                Log.d("CrossDevice", "Received battery request, battery data: ${batteryBytes.joinToString("") { "%02x".format(it) }}")
                sendRemotePacket(batteryBytes)
            } else if (packet.contentEquals(CrossDevicePackets.REQUEST_ANC_BYTES.packet)) {
                Log.d("CrossDevice", "Received ANC request")
                sendRemotePacket(ancBytes)
            } else if (packet.contentEquals(CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet)) {
                Log.d("CrossDevice", "Received connection status request")
                sendRemotePacket(if (ServiceManager.getService()?.isConnectedLocally == true) CrossDevicePackets.AIRPODS_CONNECTED.packet else CrossDevicePackets.AIRPODS_DISCONNECTED.packet)
            } else {
                if (packet.sliceArray(0..3).contentEquals(CrossDevicePackets.AIRPODS_DATA_HEADER.packet)) {
                    isAvailable = true
                    sharedPreferences.edit { putBoolean("CrossDeviceIsAvailable", true) }
                    if (packet.size % 2 == 0) {
                        val half = packet.size / 2
                        if (packet.sliceArray(0 until half).contentEquals(packet.sliceArray(half until packet.size))) {
                            Log.d("CrossDevice", "Duplicated packet, trimming")
                            packet = packet.sliceArray(0 until half)
                        }
                    }
                    var trimmedPacket = packet.drop(CrossDevicePackets.AIRPODS_DATA_HEADER.packet.size).toByteArray()
                    Log.d("CrossDevice", "Received relayed packet: ${trimmedPacket.joinToString("") { "%02x".format(it) }}")
                    if (ServiceManager.getService()?.isConnectedLocally == true) {
                        val packetInHex = trimmedPacket.joinToString("") { "%02x".format(it) }
//                        ServiceManager.getService()?.sendPacket(packetInHex)
                    } else if (ServiceManager.getService()?.batteryNotification?.isBatteryData(trimmedPacket) == true) {
                        batteryBytes = trimmedPacket
                        ServiceManager.getService()?.batteryNotification?.setBattery(trimmedPacket)
                        Log.d("CrossDevice", "Battery data: ${ServiceManager.getService()?.batteryNotification?.getBattery()[0]?.level}")
                        ServiceManager.getService()?.updateBattery()
                        ServiceManager.getService()?.sendBatteryBroadcast()
                        ServiceManager.getService()?.sendBatteryNotification()
                    } else if (ServiceManager.getService()?.ancNotification?.isANCData(trimmedPacket) == true) {
                        ServiceManager.getService()?.ancNotification?.setStatus(trimmedPacket)
                        ServiceManager.getService()?.sendANCBroadcast()
                        ServiceManager.getService()?.updateNoiseControlWidget()
                        ancBytes = trimmedPacket
                    } else if (ServiceManager.getService()?.earDetectionNotification?.isEarDetectionData(trimmedPacket) == true) {
                        Log.d("CrossDevice", "Ear detection data: ${trimmedPacket.joinToString("") { "%02x".format(it) }}")
                        ServiceManager.getService()?.earDetectionNotification?.setStatus(trimmedPacket)
                        val newEarDetectionStatus = listOf(
                            ServiceManager.getService()?.earDetectionNotification?.status?.get(0) == 0x00.toByte(),
                            ServiceManager.getService()?.earDetectionNotification?.status?.get(1) == 0x00.toByte()
                        )
                        if (earDetectionStatus == listOf(false, false) && newEarDetectionStatus.contains(true)) {
                            ServiceManager.getService()?.applicationContext?.sendBroadcast(
                                Intent("me.kavishdevar.librepods.cross_device_island")
                            )
                        }
                        earDetectionStatus = newEarDetectionStatus
                    }
                }
            }
        }
    }

    fun sendRemotePacket(byteArray: ByteArray) {
        if (clientSocket == null || clientSocket!!.outputStream == null) {
            return
        }
        clientSocket?.outputStream?.write(byteArray)
        clientSocket?.outputStream?.flush()
        logPacket(byteArray, "Sent")
        Log.d("CrossDevice", "Sent packet to remote device")
    }

    fun notifyAirPodsConnectedRemotely(context: Context) {
        val intent = Intent("me.kavishdevar.librepods.AIRPODS_CONNECTED_REMOTELY")
        context.sendBroadcast(intent)
    }
    fun notifyAirPodsDisconnectedRemotely(context: Context) {
        val intent = Intent("me.kavishdevar.librepods.AIRPODS_DISCONNECTED_REMOTELY")
        context.sendBroadcast(intent)
    }
}
