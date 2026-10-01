package me.kavishdevar.librepods.finder

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class NearbyFinderStatus {
    STOPPED,
    WAITING_FOR_SIGNAL,
    ACTIVE,
    PERMISSION_REQUIRED,
    BLUETOOTH_OFF,
    NO_SELECTED_DEVICE,
    ERROR
}

data class NearbyFinderState(
    val running: Boolean = false,
    val status: NearbyFinderStatus = NearbyFinderStatus.STOPPED,
    val signal: FinderSignalSnapshot = FinderSignalSnapshot(),
    val errorMessage: String? = null
)

/**
 * Finder coordinator. It deliberately does not own a scanner: LibrePods already performs a
 * verified, AirPods-specific BLE scan, so this consumes those RSSI callbacks only while active.
 */
class NearbyAirPodsFinder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hasSelectedDevice: () -> Boolean,
    private val processor: RssiSignalProcessor = RssiSignalProcessor()
) {
    private val _state = MutableStateFlow(NearbyFinderState())
    val state: StateFlow<NearbyFinderState> = _state
    private var tickerJob: Job? = null

    fun start(): Boolean {
        if (_state.value.running) return true
        when {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED -> {
                _state.value = NearbyFinderState(status = NearbyFinderStatus.PERMISSION_REQUIRED)
                return false
            }
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED -> {
                _state.value = NearbyFinderState(status = NearbyFinderStatus.PERMISSION_REQUIRED)
                return false
            }
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> {
                _state.value = NearbyFinderState(status = NearbyFinderStatus.PERMISSION_REQUIRED)
                return false
            }
            context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled != true -> {
                _state.value = NearbyFinderState(status = NearbyFinderStatus.BLUETOOTH_OFF)
                return false
            }
            !hasSelectedDevice() -> {
                _state.value = NearbyFinderState(status = NearbyFinderStatus.NO_SELECTED_DEVICE)
                return false
            }
        }

        processor.reset()
        _state.value = NearbyFinderState(
            running = true,
            status = NearbyFinderStatus.WAITING_FOR_SIGNAL,
            signal = processor.snapshot(SystemClock.elapsedRealtime())
        )
        tickerJob = scope.launch {
            while (true) {
                delay(250L)
                val snapshot = processor.snapshot(SystemClock.elapsedRealtime())
                val previous = _state.value
                if (!previous.running) return@launch
                _state.value = previous.copy(
                    status = if (snapshot.proximity == ProximityBucket.SIGNAL_LOST) {
                        NearbyFinderStatus.WAITING_FOR_SIGNAL
                    } else {
                        NearbyFinderStatus.ACTIVE
                    },
                    signal = snapshot
                )
            }
        }
        return true
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        processor.reset()
        _state.value = NearbyFinderState()
    }

    fun onVerifiedScanRssi(rssi: Int) {
        if (!_state.value.running) return
        val snapshot = processor.addSample(
            rssi = rssi,
            elapsedRealtime = SystemClock.elapsedRealtime()
        )
        // Publish the first fix immediately, then let the 250 ms ticker pace UI changes. Updating
        // Compose for every BLE advertisement makes normal radio noise look like rapid movement.
        if (_state.value.signal.rawRssi == null) {
            _state.value = _state.value.copy(
                status = NearbyFinderStatus.ACTIVE,
                signal = snapshot,
                errorMessage = null
            )
        }
    }

    fun onScanError(errorCode: Int) {
        val previous = _state.value
        if (!previous.running) return
        tickerJob?.cancel()
        tickerJob = null
        _state.value = previous.copy(
            running = false,
            status = NearbyFinderStatus.ERROR,
            signal = processor.snapshot(SystemClock.elapsedRealtime()),
            errorMessage = "AirPods BLE scan failed (code $errorCode)."
        )
    }

    fun refreshPrerequisites(): Boolean {
        if (_state.value.status == NearbyFinderStatus.PERMISSION_REQUIRED ||
            _state.value.status == NearbyFinderStatus.BLUETOOTH_OFF ||
            _state.value.status == NearbyFinderStatus.NO_SELECTED_DEVICE
        ) {
            return start()
        }
        return false
    }
}
