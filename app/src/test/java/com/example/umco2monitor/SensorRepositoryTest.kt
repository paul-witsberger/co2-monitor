package com.example.umco2monitor

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class SensorRepositoryTest {

    @Test
    fun testAllReadings(): Unit = runTest {
        val mockDao: SensorDataDao = mockk(relaxed = true)
        val fakeFlow: Flow<List<SensorDataEntity>> = flowOf(emptyList())
        every { mockDao.getAllReadings() } returns fakeFlow
        
        val repository: SensorRepository = SensorRepository(mockDao)
        assertEquals(fakeFlow, repository.allReadings)
    }

    @Test
    fun testInsert(): Unit = runTest {
        val mockDao: SensorDataDao = mockk(relaxed = true)
        val repository: SensorRepository = SensorRepository(mockDao)
        val entity: SensorDataEntity = SensorDataEntity(
            co2Reading = 400u, 
            temperatureReading = 25f, 
            humidityReading = 50f, 
            timestamp = Clock.System.now()
        )
        
        repository.insert(entity)
        coVerify(exactly = 1) { mockDao.insert(entity) }
    }
}
