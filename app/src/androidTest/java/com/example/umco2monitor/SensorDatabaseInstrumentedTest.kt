package com.example.umco2monitor

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@RunWith(AndroidJUnit4::class)
class SensorDatabaseInstrumentedTest {
    private lateinit var database: SensorDatabase
    private lateinit var dao: SensorDataDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SensorDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.sensorDataDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndGetAllReadings(): Unit = runTest {
        val now: kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
        val entity: SensorDataEntity = SensorDataEntity(co2Reading = 500u, temperatureReading = 22f, humidityReading = 45f, timestamp = now)
        dao.insert(entity)

        val readings: List<SensorDataEntity> = dao.getAllReadings().first()
        assertEquals(1, readings.size)
        assertEquals(500.toUShort(), readings[0].co2Reading)
        assertEquals(22f, readings[0].temperatureReading)
        assertEquals(45f, readings[0].humidityReading)
        assertEquals(now, readings[0].timestamp)
    }

    @Test
    fun getReadingsInRange(): Unit = runTest {
        val now: kotlin.time.Instant = Clock.System.now()
        val entity1: SensorDataEntity = SensorDataEntity(co2Reading = 400u, temperatureReading = 20f, humidityReading = 40f, timestamp = now - 2.hours)
        val entity2: SensorDataEntity = SensorDataEntity(co2Reading = 600u, temperatureReading = 25f, humidityReading = 50f, timestamp = now)
        
        dao.insert(entity1)
        dao.insert(entity2)

        val inRange: List<SensorDataEntity> = dao.getReadingsInRange(now - 1.hours, now + 1.hours).first()
        assertEquals(1, inRange.size)
        assertEquals(600.toUShort(), inRange[0].co2Reading)
    }

    @Test
    fun deleteReadingsInRange(): Unit = runTest {
        val now: kotlin.time.Instant = Clock.System.now()
        val entity1: SensorDataEntity = SensorDataEntity(co2Reading = 400u, temperatureReading = 20f, humidityReading = 40f, timestamp = now - 2.hours)
        val entity2: SensorDataEntity = SensorDataEntity(co2Reading = 600u, temperatureReading = 25f, humidityReading = 50f, timestamp = now)
        
        dao.insert(entity1)
        dao.insert(entity2)

        dao.deleteReadingsInRange(now - 1.hours, now + 1.hours)

        val remaining: List<SensorDataEntity> = dao.getAllReadings().first()
        assertEquals(1, remaining.size)
        assertEquals(400.toUShort(), remaining[0].co2Reading)
    }

    @Test
    fun deleteAll(): Unit = runTest {
        val entity: SensorDataEntity = SensorDataEntity(co2Reading = 500u, temperatureReading = 22f, humidityReading = 45f, timestamp = Clock.System.now())
        dao.insert(entity)
        dao.deleteAll()

        val remaining: List<SensorDataEntity> = dao.getAllReadings().first()
        assertTrue(remaining.isEmpty())
    }
}
