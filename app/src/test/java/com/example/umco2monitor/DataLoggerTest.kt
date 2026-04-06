package com.example.umco2monitor

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataLoggerTest {

    private lateinit var mockRepository: SensorRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    private val co2Flow = MutableStateFlow<UShort?>(null)
    private val tempFlow = MutableStateFlow<Float?>(null)
    private val humFlow = MutableStateFlow<Float?>(null)

    @BeforeEach
    fun setup() {
        mockRepository = mockk(relaxed = true)

        // Mock the BluetoothHandler flows to feed custom data into the Logger
        mockkObject(BluetoothHandler)
        every { BluetoothHandler.co2Value } returns co2Flow
        every { BluetoothHandler.temperatureValue } returns tempFlow
        every { BluetoothHandler.humidityValue } returns humFlow
    }

    @Test
    fun logsToDatabase_onlyWhenAllDataIsPresent() = runTest(testDispatcher) {
        // CHANGED: Use backgroundScope
        val logger = DataLogger(mockRepository, backgroundScope)

        // Missing Temperature - Should NOT insert
        co2Flow.value = 500u
        humFlow.value = 45f
        tempFlow.value = null
        coVerify(exactly = 0) { mockRepository.insert(any()) }

        // All present - Should insert
        tempFlow.value = 72f
        coVerify(exactly = 1) { mockRepository.insert(any()) }
    }
}