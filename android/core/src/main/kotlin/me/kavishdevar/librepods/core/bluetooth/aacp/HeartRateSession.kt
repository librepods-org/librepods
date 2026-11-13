package me.kavishdevar.librepods.core.bluetooth.aacp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.data.apple.BuddyState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Heart rate streaming for one AACP connection.
 *
 * Start: open services 0 and 4, send HRM_STATE on (control 0x30 = 1), then a SensorDataWX
 * service setting (log type 2, report 1, interval) to HEARTRATE_COMMAND and HEARTRATE. When the
 * buds announced their own heart rate service, that number is used instead of the legacy ones.
 * If no valid sample arrives for 12 s the stream is stopped and started once more before giving up.
 * Stop: interval 0 to both services, then HRM_STATE off (control 0x30 = 2).
 */
internal class HeartRateSession(
    private val sendSetting: (service: Int, report: Int, value: Int, logType: Int?) -> Boolean,
    private val requestAllDescriptors: () -> Boolean,
    private val sendRaw: (ByteArray) -> Boolean,
    private val isCurrent: () -> Boolean,
    private val interval: () -> Duration,
    private val onHeartRate: (SensorReports.HeartRate) -> Unit,
    private val onState: (BuddyState) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reports = SensorReports()
    private var descriptorsRequested = false
    private var wanted = false
    private var closed = false
    private var lastSample = 0L
    private var job: Job? = null
    private var epoch = 0L

    /** Ask the buds which sensor services they offer; sent once per connection. */
    @Synchronized
    fun requestDescriptors() {
        if (closed || descriptorsRequested || !isCurrent()) return
        descriptorsRequested = requestAllDescriptors()
    }

    @Synchronized
    fun receive(data: SensorDataWX) {
        if (closed || !isCurrent()) return
        val batch = reports.receive(data)
        if (batch.mappingChanged) {
            Log.i(TAG, "sensor services: heart=${reports.heartRateService} motion=${reports.motionService}")
            lastSample = 0
            if (wanted) {
                // The old number may now belong to another sensor; restart on the announced one.
                onState(BuddyState.WAITING)
                startCampaign()
            }
        }
        val sample = batch.heartRate ?: return
        if (!wanted) return
        lastSample = now()
        onState(BuddyState.ACTIVE)
        onHeartRate(sample)
    }

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        if (closed || !isCurrent()) return false
        wanted = enabled
        job?.cancel()
        job = null
        if (enabled) {
            onState(BuddyState.WAITING)
            startCampaign()
        } else {
            epoch++
            stopWire()
            onState(BuddyState.INACTIVE)
        }
        return true
    }

    private fun services(): List<Int> =
        reports.heartRateService?.let { listOf(it) } ?: listOf(HEARTRATE_COMMAND, HEARTRATE)

    private fun startCampaign() {
        val current = ++epoch
        job?.cancel()
        job = scope.launch {
            fun active() = synchronized(this@HeartRateSession) {
                current == epoch && !closed && wanted && isCurrent()
            }
            try {
                repeat(ATTEMPTS) {
                    if (!active()) return@launch
                    synchronized(this@HeartRateSession) { lastSample = 0 }
                    for ((packet, pause) in OPEN_SERVICES) {
                        if (!active() || !sendRaw(packet)) return@launch
                        delay(pause)
                    }
                    if (!active() || !sendRaw(HRM_ON)) return@launch
                    val micros = interval().inWholeMicroseconds.coerceIn(MIN_INTERVAL_US, MAX_INTERVAL_US).toInt()
                    for (service in synchronized(this@HeartRateSession) { services() }) {
                        delay(120)
                        if (!active() || !sendSetting(service, 1, micros, HEART_LOG_TYPE)) return@launch
                    }
                    val started = now()
                    while (active()) {
                        val last = synchronized(this@HeartRateSession) { lastSample }
                        if (now() - maxOf(started, last) >= WATCHDOG_MS) break
                        delay(250)
                    }
                    if (!active()) return@launch
                    Log.w(TAG, "no heart rate sample for ${WATCHDOG_MS / 1000} s, restarting stream")
                    synchronized(this@HeartRateSession) { stopWire() }
                }
            } finally {
                synchronized(this@HeartRateSession) {
                    // Reaching here while still wanted means start failed or the watchdog gave up.
                    if (current == epoch && !closed && wanted && isCurrent()) {
                        stopWire()
                        wanted = false
                        onState(BuddyState.INACTIVE)
                    }
                }
            }
        }
    }

    private fun stopWire() {
        if (closed || !isCurrent()) return
        for (service in services()) sendSetting(service, 1, 0, HEART_LOG_TYPE)
        sendRaw(HRM_OFF)
        lastSample = 0
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        wanted = false
        onState(BuddyState.INACTIVE)
    }

    companion object {
        private const val TAG = "HeartRateSession"
        private const val ATTEMPTS = 2
        private const val WATCHDOG_MS = 12_000L
        private const val HEART_LOG_TYPE = 2
        private const val HEARTRATE = 19
        private const val HEARTRATE_COMMAND = 84
        private val MIN_INTERVAL_US = 1.seconds.inWholeMicroseconds / 4
        private val MAX_INTERVAL_US = 10.seconds.inWholeMicroseconds

        /** Control command HRM_STATE (0x30): 1 = on, 2 = off. */
        internal val HRM_ON = byteArrayOf(4, 0, 4, 0, 9, 0, 0x30, 1, 0, 0, 0)
        internal val HRM_OFF = byteArrayOf(4, 0, 4, 0, 9, 0, 0x30, 2, 0, 0, 0)

        /** Connect and open services 0 and 4, with the pauses seen in captures. */
        internal val OPEN_SERVICES = listOf(
            byteArrayOf(0, 0, 0, 0, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0) to 180L,
            byteArrayOf(4, 0, 0, 0, 1, 0, 0) to 220L,
            byteArrayOf(0, 0, 4, 0, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0) to 180L,
            byteArrayOf(4, 0, 4, 0, 1, 0, 0) to 220L,
        )

        private fun now() = System.nanoTime() / 1_000_000
    }
}
