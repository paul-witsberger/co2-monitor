package com.example.umco2monitor

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmMonitorTest {

    private lateinit var mockNotifier: AlarmNotifier
    private lateinit var mockPreferencesManager: AlarmPreferencesManager
    private val testDispatcher = UnconfinedTestDispatcher()

    private val co2Flow = MutableStateFlow<UShort?>(null)
    private val preferencesFlow = MutableStateFlow(AlarmPreferences())

    @BeforeEach
    fun setup() {
        mockNotifier = mockk(relaxed = true)
        mockPreferencesManager = mockk(relaxed = true)
        every { mockPreferencesManager.preferencesFlow } returns preferencesFlow

        mockkObject(BluetoothHandler)
        every { BluetoothHandler.co2Value } returns co2Flow
    }

    @Test
    fun triggersWatchdog_after15SecondsOfNoData() = runTest(testDispatcher) {
        @Suppress("UNUSED_VARIABLE", "unused")
        val monitor = AlarmMonitor(backgroundScope, mockPreferencesManager, mockNotifier)

        co2Flow.value = 500u
        advanceTimeBy(1000)
        verify(exactly = 0) { mockNotifier.showAlarm(any(), any(), any(), AlarmMonitor.WATCHDOG_NOTIFICATION_ID) }

        advanceTimeBy(16000)

        verify(exactly = 1) { mockNotifier.showAlarm(any(), any(), AlertType.URGENT, AlarmMonitor.WATCHDOG_NOTIFICATION_ID) }
    }

    @Test
    fun escalatesToSustainedAlarm_after30Seconds() = runTest(testDispatcher) {
        mockkObject(kotlin.time.Clock.System)
        every { kotlin.time.Clock.System.now() } answers { kotlin.time.Instant.fromEpochMilliseconds(testScheduler.currentTime) }

        @Suppress("UNUSED_VARIABLE", "unused")
        val monitor = AlarmMonitor(backgroundScope, mockPreferencesManager, mockNotifier)

        co2Flow.value = 2500u
        advanceTimeBy(1000)
        verify(exactly = 1) { mockNotifier.showAlarm(any(), any(), AlertType.REGULAR, AlarmMonitor.HIGH_CO2_NOTIFICATION_ID) }

        advanceTimeBy(31000)
        co2Flow.value = 2600u
        advanceTimeBy(1000)

        verify(exactly = 1) { mockNotifier.showAlarm(any(), any(), AlertType.URGENT, AlarmMonitor.HIGH_CO2_NOTIFICATION_ID) }
    }

    @Test
    fun muteAlarm_doesNotResetState() = runTest(testDispatcher) {
        // CHANGED: Use backgroundScope
        val monitor = AlarmMonitor(backgroundScope, mockPreferencesManager, mockNotifier)

        monitor.muteAlarm(AlarmMonitor.HIGH_CO2_NOTIFICATION_ID)

        verify { mockNotifier.resolveUrgentAlarm(AlarmMonitor.HIGH_CO2_NOTIFICATION_ID) }
        verify { mockNotifier.cancelNotification(AlarmMonitor.HIGH_CO2_NOTIFICATION_ID) }
    }
}