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

package me.kavishdevar.librepods.bluetooth

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ATTManager"

enum class ATTHandles(val value: Int) {
    TRANSPARENCY(0x18),
    LOUD_SOUND_REDUCTION(0x1B),
    HEARING_AID(0x2A)
}

enum class ATTCCCDHandles(val value: Int) {
    TRANSPARENCY(ATTHandles.TRANSPARENCY.value + 1),
    //    LOUD_SOUND_REDUCTION(ATTHandles.LOUD_SOUND_REDUCTION.value + 1), // doesn't work
    HEARING_AID(ATTHandles.HEARING_AID.value + 1)
}

class ATTManagerv2 {
    val characteristicList = mutableMapOf<ATTHandles, ByteArray>()

    private val responseQueues = ConcurrentHashMap<Byte, LinkedBlockingQueue<ByteArray>>()

    private val readerRunning = AtomicBoolean(false)
    private var readerThread: Thread? = null

    private var onNotificationReceived: ((handle: Byte, value: ByteArray) -> Unit)? = null

    fun startReader() {
        if (readerRunning.getAndSet(true)) return

        readerThread = Thread {
            try {
                runReaderLoop()
            } catch (t: Throwable) {
                Log.e(TAG, "reader thread crashed: ${t.message}", t)
            } finally {
                readerRunning.set(false)
                Log.d(TAG, "reader thread stopped")
            }
        }.also { it.name = "ATT-Reader"; it.isDaemon = true; it.start() }
        Log.d(TAG, "reader started")
    }

    fun stopReader() {
        readerRunning.set(false)
        readerThread?.interrupt()
        readerThread = null
    }

    fun setOnNotificationReceived(listener: ((handle: Byte, value: ByteArray) -> Unit)?) {
        onNotificationReceived = listener
    }

    fun enableNotification(handle: ATTCCCDHandles) {
        writeCharacteristic(handle.value.toByte(), byteArrayOf(0x01))
    }

    fun getCharacteristic(handle: ATTHandles): ByteArray? {
        val storedValue = characteristicList[handle]
        return if (storedValue?.isNotEmpty() != true) {
            readCharacteristic(handle)
        } else storedValue
    }

    fun readCharacteristic(handle: ATTHandles, timeoutMillis: Long = 2000): ByteArray? {
        val socket = BluetoothConnectionManager.attSocket ?: return null
        try {
            val output = socket.outputStream
            val pdu = byteArrayOf(0x0A, handle.value.toByte(), 0x00)
            synchronized(output) {
                output.write(pdu)
                output.flush()
            }
            Log.d(TAG, "sending read request: ${pdu.joinToString(" ") { String.format("%02X", it) }}")

            val resp = waitForResponse(0x0B, timeoutMillis) ?: run {
                Log.e(TAG, "Timeout waiting for Read Response (0x0B) for handle ${handle.value}")
                return null
            }

            Log.d(TAG, "read response: ${resp.joinToString(" ") { String.format("%02X", it) }}")
            val value = resp.copyOfRange(1, resp.size)
            characteristicList[handle] = value
            return value
        } catch (e: Exception) {
            Log.e(TAG, "error reading characteristic: ${e.message}")
            return null
        }
    }

    fun writeCharacteristic(handle: ATTHandles, data: ByteArray, timeoutMillis: Long = 2000) {
        characteristicList[handle] = data
        writeCharacteristic(handle.value.toByte(), data, timeoutMillis)
    }

    fun writeCharacteristic(handle: Byte, data: ByteArray, timeoutMillis: Long = 2000) {
        val socket = BluetoothConnectionManager.attSocket ?: return
        try {
            val output = socket.outputStream
            val pdu = byteArrayOf(0x12, handle, 0x00) + data // 0x00 for LE
            synchronized(output) {
                output.write(pdu)
                output.flush()
            }
            Log.d(TAG, "sending write request: ${pdu.joinToString(" ") { String.format("%02X", it) }}")

            val resp = waitForResponse(0x13, timeoutMillis) ?: run {
                Log.e(TAG, "timeout waiting for response (0x13) for handle ${String.format("%02X", handle)}")
                return
            }

            Log.d(TAG, "write respose: ${resp.joinToString(" ") { String.format("%02X", it) }}")
        } catch (e: Exception) {
            Log.e(TAG, "error writing characteristic: ${e.message}")
        }
    }

    fun disconnected() {
        characteristicList.clear()
        stopReader()
        val socket = BluetoothConnectionManager.attSocket?: return
        try {
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "error closing socket: ${e.message}")
        }
        Log.d(TAG, "ATT disconnected")
    }

    private fun runReaderLoop() {
        val socket = BluetoothConnectionManager.attSocket ?: run {
            Log.w(TAG, "ATT socket not available. stopping reader")
            readerRunning.set(false)
            return
        }

        val input = socket.inputStream
        val buffer = ByteArray(512)

        while (readerRunning.get()) {
            try {
                val len = input.read(buffer)
                if (len == -1) {
                    Log.w(TAG, "ATT input stream ended")
                    break
                }
                val data = buffer.copyOfRange(0, len)
                if (data.isEmpty()) continue

                val opcode = data[0]
                Log.d(TAG, "pdu received ${data.joinToString(" ") { String.format("%02X", it) }}")

                val queue = responseQueues.computeIfAbsent(opcode) { LinkedBlockingQueue() }
                queue.offer(data)

                if (opcode == 0x1B.toByte()) {
                    if (data.size >= 3) {
                        val handle = data[1]
                        val value = if (data.size > 3) data.copyOfRange(3, data.size) else ByteArray(0)
                        Log.d(TAG, "notification/indication handle=0x${String.format("%02X", handle)} value=${value.toHexString()}")
                        try {
                            onNotificationReceived?.invoke(handle, value)
                        } catch (t: Throwable) {
                            Log.e(TAG, "onNotificationReceived threw: ${t.message}", t)
                        }
                    } else {
                        Log.w(TAG, "notification PDU too short: ${data.joinToString(" ") { String.format("%02X", it) }}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "error in reader loop: ${e.message}", e)
                break
            }
        }

        readerRunning.set(false)
    }

    private fun waitForResponse(opcode: Byte, timeoutMillis: Long): ByteArray? {
        val queue = responseQueues.computeIfAbsent(opcode) { LinkedBlockingQueue() }
        return try {
            queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
