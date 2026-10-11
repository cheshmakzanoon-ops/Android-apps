package com.agency.photoduel.engine.pure

import java.util.Random
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionPlannerTest {

    /** A monotone fake encoder: 10 000 bytes per quality point. Records every probe. */
    private class LinearEncoder(private val bytesPerQuality: Int = 10_000) {
        val probes = ArrayList<Int>()
        fun encode(quality: Int): Int {
            probes += quality
            return quality * bytesPerQuality
        }
    }

    @Test
    fun picksTheHighestQualityThatFitsTheBudget() {
        val encoder = LinearEncoder()
        val plan = CompressionPlanner.plan(budgetBytes = 855_000) { encoder.encode(it) }
        assertEquals(85, plan.quality)
        assertTrue(plan.budgetMet)
        assertEquals(850_000, plan.sizeBytes)
        assertEquals(encoder.probes.size, plan.encoderCalls)
    }

    @Test
    fun theBudgetIsInclusive() {
        val plan = CompressionPlanner.plan(budgetBytes = 850_000) { it * 10_000 }
        assertEquals(85, plan.quality)
        assertTrue(plan.budgetMet)
        val tooTight = CompressionPlanner.plan(budgetBytes = 849_999) { it * 10_000 }
        assertEquals(84, tooTight.quality)
        assertTrue(tooTight.budgetMet)
    }

    @Test
    fun anImageThatFitsAtTheTopQualityNeedsOnlyOneEncode() {
        val encoder = LinearEncoder()
        val plan = CompressionPlanner.plan(budgetBytes = 5_000_000) { encoder.encode(it) }
        assertEquals(100, plan.quality)
        assertTrue(plan.budgetMet)
        assertEquals(1, plan.encoderCalls)
        assertEquals(listOf(100), encoder.probes)
    }

    @Test
    fun aPreferredQualityCapBecomesTheTopOfTheSearch() {
        val encoder = LinearEncoder()
        val plan = CompressionPlanner.plan(budgetBytes = 5_000_000, maxQuality = 85) { encoder.encode(it) }
        assertEquals(85, plan.quality)
        assertTrue(plan.budgetMet)
        assertEquals(listOf(85), encoder.probes)

        val tight = CompressionPlanner.plan(budgetBytes = 600_000, maxQuality = 85) { it * 10_000 }
        assertEquals(60, tight.quality)
    }

    @Test
    fun reportsWhenNoQualityFits() {
        val encoder = LinearEncoder()
        val plan = CompressionPlanner.plan(budgetBytes = 5_000) { encoder.encode(it) }
        assertEquals(1, plan.quality)
        assertFalse(plan.budgetMet)
        assertEquals(10_000, plan.sizeBytes)
        assertTrue("calls=${plan.encoderCalls}", plan.encoderCalls <= 8)
        // The lowest quality was in fact probed, so the report is a measurement and not a guess.
        assertTrue(1 in encoder.probes)
    }

    @Test
    fun respectsTheMinimumQuality() {
        val plan = CompressionPlanner.plan(budgetBytes = 5_000, minQuality = 40) { it * 10_000 }
        assertEquals(40, plan.quality)
        assertFalse(plan.budgetMet)
        assertEquals(400_000, plan.sizeBytes)

        val fits = CompressionPlanner.plan(budgetBytes = 300_000, minQuality = 40, maxQuality = 95) { it * 10_000 }
        assertFalse(fits.budgetMet) // 300 000 only fits qualities up to 30, below the allowed minimum
        assertEquals(40, fits.quality)
    }

    @Test
    fun singleQualityRange() {
        val plan = CompressionPlanner.plan(budgetBytes = 100, minQuality = 70, maxQuality = 70) { 70 }
        assertEquals(70, plan.quality)
        assertTrue(plan.budgetMet)
        assertEquals(1, plan.encoderCalls)
        val over = CompressionPlanner.plan(budgetBytes = 10, minQuality = 70, maxQuality = 70) { 70 }
        assertEquals(70, over.quality)
        assertFalse(over.budgetMet)
        assertEquals(1, over.encoderCalls)
    }

    @Test
    fun matchesALinearScanForEveryPossibleThresholdWithAtMostEightEncodes() {
        // size(q) = q * 1000. For each budget from 0.5 steps across the whole range, compare with brute force.
        var maxCalls = 0
        for (budget in 1..101_000 step 250) {
            val plan = CompressionPlanner.plan(budgetBytes = budget) { it * 1000 }
            val expected = (1..100).lastOrNull { it * 1000 <= budget }
            if (expected == null) {
                assertFalse("budget=$budget", plan.budgetMet)
                assertEquals("budget=$budget", 1, plan.quality)
            } else {
                assertTrue("budget=$budget", plan.budgetMet)
                assertEquals("budget=$budget", expected, plan.quality)
                assertTrue(plan.sizeBytes <= budget)
            }
            maxCalls = maxOf(maxCalls, plan.encoderCalls)
            assertTrue("budget=$budget used ${plan.encoderCalls} calls", plan.encoderCalls <= 8)
        }
        assertEquals("worst case is exactly eight encodes", 8, maxCalls)
    }

    @Test
    fun everyCutoffQualityIsFoundForEveryRange() {
        // For every cutoff c: encoder fits exactly when quality <= c. Check all ranges used in practice.
        val ranges = listOf(1 to 100, 1 to 85, 30 to 95, 1 to 1, 50 to 51)
        for ((lo, hi) in ranges) {
            for (cutoff in 0..101) {
                var calls = 0
                val plan = CompressionPlanner.plan(budgetBytes = 1, minQuality = lo, maxQuality = hi) { q ->
                    calls++
                    if (q <= cutoff) 1 else 2
                }
                val expected = (lo..hi).lastOrNull { it <= cutoff }
                if (expected == null) {
                    assertFalse("range=$lo..$hi cutoff=$cutoff", plan.budgetMet)
                    assertEquals(lo, plan.quality)
                } else {
                    assertTrue("range=$lo..$hi cutoff=$cutoff", plan.budgetMet)
                    assertEquals("range=$lo..$hi cutoff=$cutoff", expected, plan.quality)
                }
                assertTrue("range=$lo..$hi cutoff=$cutoff calls=$calls", calls <= 8)
                assertEquals(calls, plan.encoderCalls)
            }
        }
    }

    @Test
    fun worksWithAnExponentialJpegLikeSizeCurve() {
        // JPEG sizes grow faster than linearly at high quality. 20 kB at q=1 up to ~2.4 MB at q=100.
        fun size(q: Int): Int = (20_000 * exp(q * 0.0478)).toInt()
        for (budget in listOf(100_000, 300_000, 307_200, 1_000_000, 1_572_864, 2_000_000)) {
            val plan = CompressionPlanner.plan(budgetBytes = budget, maxQuality = 85) { size(it) }
            val expected = (1..85).lastOrNull { size(it) <= budget }
            assertEquals("budget=$budget", expected, if (plan.budgetMet) plan.quality else null)
            assertTrue(plan.encoderCalls <= 8)
        }
    }

    @Test
    fun isDeterministic() {
        val first = LinearEncoder(7_321)
        val second = LinearEncoder(7_321)
        val a = CompressionPlanner.plan(budgetBytes = 400_000) { first.encode(it) }
        val b = CompressionPlanner.plan(budgetBytes = 400_000) { second.encode(it) }
        assertEquals(a, b)
        assertEquals(first.probes, second.probes)
    }

    @Test
    fun aNoisyNonMonotoneEncoderStillGivesAMeasuredFittingAnswerOrAnHonestFailure() {
        val random = Random(2024)
        repeat(2_000) {
            // Mostly increasing with up-to-3 kB noise, like real encoders.
            val noise = IntArray(101) { random.nextInt(3_000) }
            val budget = 100_000 + random.nextInt(900_000)
            val measured = HashMap<Int, Int>()
            val plan = CompressionPlanner.plan(budgetBytes = budget, maxQuality = 90) { q ->
                val size = q * 10_000 + noise[q]
                measured[q] = size
                size
            }
            assertTrue(plan.encoderCalls <= 8)
            if (plan.budgetMet) {
                assertEquals("answer was measured", plan.sizeBytes, measured.getValue(plan.quality))
                assertTrue("answer fits", plan.sizeBytes <= budget)
            } else {
                assertEquals(1, plan.quality)
                assertTrue(plan.sizeBytes > budget)
            }
        }
    }

    @Test
    fun theLastFittingProbeIsTheChosenQualityWhenTheBudgetIsMet() {
        // The Android pipeline keeps the bytes of the highest fitting probe and reuses them. This
        // test pins the property that makes that valid.
        for (budget in 20_000..1_000_000 step 13_337) {
            val probes = ArrayList<Pair<Int, Int>>()
            val plan = CompressionPlanner.plan(budgetBytes = budget, maxQuality = 85) { q ->
                val s = q * 12_000
                probes += q to s
                s
            }
            if (plan.budgetMet) {
                val highestFitting = probes.filter { it.second <= budget }.maxOf { it.first }
                assertEquals("budget=$budget", plan.quality, highestFitting)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsANonPositiveBudget() {
        CompressionPlanner.plan(budgetBytes = 0) { 1 }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnInvertedQualityRange() {
        CompressionPlanner.plan(budgetBytes = 100, minQuality = 80, maxQuality = 70) { 1 }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsQualityAbove100() {
        CompressionPlanner.plan(budgetBytes = 100, maxQuality = 101) { 1 }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsQualityBelow1() {
        CompressionPlanner.plan(budgetBytes = 100, minQuality = 0) { 1 }
    }
}
