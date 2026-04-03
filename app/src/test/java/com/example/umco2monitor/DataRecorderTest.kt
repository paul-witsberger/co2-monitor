package com.example.umco2monitor

import android.Manifest
import android.app.NotificationManager
import android.app.NotificationChannel
import android.content.Context
import android.content.pm.PackageManager
import android.text.TextUtils
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DataRecorderTest {

    private lateinit var mockContext: Context
    private lateinit var mockRepository: SensorRepository
    private lateinit var mockNotificationManager: NotificationManager

    private val co2Flow: MutableStateFlow<UShort?> = MutableStateFlow(null)
    private val tempFlow: MutableStateFlow<Float?> = MutableStateFlow(null)
    private val humFlow: MutableStateFlow<Float?> = MutableStateFlow(null)

    @BeforeEach
    fun setup(): Unit {
        mockContext = mockk(relaxed = true)
        mockRepository = mockk(relaxed = true)
        mockNotificationManager = mockk(relaxed = true)

        every { mockContext.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockNotificationManager
        
        mockkConstructor(NotificationChannel::class)
        every { anyConstructed<NotificationChannel>().setDescription(any<String>()) } returns Unit

        mockkConstructor(NotificationCompat.Builder::class)
        every { anyConstructed<NotificationCompat.Builder>().build() } returns mockk(relaxed = true)

        mockkStatic(android.os.Process::class)
        every { android.os.Process.myPid() } returns 1

        mockkStatic(TextUtils::class)
        every { TextUtils.equals(any(), any()) } answers {
            arg<CharSequence?>(0) == arg<CharSequence?>(1)
        }

        mockkStatic(ContextCompat::class)
        mockkStatic(ActivityCompat::class)
        every { ActivityCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        mockkObject(BluetoothHandler)
        every { BluetoothHandler.co2Value } returns co2Flow
        every { BluetoothHandler.temperatureValue } returns tempFlow
        every { BluetoothHandler.humidityValue } returns humFlow
    }

    @AfterEach
    fun teardown(): Unit {
        unmockkAll()
    }

    @Test
    fun recordsDataWhenAllValuesPresent(): Unit = runTest(UnconfinedTestDispatcher()) {
        mockkObject(kotlin.time.Clock.System)
        every { kotlin.time.Clock.System.now() } answers { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

        val dataRecorder: DataRecorder = DataRecorder(mockContext, mockRepository, backgroundScope)

        co2Flow.value = 500u
        tempFlow.value = 22f
        humFlow.value = 45f

        // Advance time to pass debounce(1000)
        advanceTimeBy(1001)

        coVerify(exactly = 1) { mockRepository.insert(any()) }
    }

    @Test
    fun sendsHighCo2Alarm(): Unit = runTest(UnconfinedTestDispatcher()) {
        mockkObject(kotlin.time.Clock.System)
        every { kotlin.time.Clock.System.now() } answers { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

        val dataRecorder: DataRecorder = DataRecorder(mockContext, mockRepository, backgroundScope)

        co2Flow.value = 2500u
        tempFlow.value = 22f
        humFlow.value = 45f

        advanceTimeBy(1001)

        // Should trigger initial alarm notification
        verify(exactly = 1) { mockNotificationManager.notify(101, any()) }
        
        // Advance by more than 30 seconds to trigger urgent alarm
        advanceTimeBy(31000)
        
        co2Flow.value = 2600u
        advanceTimeBy(1001)

        // Notify should be called again for urgent alarm
        verify(atLeast = 2) { mockNotificationManager.notify(101, any()) }

        // Go back to normal
        co2Flow.value = 500u
        advanceTimeBy(1001)

        // Notify called for "returned to normal"
        verify(atLeast = 3) { mockNotificationManager.notify(101, any()) }
    }

    @Test
    fun sendsLowCo2Alarm(): Unit = runTest(UnconfinedTestDispatcher()) {
        mockkObject(kotlin.time.Clock.System)
        every { kotlin.time.Clock.System.now() } answers { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

        val dataRecorder: DataRecorder = DataRecorder(mockContext, mockRepository, backgroundScope)

        co2Flow.value = 350u
        tempFlow.value = 22f
        humFlow.value = 45f

        advanceTimeBy(1001)

        // Should trigger initial low CO2 alarm notification
        verify(exactly = 1) { mockNotificationManager.notify(102, any()) }

        // Advance time
        advanceTimeBy(31000)
        co2Flow.value = 300u
        advanceTimeBy(1001)

        // Should trigger urgent low alarm
        verify(atLeast = 2) { mockNotificationManager.notify(102, any()) }

        // Go back to normal
        co2Flow.value = 500u
        advanceTimeBy(1001)

        // Notify returned to normal
        verify(atLeast = 3) { mockNotificationManager.notify(102, any()) }
    }

    /**
     * Verifies that the recorder does not crash and handles logic correctly
     * when POST_NOTIFICATIONS permission is denied by the user.
     */
    @Test
    fun doesNotCrashWhenNotificationPermissionDenied() = runTest(UnconfinedTestDispatcher()) {
        // GIVEN: Notification permission is denied
        every {
            ActivityCompat.checkSelfPermission(any(), Manifest.permission.POST_NOTIFICATIONS)
        } returns PackageManager.PERMISSION_DENIED

        mockkObject(kotlin.time.Clock.System)
        every { kotlin.time.Clock.System.now() } answers { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

        val recorder: DataRecorder = DataRecorder(mockContext, mockRepository, backgroundScope, sdkInt = 33)

        // WHEN: A high CO2 reading occurs that would normally trigger an alarm
        co2Flow.value = 2500u
        tempFlow.value = 72f
        humFlow.value = 40f
        advanceTimeBy(1001)

        // THEN: The notification manager should never be called
        verify(exactly = 0) { mockNotificationManager.notify(any(), any()) }
    }
}
