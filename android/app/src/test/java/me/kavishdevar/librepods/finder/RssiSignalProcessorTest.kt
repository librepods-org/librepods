package me.kavishdevar.librepods.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssiSignalProcessorTest {
    @Test
    fun emaDampensSingleStrongSpike() {
        val processor = RssiSignalProcessor(medianWindowSize = 1)
        processor.addSample(-60, 0L)
        processor.addSample(-60, 500L)
        val snapshot = processor.addSample(-30, 1_000L)

        val smoothed = requireNotNull(snapshot.smoothedRssi)
        assertTrue(smoothed < -45.0)
        assertTrue(smoothed > -60.0)
        assertEquals(-30, snapshot.rawRssi)
    }

    @Test
    fun bucketHysteresisPreventsBoundaryFlapping() {
        val processor = RssiSignalProcessor(
            emaAlpha = 1.0,
            hysteresisDb = 3.0,
            medianWindowSize = 1
        )

        assertEquals(
            ProximityBucket.CLOSE,
            processor.addSample(-57, 0L).proximity
        )
        assertEquals(
            ProximityBucket.CLOSE,
            processor.addSample(-59, 500L).proximity
        )
        assertEquals(
            ProximityBucket.NEARBY,
            processor.addSample(-62, 1_000L).proximity
        )
    }

    @Test
    fun signalBecomesStaleThenLost() {
        val processor = RssiSignalProcessor(emaAlpha = 1.0, medianWindowSize = 1)
        processor.addSample(-55, 1_000L)

        val stale = processor.snapshot(4_000L)
        assertTrue(stale.stale)
        assertEquals(ProximityBucket.CLOSE, stale.proximity)

        val lost = processor.snapshot(9_000L)
        assertTrue(lost.stale)
        assertEquals(ProximityBucket.SIGNAL_LOST, lost.proximity)
        assertEquals(null, lost.approximateDistanceMeters)
    }

    @Test
    fun trendNeedsHistoryAndDetectsCloserFartherAndStable() {
        fun trendFor(values: List<Int>): SignalTrend {
            val processor = RssiSignalProcessor(
                emaAlpha = 1.0,
                minTrendSamples = 5,
                minTrendSpanMillis = 2_000L,
                trendThresholdDb = 2.0,
                medianWindowSize = 1
            )
            values.forEachIndexed { index, rssi ->
                processor.addSample(
                    rssi,
                    index * 500L,
                )
            }
            return processor.snapshot(2_000L).trend
        }

        assertEquals(SignalTrend.GETTING_CLOSER, trendFor(listOf(-70, -68, -66, -64, -62)))
        assertEquals(SignalTrend.GETTING_FARTHER, trendFor(listOf(-62, -64, -66, -68, -70)))
        assertEquals(SignalTrend.STABLE, trendFor(listOf(-65, -64, -65, -64, -65)))
    }
}
