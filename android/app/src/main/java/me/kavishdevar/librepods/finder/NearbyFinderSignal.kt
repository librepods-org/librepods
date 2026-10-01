package me.kavishdevar.librepods.finder

import java.util.ArrayDeque
import kotlin.math.pow
import kotlin.math.round

enum class ProximityBucket(val label: String) {
    VERY_CLOSE("Very close"),
    CLOSE("Close"),
    NEARBY("Nearby"),
    FAR("Far"),
    SIGNAL_LOST("Signal lost")
}

enum class SignalTrend(val label: String) {
    GETTING_CLOSER("Getting closer"),
    STABLE("Stable"),
    GETTING_FARTHER("Getting farther")
}

data class FinderSignalSnapshot(
    val rawRssi: Int? = null,
    val smoothedRssi: Double? = null,
    val sampleAgeMillis: Long? = null,
    val stale: Boolean = true,
    val approximateDistanceMeters: Double? = null,
    val proximity: ProximityBucket = ProximityBucket.SIGNAL_LOST,
    val trend: SignalTrend = SignalTrend.STABLE,
)

/**
 * Small stateful RSSI filter tuned for a finder UI, not radio metrology.
 *
 * RSSI is strongly affected by body blocking, orientation and multipath. The distance estimate is
 * intentionally coarse and should only be presented as approximate.
 */
class RssiSignalProcessor(
    private val emaAlpha: Double = 0.23,
    private val staleAfterMillis: Long = 3_000L,
    private val lostAfterMillis: Long = 8_000L,
    private val hysteresisDb: Double = 3.5,
    private val calibratedRssiAtOneMeter: Double = -59.0,
    private val pathLossExponent: Double = 2.2,
    private val minTrendSamples: Int = 6,
    private val minTrendSpanMillis: Long = 1_750L,
    private val trendThresholdDb: Double = 2.5,
    private val medianWindowSize: Int = 7
) {
    private data class HistoryPoint(val elapsedRealtime: Long, val rssi: Double)

    private val history = ArrayDeque<HistoryPoint>()
    private val rawWindow = ArrayDeque<Int>()
    private var rawRssi: Int? = null
    private var smoothedRssi: Double? = null
    private var lastSampleElapsedRealtime: Long? = null
    private var bucket = ProximityBucket.SIGNAL_LOST

    fun reset() {
        history.clear()
        rawWindow.clear()
        rawRssi = null
        smoothedRssi = null
        lastSampleElapsedRealtime = null
        bucket = ProximityBucket.SIGNAL_LOST
    }

    fun addSample(rssi: Int, elapsedRealtime: Long): FinderSignalSnapshot {
        if (rssi !in -127..20) return snapshot(elapsedRealtime)

        rawRssi = rssi
        rawWindow.addLast(rssi)
        while (rawWindow.size > medianWindowSize.coerceAtLeast(1)) rawWindow.removeFirst()
        val sortedWindow = rawWindow.sorted()
        val robustRssi = if (sortedWindow.size % 2 == 1) {
            sortedWindow[sortedWindow.size / 2].toDouble()
        } else {
            val upper = sortedWindow.size / 2
            (sortedWindow[upper - 1] + sortedWindow[upper]) / 2.0
        }
        smoothedRssi = smoothedRssi?.let { previous ->
            (emaAlpha * robustRssi) + ((1.0 - emaAlpha) * previous)
        } ?: robustRssi
        lastSampleElapsedRealtime = elapsedRealtime

        val filtered = smoothedRssi!!
        // BLE advertisements can arrive dozens of times per second. Trend history is deliberately
        // time-sampled so a burst from one bud cannot dominate several seconds of movement.
        if (history.lastOrNull()?.elapsedRealtime?.let { elapsedRealtime - it >= 250L } != false) {
            history.addLast(HistoryPoint(elapsedRealtime, filtered))
            while (history.size > 24 ||
                (history.firstOrNull()?.elapsedRealtime ?: elapsedRealtime) < elapsedRealtime - 12_000L
            ) {
                history.removeFirst()
            }
        }

        bucket = nextBucket(bucket, filtered)
        return snapshot(elapsedRealtime)
    }

    fun snapshot(nowElapsedRealtime: Long): FinderSignalSnapshot {
        val last = lastSampleElapsedRealtime
        val age = last?.let { (nowElapsedRealtime - it).coerceAtLeast(0L) }
        val isLost = age == null || age >= lostAfterMillis
        val currentBucket = if (isLost) ProximityBucket.SIGNAL_LOST else bucket
        return FinderSignalSnapshot(
            rawRssi = rawRssi,
            smoothedRssi = smoothedRssi,
            sampleAgeMillis = age,
            stale = age == null || age >= staleAfterMillis,
            approximateDistanceMeters = smoothedRssi
                ?.takeUnless { isLost }
                ?.let(::coarseDistanceEstimateMeters),
            proximity = currentBucket,
            trend = if (isLost) SignalTrend.STABLE else calculateTrend(),
        )
    }

    private fun calculateTrend(): SignalTrend {
        if (history.size < minTrendSamples) return SignalTrend.STABLE
        val points = history.toList()
        if (points.last().elapsedRealtime - points.first().elapsedRealtime < minTrendSpanMillis) {
            return SignalTrend.STABLE
        }
        val split = points.size / 2
        val older = points.take(split).map { it.rssi }.average()
        val newer = points.takeLast(split).map { it.rssi }.average()
        val delta = newer - older
        return when {
            delta >= trendThresholdDb -> SignalTrend.GETTING_CLOSER
            delta <= -trendThresholdDb -> SignalTrend.GETTING_FARTHER
            else -> SignalTrend.STABLE
        }
    }

    private fun nextBucket(previous: ProximityBucket, rssi: Double): ProximityBucket {
        val candidate = bucketWithoutHysteresis(rssi)
        if (previous == ProximityBucket.SIGNAL_LOST) return candidate
        if (candidate == previous) return previous

        return when (previous) {
            ProximityBucket.VERY_CLOSE ->
                if (rssi < VERY_CLOSE_THRESHOLD - hysteresisDb) candidate else previous
            ProximityBucket.CLOSE -> when {
                candidate == ProximityBucket.VERY_CLOSE && rssi >= VERY_CLOSE_THRESHOLD + hysteresisDb -> candidate
                candidate.ordinal > previous.ordinal && rssi < CLOSE_THRESHOLD - hysteresisDb -> candidate
                else -> previous
            }
            ProximityBucket.NEARBY -> when {
                candidate.ordinal < previous.ordinal && rssi >= CLOSE_THRESHOLD + hysteresisDb -> candidate
                candidate == ProximityBucket.FAR && rssi < NEARBY_THRESHOLD - hysteresisDb -> candidate
                else -> previous
            }
            ProximityBucket.FAR ->
                if (candidate.ordinal < previous.ordinal && rssi >= NEARBY_THRESHOLD + hysteresisDb) candidate else previous
            ProximityBucket.SIGNAL_LOST -> candidate
        }
    }

    private fun bucketWithoutHysteresis(rssi: Double): ProximityBucket = when {
        rssi >= VERY_CLOSE_THRESHOLD -> ProximityBucket.VERY_CLOSE
        rssi >= CLOSE_THRESHOLD -> ProximityBucket.CLOSE
        rssi >= NEARBY_THRESHOLD -> ProximityBucket.NEARBY
        else -> ProximityBucket.FAR
    }

    private fun coarseDistanceEstimateMeters(rssi: Double): Double {
        val raw = 10.0.pow((calibratedRssiAtOneMeter - rssi) / (10.0 * pathLossExponent))
            .coerceIn(0.1, 50.0)
        return when {
            raw < 1.0 -> maxOf(0.5, round(raw * 2.0) / 2.0)
            raw < 5.0 -> round(raw * 2.0) / 2.0
            raw < 15.0 -> round(raw)
            else -> round(raw / 5.0) * 5.0
        }
    }

    companion object {
        private const val VERY_CLOSE_THRESHOLD = -48.0
        private const val CLOSE_THRESHOLD = -58.0
        private const val NEARBY_THRESHOLD = -68.0
    }
}
