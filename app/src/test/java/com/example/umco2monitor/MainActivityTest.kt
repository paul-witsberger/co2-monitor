package com.example.umco2monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Local unit tests for MainActivity plotting math and formatters.
 */
class MainActivityTest {

    /**
     * Verifies that the time interval calculation returns "nice" human-readable durations.
     */
    @Test
    fun getNiceTimeInterval_returnsExpectedIntervals() {
        val durationOneHour: Duration = 1.hours
        assertEquals(15.minutes, getNiceTimeInterval(durationOneHour, 4))

        val durationTwentyFourHours: Duration = 24.hours
        assertEquals(6.hours, getNiceTimeInterval(durationTwentyFourHours, 4))

        val durationSevenDays: Duration = 7.days
        assertEquals(2.days, getNiceTimeInterval(durationSevenDays, 4))
    }

    /**
     * Verifies that the Y-axis range calculation correctly snaps to "nice" multiples
     * and handles floating-point precision issues using the internal epsilon.
     */
    @Test
    fun getNiceRange_calculatesEvenRanges() {
        // Testing standard CO2 range
        val co2Range: Pair<Double, Double> = getNiceRange(400.0, 1200.0, 4)
        assertEquals(400.0, co2Range.first, 1e-10)
        assertEquals(1200.0, co2Range.second, 1e-10)

        // Testing Temperature range with decimal precision
        // Range: 74.8 - 72.1 = 2.7. Ticks: 4. niceTick: 0.7.
        val temperatureRange: Pair<Double, Double> = getNiceRange(72.1, 74.8, 4)

        // Expected min: floor(72.1 / 0.7) * 0.7 = 103 * 0.7 = 72.1
        assertEquals(72.1, temperatureRange.first, 1e-10)

        // Expected max: ceil(74.8 / 0.7) * 0.7 = 107 * 0.7 = 74.9
        assertEquals(74.9, temperatureRange.second, 1e-10)
    }

    /**
     * Verifies that a range with zero difference is expanded to a non-zero range.
     */
    @Test
    fun getNiceRange_handlesZeroDifference() {
        val flatRange: Pair<Double, Double> = getNiceRange(500.0, 500.0)
        assertEquals(499.0, flatRange.first, 1e-10)
        assertEquals(501.0, flatRange.second, 1e-10)
    }
}