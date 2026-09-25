package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class HeartRateBlePeripheralStatus {
    DISABLED,
    STARTING,
    ADVERTISING,
    PERMISSION_REQUIRED,
    BLUETOOTH_OFF,
    UNSUPPORTED,
    ERROR
}

data class HeartRateBlePeripheralState(
    val enabled: Boolean = false,
    val status: HeartRateBlePeripheralStatus = HeartRateBlePeripheralStatus.DISABLED,
    val connectedDeviceCount: Int = 0,
    val subscribedDeviceCount: Int = 0,
    val lastError: String? = null
)

/**
 * Opt-in Bluetooth SIG Heart Rate Service peripheral.
 *
 * The caller is responsible for forwarding only validated samples. This class never reads the
 * AirPods transport directly and does not start heart-rate monitoring on its own.
 */
class HeartRateBlePeripheral(private val context: Context) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val lock = Any()
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()
    private val notificationInFlight = mutableSetOf<BluetoothDevice>()

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising = false
    private var starting = false
    private var requestedEnabled = false

    private val heartRateMeasurement = BluetoothGattCharacteristic(
        HEART_RATE_MEASUREMENT_UUID,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        0
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
    }

    private val bodySensorLocation = BluetoothGattCharacteristic(
        BODY_SENSOR_LOCATION_UUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    )

    private val heartRateService = BluetoothGattService(
        HEART_RATE_SERVICE_UUID,
        BluetoothGattService.SERVICE_TYPE_PRIMARY
    ).apply {
        addCharacteristic(heartRateMeasurement)
        addCharacteristic(bodySensorLocation)
    }

    private val _state = MutableStateFlow(HeartRateBlePeripheralState())
    val state: StateFlow<HeartRateBlePeripheralState> = _state

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            synchronized(lock) {
                if (!requestedEnabled) return
                starting = false
                advertising = true
                publishStateLocked(HeartRateBlePeripheralStatus.ADVERTISING, null)
            }
        }

        override fun onStartFailure(errorCode: Int) {
            synchronized(lock) {
                advertising = false
                publishStateLocked(
                    HeartRateBlePeripheralStatus.ERROR,
                    "BLE advertising failed: ${advertiseErrorName(errorCode)}"
                )
            }
            stopResources(keepRequestedEnabled = true)
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != HEART_RATE_SERVICE_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                synchronized(lock) {
                    publishStateLocked(
                        HeartRateBlePeripheralStatus.ERROR,
                        "Could not add Heart Rate Service (GATT status $status)"
                    )
                }
                stopResources(keepRequestedEnabled = true)
                return
            }
            startAdvertisingAfterServiceAdded()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            synchronized(lock) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> connectedDevices.add(device)
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connectedDevices.remove(device)
                        subscribedDevices.remove(device)
                        notificationInFlight.remove(device)
                    }
                }
                publishStateLocked()
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                BODY_SENSOR_LOCATION_UUID -> sendReadResponse(
                    device = device,
                    requestId = requestId,
                    offset = offset,
                    fullValue = byteArrayOf(BODY_SENSOR_LOCATION_EAR_LOBE)
                )
                else -> sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_READ_NOT_PERMITTED,
                    0,
                    null
                )
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                    0,
                    null
                )
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIGURATION_UUID ||
                descriptor.characteristic.uuid != HEART_RATE_MEASUREMENT_UUID
            ) {
                sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
                return
            }
            val value = synchronized(lock) {
                if (subscribedDevices.contains(device)) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
            }
            sendReadResponse(device, requestId, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val isHeartRateCccd = descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIGURATION_UUID &&
                descriptor.characteristic.uuid == HEART_RATE_MEASUREMENT_UUID
            val status = when {
                !isHeartRateCccd -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                    synchronized(lock) {
                        subscribedDevices.add(device)
                        publishStateLocked()
                    }
                    BluetoothGatt.GATT_SUCCESS
                }
                value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                    synchronized(lock) {
                        subscribedDevices.remove(device)
                        notificationInFlight.remove(device)
                        publishStateLocked()
                    }
                    BluetoothGatt.GATT_SUCCESS
                }
                else -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            }
            if (responseNeeded) sendResponse(device, requestId, status, 0, null)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(lock) {
                notificationInFlight.remove(device)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Heart-rate notification failed for ${device.address}: $status")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun setEnabled(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lock) {
            requestedEnabled = true
            if (advertising || starting || gattServer != null) {
                publishStateLocked(if (advertising) HeartRateBlePeripheralStatus.ADVERTISING else HeartRateBlePeripheralStatus.STARTING)
                return
            }
            starting = true
            publishStateLocked(HeartRateBlePeripheralStatus.STARTING, null)
        }

        val missingPermission = when {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED -> Manifest.permission.BLUETOOTH_CONNECT
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED -> Manifest.permission.BLUETOOTH_ADVERTISE
            else -> null
        }
        if (missingPermission != null) {
            synchronized(lock) {
                starting = false
                publishStateLocked(
                    HeartRateBlePeripheralStatus.PERMISSION_REQUIRED,
                    "Nearby devices permission is required for BLE heart-rate sharing"
                )
            }
            return
        }

        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            synchronized(lock) { starting = false; publishStateLocked(HeartRateBlePeripheralStatus.UNSUPPORTED, "Bluetooth is not supported") }
            return
        }
        if (!adapter.isEnabled) {
            synchronized(lock) { starting = false; publishStateLocked(HeartRateBlePeripheralStatus.BLUETOOTH_OFF, "Bluetooth is turned off") }
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            synchronized(lock) {
                starting = false
                publishStateLocked(
                    HeartRateBlePeripheralStatus.UNSUPPORTED,
                    "This Bluetooth chipset does not support LE advertising"
                )
            }
            return
        }
        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            synchronized(lock) {
                starting = false
                publishStateLocked(HeartRateBlePeripheralStatus.UNSUPPORTED, "BLE advertising is unavailable")
            }
            return
        }

        val server = try {
            bluetoothManager.openGattServer(context, gattCallback)
        } catch (_: SecurityException) {
            synchronized(lock) {
                starting = false
                publishStateLocked(
                    HeartRateBlePeripheralStatus.PERMISSION_REQUIRED,
                    "Nearby devices permission was revoked"
                )
            }
            return
        } catch (_: Throwable) {
            null
        }
        if (server == null) {
            synchronized(lock) {
                starting = false
                publishStateLocked(HeartRateBlePeripheralStatus.ERROR, "Could not open a local GATT server")
            }
            return
        }

        synchronized(lock) {
            advertiser = leAdvertiser
            gattServer = server
            publishStateLocked(HeartRateBlePeripheralStatus.STARTING, null)
        }
        val serviceAdded = try {
            server.addService(heartRateService)
        } catch (_: SecurityException) {
            synchronized(lock) {
                publishStateLocked(
                    HeartRateBlePeripheralStatus.PERMISSION_REQUIRED,
                    "Nearby devices permission was revoked"
                )
            }
            false
        } catch (_: Throwable) {
            false
        }
        if (!serviceAdded) {
            if (_state.value.status != HeartRateBlePeripheralStatus.PERMISSION_REQUIRED) {
                synchronized(lock) {
                    publishStateLocked(HeartRateBlePeripheralStatus.ERROR, "Could not register Heart Rate Service")
                }
            }
            stopResources(keepRequestedEnabled = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingAfterServiceAdded() {
        val localAdvertiser = synchronized(lock) {
            if (!requestedEnabled || advertising) return
            advertiser
        } ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
            .build()
        try {
            localAdvertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (securityException: SecurityException) {
            synchronized(lock) {
                publishStateLocked(HeartRateBlePeripheralStatus.PERMISSION_REQUIRED, "Bluetooth advertise permission was revoked")
            }
            stopResources(keepRequestedEnabled = true)
        } catch (t: Throwable) {
            synchronized(lock) {
                publishStateLocked(HeartRateBlePeripheralStatus.ERROR, t.message ?: "BLE advertising could not start")
            }
            stopResources(keepRequestedEnabled = true)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopResources(keepRequestedEnabled = false)
        synchronized(lock) {
            publishStateLocked(HeartRateBlePeripheralStatus.DISABLED, null)
        }
    }

    /** Called only with HeartRateMonitor's published/validated samples. */
    fun onValidatedSample(sample: HeartRateSample) {
        val value = HeartRateMeasurementEncoder.encodeBpm(sample.bpm)
        val targets = synchronized(lock) {
            if (!requestedEnabled || !advertising) return
            subscribedDevices.filterTo(mutableListOf()) { connectedDevices.contains(it) }
        }
        targets.forEach { sendNotification(it, value) }
    }

    @SuppressLint("MissingPermission")
    private fun sendNotification(device: BluetoothDevice, value: ByteArray) {
        val server = synchronized(lock) {
            if (!requestedEnabled || !subscribedDevices.contains(device)) return
            // Heart Rate Measurement is time-sensitive. Do not queue old BPM values while Android
            // is still sending a prior notification for this central.
            if (notificationInFlight.contains(device)) return
            gattServer
        } ?: return

        val result = try {
            server.notifyCharacteristicChanged(device, heartRateMeasurement, false, value)
        } catch (_: SecurityException) {
            synchronized(lock) {
                publishStateLocked(
                    HeartRateBlePeripheralStatus.PERMISSION_REQUIRED,
                    "Nearby devices permission was revoked"
                )
            }
            stopResources(keepRequestedEnabled = true)
            return
        } catch (_: Throwable) {
            BluetoothStatusCodes.ERROR_UNKNOWN
        }
        synchronized(lock) {
            if (result == BluetoothStatusCodes.SUCCESS) {
                notificationInFlight.add(device)
            } else {
                Log.w(TAG, "Could not queue heart-rate notification for ${device.address}: $result")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendReadResponse(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        fullValue: ByteArray
    ) {
        if (offset < 0 || offset > fullValue.size) {
            sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, 0, null)
            return
        }
        sendResponse(
            device,
            requestId,
            BluetoothGatt.GATT_SUCCESS,
            offset,
            fullValue.copyOfRange(offset, fullValue.size)
        )
    }

    @SuppressLint("MissingPermission")
    private fun sendResponse(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?
    ) {
        try {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not send GATT response", t)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopResources(keepRequestedEnabled: Boolean) {
        val localAdvertiser: BluetoothLeAdvertiser?
        val localServer: BluetoothGattServer?
        synchronized(lock) {
            requestedEnabled = keepRequestedEnabled
            starting = false
            advertising = false
            localAdvertiser = advertiser
            localServer = gattServer
            advertiser = null
            gattServer = null
            connectedDevices.clear()
            subscribedDevices.clear()
            notificationInFlight.clear()
        }
        try {
            localAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Throwable) {
        }
        try {
            localServer?.clearServices()
        } catch (_: Throwable) {
        }
        try {
            localServer?.close()
        } catch (_: Throwable) {
        }
    }

    private fun publishStateLocked(
        status: HeartRateBlePeripheralStatus = _state.value.status,
        error: String? = _state.value.lastError
    ) {
        _state.value = HeartRateBlePeripheralState(
            enabled = requestedEnabled,
            status = status,
            connectedDeviceCount = connectedDevices.size,
            subscribedDeviceCount = subscribedDevices.size,
            lastError = error
        )
    }

    private fun advertiseErrorName(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
        else -> "error $code"
    }

    companion object {
        private const val TAG = "HeartRateBlePeripheral"
        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val BODY_SENSOR_LOCATION_UUID: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val BODY_SENSOR_LOCATION_EAR_LOBE: Byte = 0x05
    }
}
