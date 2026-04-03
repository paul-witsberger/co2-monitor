package com.example.umco2monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * Local unit tests for the Room database [Converters].
 */
class SensorDatabaseTest {

    private val converters: Converters = Converters()

    /**
     * Verifies that [Instant] timestamps are correctly converted to and from [Long] epoch milliseconds.
     */
    @Test
    fun timestampConversion_isCorrect() {
        val now: Instant = kotlin.time.Clock.System.now().let { instant ->
            Instant.fromEpochMilliseconds(instant.toEpochMilliseconds()) }
        val timestamp: Long? = converters.dateToTimestamp(now)
        val restoredDate: Instant? = converters.fromTimestamp(timestamp)

        assertEquals(now, restoredDate)
    }

    /**
     * Verifies that [Instant] converters handle null values gracefully.
     */
    @Test
    fun timestampConversion_handlesNull() {
        assertNull(converters.dateToTimestamp(null))
        assertNull(converters.fromTimestamp(null))
    }

    /**
     * Verifies that [UShort] values are correctly converted to and from [Int] for Room storage.
     */
    @Test
    fun uShortConversion_isCorrect() {
        val originalValue: UShort = 45000u
        val storedValue: Int? = converters.fromUShort(originalValue)
        val restoredValue: UShort? = converters.toUShort(storedValue)

        assertEquals(originalValue, restoredValue)
    }

    /**
     * Verifies that [UShort] converters handle null values gracefully.
     */
    @Test
    fun uShortConversion_handlesNull() {
        assertNull(converters.fromUShort(null))
        assertNull(converters.toUShort(null))
    }
}