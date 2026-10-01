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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import me.kavishdevar.librepods.utils.BluetoothCryptography
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.iterator
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Manager for Bluetooth Low Energy scanning operations specifically for AirPods
 */
@OptIn(ExperimentalEncodingApi::class)
class BLEManager(private val context: Context) {

    data class AirPodsStatus(
        val address: String,
        val lastSeen: Long = System.currentTimeMillis(),
        val paired: Boolean = false,
        val model: String = "Unknown",
        val leftBattery: Int? = null,
        val rightBattery: Int? = null,
        val caseBattery: Int? = null,
        val isLeftInEar: Boolean = false,
        val isRightInEar: Boolean = false,
        val isLeftCharging: Boolean = false,
        val isRightCharging: Boolean = false,
        val isCaseCharging: Boolean = false,
        val lidOpen: Boolean = false,
        val color: String = "Unknown",
        val connectionState: String = "Unknown"
    )

    fun getMostRecentStatus(): AirPodsStatus? {
        return deviceStatusMap.values.maxByOrNull { it.lastSeen }
    }

    interface AirPodsStatusListener {
        fun onDeviceStatusChanged(device: AirPodsStatus, previousStatus: AirPodsStatus?)
        fun onBroadcastFromNewAddress(device: AirPodsStatus)
        fun onLidStateChanged(lidOpen: Boolean)
        fun onEarStateChanged(device: AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean)
        fun onBatteryChanged(device: AirPodsStatus)
        fun onVerifiedRssi(rssi: Int)
        fun onScanError(errorCode: Int)
        fun onDeviceDisappeared()
    }

    private var mBluetoothLeScanner: BluetoothLeScanner? = null
    @Volatile private var mScanCallback: ScanCallback? = null
    private var airPodsStatusListener: AirPodsStatusListener? = null
    private val deviceStatusMap = mutableMapOf<String, AirPodsStatus>()
    private val verifiedAddresses = mutableSetOf<String>()
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var currentGlobalLidState: Boolean? = null
    private var lastBroadcastTime: Long = 0
    private val processedAddresses = mutableSetOf<String>()
    @Volatile private var finderScanMode = false
    @Volatile private var finderScanStartedAt = 0L
    @Volatile private var lastFinderVerifiedRssiAt = 0L
    @Volatile private var lastFinderScanRestartAt = 0L
    @Volatile private var scanGeneration = 0L

    private val lastValidCaseBatteryMap = mutableMapOf<String, Int>()
    private val modelNames = mapOf(
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro 2",
        0x2420 to "AirPods Pro 2 (USB-C)",
        0x0220 to "AirPods 1",
        0x0F20 to "AirPods 2",
        0x1320 to "AirPods 3",
        0x1920 to "AirPods 4",
        0x1B20 to "AirPods 4 (ANC)",
        0x0A20 to "AirPods Max",
        0x1F20 to "AirPods Max (USB-C)"
    )

    val colorNames = mapOf(
        0x00 to "White", 0x01 to "Black", 0x02 to "Red", 0x03 to "Blue",
        0x04 to "Pink", 0x05 to "Gray", 0x06 to "Silver", 0x07 to "Gold",
        0x08 to "Rose Gold", 0x09 to "Space Gray", 0x0A to "Dark Blue",
        0x0B to "Light Blue", 0x0C to "Yellow"
    )

    val connStates = mapOf(
        0x00 to "Disconnected", 0x04 to "Idle", 0x05 to "Music",
        0x06 to "Call", 0x07 to "Ringing", 0x09 to "Hanging Up", 0xFF to "Unknown"
    )

    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            cleanupStaleDevices()
            checkLidStateTimeout()
            cleanupHandler.postDelayed(this, CLEANUP_INTERVAL_MS)
        }
    }

    /**
     * OxygenOS occasionally leaves an unfiltered BLE scan registered but stops delivering its
     * callbacks. Finder mode is short-lived, so recover that state automatically instead of
     * forcing the user to leave and re-enter the screen.
     */
    private val finderScanWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!finderScanMode) return
            val now = SystemClock.elapsedRealtime()
            val lastSignal = lastFinderVerifiedRssiAt.takeIf { it > 0L } ?: finderScanStartedAt
            val stalled = now - lastSignal >= FINDER_SCAN_STALL_TIMEOUT_MS
            val canRestart = now - lastFinderScanRestartAt >= FINDER_SCAN_RESTART_COOLDOWN_MS
            if (stalled && canRestart) {
                lastFinderScanRestartAt = now
                Log.w(TAG, "Finder BLE scan stalled; restarting scanner")
                if (!startScanning(scanAllAdvertisementsForFinder = true)) {
                    airPodsStatusListener?.onScanError(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
                }
                return
            }
            cleanupHandler.postDelayed(this, FINDER_WATCHDOG_INTERVAL_MS)
        }
    }

    fun setAirPodsStatusListener(listener: AirPodsStatusListener) {
        airPodsStatusListener = listener
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startScanning(
        scanAllAdvertisementsForFinder: Boolean = false,
        resetFinderWatchdog: Boolean = false,
    ): Boolean {
        val generation = ++scanGeneration
        try {
            Log.d(TAG, "Starting BLE scanner")
            finderScanMode = scanAllAdvertisementsForFinder
            if (scanAllAdvertisementsForFinder) {
                finderScanStartedAt = SystemClock.elapsedRealtime()
                lastFinderVerifiedRssiAt = 0L
                if (resetFinderWatchdog) lastFinderScanRestartAt = 0L
            } else {
                cleanupHandler.removeCallbacks(finderScanWatchdogRunnable)
            }

            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val btAdapter = btManager.adapter

            if (btAdapter == null) {
                Log.d(TAG, "No Bluetooth adapter available")
                finderScanMode = false
                return false
            }

            if (mBluetoothLeScanner != null && mScanCallback != null) {
                mBluetoothLeScanner?.stopScan(mScanCallback)
                mScanCallback = null
            }

            if (!btAdapter.isEnabled) {
                Log.d(TAG, "Bluetooth is disabled")
                finderScanMode = false
                return false
            }

            mBluetoothLeScanner = btAdapter.bluetoothLeScanner
            val scanner = mBluetoothLeScanner
            if (scanner == null) {
                Log.d(TAG, "No Bluetooth LE scanner available")
                finderScanMode = false
                return false
            }

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                // Finder needs each RSSI sample immediately. Some vendor stacks (including
                // OxygenOS) fail to flush an unfiltered offloaded batch while the app is also
                // using BLE peripheral mode, leaving the UI on "Waiting for signal" forever.
                .setReportDelay(if (scanAllAdvertisementsForFinder) 0L else 500L)
                .build()

            val scanFilters = if (scanAllAdvertisementsForFinder) {
                // Some vendor Bluetooth stacks interpret an empty manufacturer-data filter as
                // "match an empty payload". Finder therefore uses a genuinely unfiltered
                // foreground scan and applies Apple company-ID + RPA ownership checks in-process.
                null
            } else {
                val manufacturerData = ByteArray(27).apply {
                    this[0] = 0x07
                    this[1] = 0x19
                }
                val manufacturerDataMask = ByteArray(27).apply {
                    this[0] = -1
                    this[1] = -1
                }
                listOf(
                    ScanFilter.Builder()
                        .setManufacturerData(76, manufacturerData, manufacturerDataMask)
                        .build()
                )
            }

            lateinit var callback: ScanCallback
            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (isCurrentScan(callback, generation)) processScanResult(result)
                }

                override fun onBatchScanResults(results: List<ScanResult>) {
                    if (!isCurrentScan(callback, generation)) return
                    processedAddresses.clear()
                    for (result in results) {
                        processScanResult(result)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    if (!isCurrentScan(callback, generation)) return
                    Log.e(TAG, "BLE scan failed with error code: $errorCode")
                    mScanCallback = null
                    finderScanMode = false
                    cleanupHandler.removeCallbacks(finderScanWatchdogRunnable)
                    airPodsStatusListener?.onScanError(errorCode)
                }
            }

            mScanCallback = callback
            processedAddresses.clear()
            scanner.startScan(scanFilters, scanSettings, callback)
            Log.d(TAG, "BLE scanner started successfully")

            cleanupHandler.removeCallbacks(cleanupRunnable)
            cleanupHandler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL_MS)
            if (scanAllAdvertisementsForFinder) {
                cleanupHandler.removeCallbacks(finderScanWatchdogRunnable)
                cleanupHandler.postDelayed(finderScanWatchdogRunnable, FINDER_WATCHDOG_INTERVAL_MS)
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Error starting BLE scanner", t)
            if (scanGeneration == generation) {
                mScanCallback = null
                finderScanMode = false
                cleanupHandler.removeCallbacks(finderScanWatchdogRunnable)
                if (scanAllAdvertisementsForFinder) {
                    airPodsStatusListener?.onScanError(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
                }
            }
            return false
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stopScanning(): Boolean {
        ++scanGeneration
        try {
            finderScanMode = false
            if (mBluetoothLeScanner != null && mScanCallback != null) {
                Log.d(TAG, "Stopping BLE scanner")
                mBluetoothLeScanner?.stopScan(mScanCallback)
                mScanCallback = null
            }

            cleanupHandler.removeCallbacks(cleanupRunnable)
            cleanupHandler.removeCallbacks(finderScanWatchdogRunnable)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping BLE scanner", t)
            return false
        }
    }

    private fun isCurrentScan(callback: ScanCallback, generation: Long): Boolean =
        scanGeneration == generation && mScanCallback === callback

    @OptIn(ExperimentalEncodingApi::class)
    private fun getEncryptionKeyFromPreferences(): ByteArray? {
        val keyBase64 = sharedPreferences.getString(AACPManager.Companion.ProximityKeyType.ENC_KEY.name, null)
        return if (keyBase64 != null) {
            try {
                Base64.decode(keyBase64)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode encryption key", e)
                null
            }
        } else {
            null
        }
    }

    @SuppressLint("GetInstance")
    private fun decryptLastBytes(data: ByteArray, key: ByteArray): ByteArray? {
        return try {
            if (data.size < 16) {
                return null
            }

            val block = data.copyOfRange(data.size - 16, data.size)
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            cipher.doFinal(block)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting data", e)
            null
        }
    }

    private fun formatBattery(byteVal: Int): Pair<Boolean, Int> {
        val charging = (byteVal and 0x80) != 0
        val level = byteVal and 0x7F
        return Pair(charging, level)
    }

    private fun processScanResult(result: ScanResult) {
        try {
            val scanRecord = result.scanRecord ?: return
            val address = result.device.address

            val manufacturerData = scanRecord.getManufacturerSpecificData(76) ?: return

            if (!verifiedAddresses.contains(address)) {
                val irk = getIrkFromPreferences()
                if (irk == null || !BluetoothCryptography.verifyRPA(address, irk)) {
                    return
                }
                verifiedAddresses.add(address)
                Log.d(TAG, "RPA verified and added to trusted list: $address")
            }

            // RSSI is useful on every verified advertisement. Keep the existing status-message
            // de-duplication below so finder updates do not increase unrelated status callbacks.
            if (finderScanMode) lastFinderVerifiedRssiAt = SystemClock.elapsedRealtime()
            airPodsStatusListener?.onVerifiedRssi(result.rssi)
            if (processedAddresses.contains(address)) return

            // RSSI finding can use any advertisement from the verified rotating address. The
            // battery/lid parser below is specific to Apple's legacy 0x07/0x19 payload.
            if (manufacturerData.size <= 20 ||
                manufacturerData[0] != 0x07.toByte() ||
                manufacturerData[1] != 0x19.toByte()
            ) return

            processedAddresses.add(address)
            lastBroadcastTime = System.currentTimeMillis()

            val encryptionKey = getEncryptionKeyFromPreferences()
            val decryptedData = if (encryptionKey != null) decryptLastBytes(manufacturerData, encryptionKey) else null
            val parsedStatus = if (decryptedData != null && decryptedData.size == 16) {
                parseProximityMessageWithDecryption(address, manufacturerData, decryptedData)
            } else {
                parseProximityMessage(address, manufacturerData)
            }

            val previousStatus = deviceStatusMap[address]
            deviceStatusMap[address] = parsedStatus

            airPodsStatusListener?.let { listener ->
                if (previousStatus == null) {
                    listener.onBroadcastFromNewAddress(parsedStatus)
                    Log.d(TAG, "New AirPods device detected: $address")

                    if (currentGlobalLidState == null || currentGlobalLidState != parsedStatus.lidOpen) {
                        currentGlobalLidState = parsedStatus.lidOpen
                        listener.onLidStateChanged(parsedStatus.lidOpen)
                        Log.d(TAG, "Lid state ${if (parsedStatus.lidOpen) "opened" else "closed"} (detected from new device)")
                    }
                } else {
                    if (parsedStatus != previousStatus) {
                        listener.onDeviceStatusChanged(parsedStatus, previousStatus)
                    }

                    if (parsedStatus.lidOpen != previousStatus.lidOpen) {
                        val previousGlobalState = currentGlobalLidState
                        currentGlobalLidState = parsedStatus.lidOpen

                        if (previousGlobalState != parsedStatus.lidOpen) {
                            listener.onLidStateChanged(parsedStatus.lidOpen)
                            Log.d(TAG, "Lid state changed from $previousGlobalState to ${parsedStatus.lidOpen}")
                        }
                    }

                    if (parsedStatus.isLeftInEar != previousStatus.isLeftInEar ||
                        parsedStatus.isRightInEar != previousStatus.isRightInEar) {
                        listener.onEarStateChanged(
                            parsedStatus,
                            parsedStatus.isLeftInEar,
                            parsedStatus.isRightInEar
                        )
                        Log.d(TAG, "Ear state changed - Left: ${parsedStatus.isLeftInEar}, Right: ${parsedStatus.isRightInEar}")
                    }

                    if (parsedStatus.leftBattery != previousStatus.leftBattery ||
                        parsedStatus.rightBattery != previousStatus.rightBattery ||
                        parsedStatus.caseBattery != previousStatus.caseBattery) {
                        listener.onBatteryChanged(parsedStatus)
                        Log.d(TAG, "Battery changed - Left: ${parsedStatus.leftBattery}, Right: ${parsedStatus.rightBattery}, Case: ${parsedStatus.caseBattery}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error processing scan result", t)
        }
    }

    private fun parseProximityMessageWithDecryption(address: String, data: ByteArray, decrypted: ByteArray): AirPodsStatus {
        val paired = data[2].toInt() == 1
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val model = modelNames[modelId] ?: "Unknown ($modelId)"

        val status = data[5].toInt() and 0xFF
//        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val color = colorNames[data[9].toInt()] ?: "Unknown"
        val conn = connStates[data[10].toInt()] ?: "Unknown (${data[10].toInt()})"

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft

        val leftByteIndex = if (isFlipped) 2 else 1
        val rightByteIndex = if (isFlipped) 1 else 2

        val (isLeftCharging, leftBattery) = formatBattery(decrypted[leftByteIndex].toInt() and 0xFF)
        val (isRightCharging, rightBattery) = formatBattery(decrypted[rightByteIndex].toInt() and 0xFF)

        val rawCaseBatteryByte = decrypted[3].toInt() and 0xFF
        val (isCaseCharging, rawCaseBattery) = formatBattery(rawCaseBatteryByte)

        val caseBattery = if (rawCaseBatteryByte == 0xFF || (isCaseCharging && rawCaseBattery == 127)) {
            lastValidCaseBatteryMap[address]
        } else {
            lastValidCaseBatteryMap[address] = rawCaseBattery
            rawCaseBattery
        }

        val lidOpen = ((lid shr 3) and 0x01) == 0

        return AirPodsStatus(
            address = address,
            lastSeen = System.currentTimeMillis(),
            paired = paired,
            model = model,
            leftBattery = leftBattery,
            rightBattery = rightBattery,
            caseBattery = caseBattery,
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            lidOpen = lidOpen,
            color = color,
            connectionState = conn
        )
    }

    private fun cleanupStaleDevices() {
        val now = System.currentTimeMillis()
        val staleCutoff = now - STALE_DEVICE_TIMEOUT_MS
        val hadDevices = deviceStatusMap.isNotEmpty()

        val staleDevices = deviceStatusMap.filter { it.value.lastSeen < staleCutoff }

        for (device in staleDevices) {
            deviceStatusMap.remove(device.key)
            Log.d(TAG, "Removed stale device from tracking: ${device.key}")
        }

        if (hadDevices && deviceStatusMap.isEmpty()) {
            airPodsStatusListener?.onDeviceDisappeared()
        }
    }

    private fun checkLidStateTimeout() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBroadcastTime > LID_CLOSE_TIMEOUT_MS && currentGlobalLidState == true) {
            Log.d(TAG, "No broadcasts for ${LID_CLOSE_TIMEOUT_MS}ms, forcing lid state to closed")
            currentGlobalLidState = false
            airPodsStatusListener?.onLidStateChanged(false)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun getIrkFromPreferences(): ByteArray? {
        val irkBase64 = sharedPreferences.getString(AACPManager.Companion.ProximityKeyType.IRK.name, null)
        return if (irkBase64 != null) {
            try {
                Base64.decode(irkBase64)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode IRK", e)
                null
            }
        } else {
            null
        }
    }

    private fun parseProximityMessage(address: String, data: ByteArray): AirPodsStatus {
        val paired = data[2].toInt() == 1
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val model = modelNames[modelId] ?: "Unknown ($modelId)"

        val status = data[5].toInt() and 0xFF
        val podsBattery = data[6].toInt() and 0xFF
        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val color = colorNames[data[9].toInt()] ?: "Unknown"
        val conn = connStates[data[10].toInt()] ?: "Unknown (${data[10].toInt()})"

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft

        val leftBatteryNibble = if (isFlipped) (podsBattery shr 4) and 0x0F else podsBattery and 0x0F
        val rightBatteryNibble = if (isFlipped) podsBattery and 0x0F else (podsBattery shr 4) and 0x0F

        val caseBattery = flagsCase and 0x0F
        val flags = (flagsCase shr 4) and 0x0F

        val isLeftCharging = if (isFlipped) (flags and 0x02) != 0 else (flags and 0x01) != 0
        val isRightCharging = if (isFlipped) (flags and 0x01) != 0 else (flags and 0x02) != 0
        val isCaseCharging = (flags and 0x04) != 0

        val lidOpen = ((lid shr 3) and 0x01) == 0

        fun decodeBattery(n: Int): Int? = when (n) {
            in 0x0..0x9 -> n * 10
            in 0xA..0xE -> 100
            0xF -> null
            else -> null
        }

        return AirPodsStatus(
            address = address,
            lastSeen = System.currentTimeMillis(),
            paired = paired,
            model = model,
            leftBattery = decodeBattery(leftBatteryNibble),
            rightBattery = decodeBattery(rightBatteryNibble),
            caseBattery = decodeBattery(caseBattery),
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            lidOpen = lidOpen,
            color = color,
            connectionState = conn
        )
    }

    companion object {
        private const val TAG = "AirPodsBLE"
        private const val FINDER_WATCHDOG_INTERVAL_MS = 3_000L
        private const val FINDER_SCAN_STALL_TIMEOUT_MS = 8_000L
        private const val FINDER_SCAN_RESTART_COOLDOWN_MS = 15_000L
        private const val CLEANUP_INTERVAL_MS = 10000L
        private const val STALE_DEVICE_TIMEOUT_MS = 15000L
        private const val LID_CLOSE_TIMEOUT_MS = 2500L
    }
}
