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
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
        // null when the advertisement doesn't carry the lid state, e.g. because the advertising bud
        // isn't in the case
        val lidOpen: Boolean? = null,
        val color: String = "Unknown",
        val connectionState: String = "Unknown",
        // Whether the AirPods are connected to any device
        val isConnected: Boolean = false
    )

    fun getMostRecentStatus(): AirPodsStatus? {
        return latestStatus
    }

    interface AirPodsStatusListener {
        fun onDeviceStatusChanged(device: AirPodsStatus, previousStatus: AirPodsStatus?)
        fun onBroadcastFromNewAddress(device: AirPodsStatus)
        fun onLidStateChanged(lidOpen: Boolean)
        fun onEarStateChanged(device: AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean)
        fun onBatteryChanged(device: AirPodsStatus)
        fun onDeviceDisappeared()
    }

    private var mBluetoothLeScanner: BluetoothLeScanner? = null
    private var mScanCallback: ScanCallback? = null
    @Volatile private var mLidScanCallback: ScanCallback? = null
    private val lidScanActive: Boolean get() = mLidScanCallback != null
    // The lid scan keeps the radio listening all the time, so it stops while the screen is off,
    // when the popup can't show anyway
    @Volatile private var lidScanPaused = false
    @Volatile private var scanRequested = false
    @Volatile private var immediateDelivery = false
    @Volatile private var batchingSupported = true
    private var airPodsStatusListener: AirPodsStatusListener? = null
    // Last status passed to the listener for each address
    private val deviceStatusMap = mutableMapOf<String, AirPodsStatus>()
    private val pendingStatuses = LinkedHashMap<String, AirPodsStatus>()
    private var lastDispatchElapsed = 0L
    private var latestStatus: AirPodsStatus? = null
    private val verifiedAddresses = mutableSetOf<String>()
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val bluetoothAdapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    // Getting a cipher takes far longer than decrypting the one block each advertisement has
    private var decryptCipher: Cipher? = null
    private var decryptCipherKey: ByteArray? = null

    // Each bud advertises from its own rotating address, so the lid and case state, which only
    // a bud inside the case can report, are tracked across addresses.
    // When a bud in the case last reported the lid open, null once one reported it closed
    private var lidOpenReportNanos: Long? = null
    private var lastCaseReading: BatteryReading? = null
    private var lastCaseReadingNanos = 0L

    private data class BatteryReading(val level: Int, val charging: Boolean)

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
            cleanupHandler.postDelayed(this, CLEANUP_INTERVAL_MS)
        }
    }
    private val dispatchRunnable = Runnable {
        try {
            dispatchPendingStatuses()
        } catch (t: Throwable) {
            Log.e(TAG, "Error processing scan result", t)
        }
    }
    private val screenOffRunnable = Runnable {
        if (lidScanActive) {
            stopLidScan()
            lidScanPaused = true
        }
        if (immediateDelivery && !wantsImmediate()) startScan(immediate = false)
    }
    private val retryScanRunnable = Runnable {
        if (scanRequested) startScans()
    }

    private val scanStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    cleanupHandler.removeCallbacks(screenOffRunnable)
                    if (lidScanPaused) {
                        lidScanPaused = false
                        startLidScan()
                    }
                    if (!immediateDelivery && wantsImmediate()) startScan(immediate = true)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // Waiting first means quickly toggling the screen doesn't restart scans each
                    // time; Android refuses scans from apps that start more than 5 in 30 seconds
                    cleanupHandler.removeCallbacks(screenOffRunnable)
                    cleanupHandler.postDelayed(screenOffRunnable, SCREEN_OFF_DELAY_MS)
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    // Android drops every scan when Bluetooth turns off
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                        startScans()
                    }
                }
            }
        }
    }

    fun setAirPodsStatusListener(listener: AirPodsStatusListener) {
        airPodsStatusListener = listener
    }

    @Synchronized
    fun startScanning() {
        if (!scanRequested) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            context.registerReceiver(scanStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }
        scanRequested = true
        startScans()
        cleanupHandler.removeCallbacks(cleanupRunnable)
        cleanupHandler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL_MS)
    }

    @Synchronized
    private fun startScans() {
        lidScanPaused = !isScreenOn()
        if (lidScanPaused) stopLidScan() else startLidScan()
        startScan(immediate = wantsImmediate())
    }

    private fun isScreenOn(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive != false

    // The lid scan reports the lid opening right away, so status updates can come in batches,
    // which Android hands out every 5s or more. Without the lid scan, take each advertisement as
    // it arrives while the screen is on so the popup still shows up as soon as the lid opens, and
    // leave batching for when the screen is off, to save power.
    private fun wantsImmediate(): Boolean = !batchingSupported || (isScreenOn() && !lidScanActive)

    // Proximity pairing messages: Apple manufacturer data starting with type 0x07, length 25
    private fun proximityPairingFilter(): ScanFilter = ScanFilter.Builder()
        .setManufacturerData(76, byteArrayOf(0x07, 0x19), byteArrayOf(-1, -1))
        .build()

    // AirPods advertise inviting scan requests but don't answer them (AirPods 3 at least), and
    // Android holds such an advertisement until the answer arrives, so on many phones the status
    // scan only gets them in batches, which the controller stores itself. The controller's report
    // of the first advertisement from an address doesn't wait either, and as the AirPods use a
    // new address each time the lid opens, it comes right as it does. Only phones that filter in
    // hardware can run this scan.
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startLidScan() {
        try {
            stopLidScan()
            val btAdapter = bluetoothAdapter ?: return
            val scanner = btAdapter.bluetoothLeScanner
            if (scanner == null || !btAdapter.isEnabled || !btAdapter.isOffloadedFilteringSupported) {
                Log.d(TAG, "Lid scan unavailable")
                return
            }

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                // Each tracked address takes one of the few slots the controller shares between
                // apps. Other AirPods nearby can fill these; the popup then waits for the connection.
                .setNumOfMatches(ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT)
                .build()

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    processAppearance(result)
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "Lid scan failed with error code: $errorCode")
                    onLidScanFailed(this, errorCode)
                }
            }
            scanner.startScan(listOf(proximityPairingFilter()), scanSettings, callback)
            mLidScanCallback = callback
            Log.d(TAG, "Lid scan started")
        } catch (t: Throwable) {
            Log.e(TAG, "Error starting lid scan", t)
        }
    }

    // Android keeps a scan registered until it's stopped, even one that failed after starting
    @SuppressLint("MissingPermission")
    private fun stopLidScan() {
        val callback = mLidScanCallback ?: return
        mLidScanCallback = null
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping lid scan", t)
        }
    }

    @Synchronized
    private fun onLidScanFailed(callback: ScanCallback, errorCode: Int) {
        // A failure from a scan that has since been replaced doesn't matter
        if (callback != mLidScanCallback) return
        stopLidScan()
        if (!scanRequested) return
        retryIfThrottled(errorCode)
        if (!immediateDelivery && wantsImmediate()) startScan(immediate = true)
    }

    // Android refuses new scans from apps that start more than 5 within 30 seconds
    private fun retryIfThrottled(errorCode: Int) {
        if (errorCode != ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY) return
        cleanupHandler.removeCallbacks(retryScanRunnable)
        cleanupHandler.postDelayed(retryScanRunnable, SCAN_RETRY_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startScan(immediate: Boolean) {
        try {
            Log.d(TAG, "Starting BLE scanner")

            val btAdapter = bluetoothAdapter

            if (btAdapter == null) {
                Log.d(TAG, "No Bluetooth adapter available")
                return
            }

            if (mBluetoothLeScanner != null && mScanCallback != null) {
                mBluetoothLeScanner?.stopScan(mScanCallback)
                mScanCallback = null
            }

            if (!btAdapter.isEnabled) {
                Log.d(TAG, "Bluetooth is disabled")
                return
            }

            mBluetoothLeScanner = btAdapter.bluetoothLeScanner

            // Batching needs the controller to filter and store results by itself; without that,
            // Android rejects batched scans outright
            batchingSupported = btAdapter.isOffloadedFilteringSupported && btAdapter.isOffloadedScanBatchingSupported
            immediateDelivery = immediate || !batchingSupported

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(if (immediateDelivery) 0L else 500L)
                .build()

            mScanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    reportStatuses(trackLid(result))
                }

                // Batches can hold many advertisements per address, so go through all of them in
                // order to keep track of the lid. Lid changes found in them aren't reported; they're
                // seconds old and would open the popup long after the lid.
                override fun onBatchScanResults(results: List<ScanResult>) {
                    results.sortedBy { it.timestampNanos }.forEach { trackLid(it) }
                    reportStatuses(lidChange = null)
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "BLE scan failed with error code: $errorCode")
                    retryIfThrottled(errorCode)
                }
            }

            mBluetoothLeScanner?.startScan(listOf(proximityPairingFilter()), scanSettings, mScanCallback)
            Log.d(TAG, "BLE scanner started successfully (${if (immediateDelivery) "immediate" else "batched"} delivery)")
        } catch (t: Throwable) {
            Log.e(TAG, "Error starting BLE scanner", t)
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stopScanning() {
        if (scanRequested) context.unregisterReceiver(scanStateReceiver)
        scanRequested = false
        lidScanPaused = false
        cleanupHandler.removeCallbacks(screenOffRunnable)
        cleanupHandler.removeCallbacks(retryScanRunnable)
        cleanupHandler.removeCallbacks(dispatchRunnable)
        stopLidScan()
        try {
            if (mBluetoothLeScanner != null && mScanCallback != null) {
                Log.d(TAG, "Stopping BLE scanner")
                mBluetoothLeScanner?.stopScan(mScanCallback)
                mScanCallback = null
            }

            cleanupHandler.removeCallbacks(cleanupRunnable)
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping BLE scanner", t)
        }
    }

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

            val cipher = decryptCipher?.takeIf { key.contentEquals(decryptCipherKey) }
                ?: Cipher.getInstance("AES/ECB/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                    decryptCipher = this
                    decryptCipherKey = key
                }
            cipher.doFinal(data, data.size - 16, 16)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting data", e)
            null
        }
    }

    // Parses an advertisement and queues its status for the listener
    private fun record(result: ScanResult): AirPodsStatus? = try {
        parseScanResult(result)?.also {
            latestStatus = it
            pendingStatuses[it.address] = it
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Error processing scan result", t)
        null
    }

    private fun trackLid(result: ScanResult): Boolean? =
        record(result)?.lidOpen?.let { updateLidState(it, result.timestampNanos) }

    // The first advertisement seen from an address, from the lid scan. The AirPods switch to a new
    // address when the lid opens, before connecting to anything, so one from a bud in the case
    // with none in an ear and no connection means the lid was just opened. Its lid bit can still
    // read as closed. They switch addresses as the lid closes too, while still connected or with
    // no lid state in the advertisement.
    private fun processAppearance(result: ScanResult) {
        val status = record(result) ?: return
        val lidOpened = status.lidOpen != null && !status.isConnected &&
            !status.isLeftInEar && !status.isRightInEar
        if (lidOpened) updateLidState(true, result.timestampNanos)
        reportStatuses(lidChange = if (lidOpened) true else null)
    }

    // Lid changes are reported right away, but statuses at most every
    // STATUS_DISPATCH_INTERVAL_MS, and only the newest one per address.
    private fun reportStatuses(lidChange: Boolean?) {
        try {
            val sinceLastDispatch = SystemClock.elapsedRealtime() - lastDispatchElapsed
            if (sinceLastDispatch >= STATUS_DISPATCH_INTERVAL_MS) {
                dispatchPendingStatuses()
            } else if (pendingStatuses.isNotEmpty() && !cleanupHandler.hasCallbacks(dispatchRunnable)) {
                cleanupHandler.postDelayed(dispatchRunnable, STATUS_DISPATCH_INTERVAL_MS - sinceLastDispatch)
            }
            // Listeners can get the battery to show from getMostRecentStatus(), which is already up to date
            if (lidChange != null) {
                Log.d(TAG, "Lid ${if (lidChange) "opened" else "closed"}")
                airPodsStatusListener?.onLidStateChanged(lidChange)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error processing scan result", t)
        }
    }

    private fun dispatchPendingStatuses() {
        cleanupHandler.removeCallbacks(dispatchRunnable)
        lastDispatchElapsed = SystemClock.elapsedRealtime()
        val statuses = pendingStatuses.values.toList()
        pendingStatuses.clear()
        statuses.forEach { dispatchStatus(it) }
    }

    private fun parseScanResult(result: ScanResult): AirPodsStatus? {
        val scanRecord = result.scanRecord ?: return null
        val address = result.device.address

        val manufacturerData = scanRecord.getManufacturerSpecificData(76) ?: return null
        if (manufacturerData.size <= 20) return null

        if (!verifiedAddresses.contains(address)) {
            val irk = getIrkFromPreferences()
            if (irk == null || !BluetoothCryptography.verifyRPA(address, irk)) {
                return null
            }
            verifiedAddresses.add(address)
            Log.d(TAG, "RPA verified and added to trusted list: $address")
        }

        val encryptionKey = getEncryptionKeyFromPreferences()
        val decryptedData = if (encryptionKey != null) decryptLastBytes(manufacturerData, encryptionKey) else null
        return parseProximityMessage(
            address,
            manufacturerData,
            decryptedData?.takeIf { it.size == 16 },
            result.timestampNanos
        )
    }

    private fun dispatchStatus(status: AirPodsStatus) {
        val previousStatus = deviceStatusMap[status.address]
        deviceStatusMap[status.address] = status

        val listener = airPodsStatusListener ?: return
        if (previousStatus == null) {
            listener.onBroadcastFromNewAddress(status)
            Log.d(TAG, "New AirPods device detected: ${status.address}")
            return
        }

        if (status != previousStatus) {
            listener.onDeviceStatusChanged(status, previousStatus)
        }

        if (status.isLeftInEar != previousStatus.isLeftInEar ||
            status.isRightInEar != previousStatus.isRightInEar) {
            listener.onEarStateChanged(
                status,
                status.isLeftInEar,
                status.isRightInEar
            )
            Log.d(TAG, "Ear state changed - Left: ${status.isLeftInEar}, Right: ${status.isRightInEar}")
        }

        if (status.leftBattery != previousStatus.leftBattery ||
            status.rightBattery != previousStatus.rightBattery ||
            status.caseBattery != previousStatus.caseBattery) {
            listener.onBatteryChanged(status)
            Log.d(TAG, "Battery changed - Left: ${status.leftBattery}, Right: ${status.rightBattery}, Case: ${status.caseBattery}")
        }
    }

    // Returns the new lid state if this advertisement changed it
    private fun updateLidState(open: Boolean, timestampNanos: Long): Boolean? {
        // The buds go quiet soon after the lid shuts, so a long silence from inside the case means
        // it was closed in between, even if no advertisement saying so was caught.
        val wasOpen = lidOpenReportNanos?.let { timestampNanos - it <= IN_CASE_STATE_TIMEOUT_NANOS } == true
        lidOpenReportNanos = if (open) timestampNanos else null
        return if (open != wasOpen) open else null
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
        if ((latestStatus?.lastSeen ?: now) < staleCutoff) {
            latestStatus = null
        }

        if (hadDevices && deviceStatusMap.isEmpty()) {
            airPodsStatusListener?.onDeviceDisappeared()
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

    private fun parseProximityMessage(
        address: String,
        data: ByteArray,
        decrypted: ByteArray?,
        timestampNanos: Long
    ): AirPodsStatus {
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
        val bothInCase = ((status shr 2) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft

        val leftBatteryNibble = if (isFlipped) (podsBattery shr 4) and 0x0F else podsBattery and 0x0F
        val rightBatteryNibble = if (isFlipped) podsBattery and 0x0F else (podsBattery shr 4) and 0x0F

        val caseBatteryNibble = flagsCase and 0x0F
        val flags = (flagsCase shr 4) and 0x0F

        val isLeftCharging = if (isFlipped) (flags and 0x02) != 0 else (flags and 0x01) != 0
        val isRightCharging = if (isFlipped) (flags and 0x01) != 0 else (flags and 0x02) != 0
        val isCaseCharging = (flags and 0x04) != 0

        // The encrypted payload has exact levels; fall back to the public 10% steps for any
        // value it doesn't know
        val left = decrypted?.let { decodeBatteryByte(it[if (isFlipped) 2 else 1]) }
            ?: decodeBatteryNibble(leftBatteryNibble, isLeftCharging)
        val right = decrypted?.let { decodeBatteryByte(it[if (isFlipped) 1 else 2]) }
            ?: decodeBatteryNibble(rightBatteryNibble, isRightCharging)

        // Only a bud sitting in the case knows the case battery and lid state. One outside the
        // case advertises a stale lid byte that reads as open even while the case is shut.
        val reportsCase = thisInCase || bothInCase
        if (reportsCase) {
            val reading = decrypted?.let { decodeBatteryByte(it[3]) }
                ?: decodeBatteryNibble(caseBatteryNibble, isCaseCharging)
            if (reading != null) {
                lastCaseReading = reading
                lastCaseReadingNanos = timestampNanos
            }
        }
        // Advertisements from the bud in the case and the one outside it interleave, so keep
        // using a recent reading rather than flickering between it and nothing
        val case = lastCaseReading?.takeIf {
            timestampNanos - lastCaseReadingNanos <= IN_CASE_STATE_TIMEOUT_NANOS
        }

        // Bit 3 of the lid byte is set while the lid is closed. Advertisements that don't know the
        // lid state have 0 there, like the one the AirPods send from a new address as they
        // disconnect after the lid closes.
        val lidOpen = if (reportsCase && lid != 0) ((lid shr 3) and 0x01) == 0 else null

        return AirPodsStatus(
            address = address,
            lastSeen = System.currentTimeMillis(),
            paired = paired,
            model = model,
            leftBattery = left?.level,
            rightBattery = right?.level,
            caseBattery = case?.level,
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = left?.charging == true,
            isRightCharging = right?.charging == true,
            isCaseCharging = case?.charging == true,
            lidOpen = lidOpen,
            color = color,
            connectionState = conn,
            isConnected = data[10].toInt() != 0
        )
    }

    // Encrypted payload byte: bit 7 is charging, the rest the level. 0x7F means unknown.
    private fun decodeBatteryByte(byte: Byte): BatteryReading? {
        val value = byte.toInt() and 0xFF
        val level = value and 0x7F
        return if (level <= 100) BatteryReading(level, (value and 0x80) != 0) else null
    }

    // Public payload nibble: 0x0-0xA in 10% steps. 0xF means unknown.
    private fun decodeBatteryNibble(nibble: Int, charging: Boolean): BatteryReading? = when (nibble) {
        in 0x0..0x9 -> BatteryReading(nibble * 10, charging)
        in 0xA..0xE -> BatteryReading(100, charging)
        else -> null
    }

    companion object {
        private const val TAG = "AirPodsBLE"
        private const val CLEANUP_INTERVAL_MS = 10000L
        private const val STALE_DEVICE_TIMEOUT_MS = 15000L
        // Batched results arrive about every 5s. Keep that rate when results arrive one by one, as
        // listeners do a fair amount of work per status (even trying to take over the AirPods when
        // they're advertising as disconnected).
        private const val STATUS_DISPATCH_INTERVAL_MS = 5000L
        private const val SCREEN_OFF_DELAY_MS = 30000L
        // Android refuses new scans from apps that start more than 5 within 30 seconds
        private const val SCAN_RETRY_DELAY_MS = 30000L
        // How long what a bud in the case reported stays valid. Batched scans are only delivered
        // every ~5s and don't listen continuously, so this has to comfortably outlast those gaps.
        private const val IN_CASE_STATE_TIMEOUT_NANOS = 10_000_000_000L
    }
}
