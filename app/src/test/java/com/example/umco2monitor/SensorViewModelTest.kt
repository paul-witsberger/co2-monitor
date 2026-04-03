package com.example.umco2monitor

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.text.TextUtils
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.welie.blessed.BluetoothPeripheral
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days

@OptIn(ExperimentalCoroutinesApi::class)
class SensorViewModelTest {

    private lateinit var viewModel: SensorViewModel
    private val application: Application = mockk<Application>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    private val mockBleState: MutableStateFlow<BleConnectionState> = MutableStateFlow(BleConnectionState.Disconnected)
    private val mockCo2Value: MutableStateFlow<UShort?> = MutableStateFlow(null)
    private val mockTemp: MutableStateFlow<Float?> = MutableStateFlow(null)
    private val mockHum: MutableStateFlow<Float?> = MutableStateFlow(null)
    private val mockBatt: MutableStateFlow<UInt?> = MutableStateFlow(null)

    @BeforeEach
    fun setup(): Unit {
        Dispatchers.setMain(testDispatcher)

        // 1. Mock Android Framework Statics (Prevents crashes)
        mockkStatic(Process::class)
        every { Process.myPid() } returns 1

        mockkStatic(TextUtils::class)
        every { TextUtils.equals(any(), any()) } answers {
            arg<CharSequence?>(0) == arg<CharSequence?>(1)
        }

        mockkStatic(ContextCompat::class)
        mockkStatic(ActivityCompat::class)

        // 2. Mock BluetoothHandler object
        mockkObject(BluetoothHandler)
        every { BluetoothHandler.initialize(any()) } returns Unit
        every { BluetoothHandler.bleConnectionState } returns mockBleState
        every { BluetoothHandler.co2Value } returns mockCo2Value
        every { BluetoothHandler.temperatureValue } returns mockTemp
        every { BluetoothHandler.humidityValue } returns mockHum
        every { BluetoothHandler.batteryLevel } returns mockBatt

        every { BluetoothHandler.startScan() } returns Unit
        every { BluetoothHandler.stopScan() } returns Unit
        every { BluetoothHandler.connect(any()) } returns Unit
        every { BluetoothHandler.disconnect(any()) } returns Unit

        viewModel = SensorViewModel(application)
    }

    @AfterEach
    fun tearDown(): Unit {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun startScanShouldCallOnPermissionsDeniedWhenPermissionsMissingOnAndroidS(): Unit {
        // GIVEN: Permissions are denied
        every { ActivityCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_DENIED

        // GIVEN: Create a SPY of the real ViewModel
        val spyViewModel: SensorViewModel = spyk(viewModel, recordPrivateCalls = true)

        // WHEN: Calling startScan and EXPLICITLY passing Android S (31)
        spyViewModel.startScan(Build.VERSION_CODES.S)

        // THEN: Verify the internal method was reached
        verify(exactly = 1) { spyViewModel.onPermissionsDenied() }
        verify(exactly = 0) { BluetoothHandler.startScan() }
    }

    @Test
    fun startScanShouldDelegateToBluetoothHandlerWhenPermissionsGranted(): Unit {
        // GIVEN: Permissions are granted
        every { ActivityCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        // WHEN: Calling startScan (defaulting to current SDK_INT is fine here)
        viewModel.startScan(Build.VERSION_CODES.S)

        verify { BluetoothHandler.startScan() }
    }

    @Test
    fun initShouldInitializeBluetoothHandlerAndObserveState(): Unit {
        verify { BluetoothHandler.initialize(application) }
        val newState: BleConnectionState.Scanning = BleConnectionState.Scanning(emptyList())
        mockBleState.value = newState
        assertEquals(newState, viewModel.bleConnectionState.value)
    }

    @Test
    fun onPermissionsDeniedShouldSetStateToError(): Unit {
        viewModel.onPermissionsDenied()
        assertTrue(viewModel.bleConnectionState.value is BleConnectionState.Error)
    }

    @Test
    fun stopScanShouldDelegateToBluetoothHandler(): Unit {
        viewModel.stopScan()
        verify { BluetoothHandler.stopScan() }
    }

    @Test
    fun connectToDeviceShouldDelegateToBluetoothHandler(): Unit {
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>()
        val device: DiscoveredDevice = DiscoveredDevice("Test", "00:00", peripheral)
        viewModel.connectToDevice(device)
        verify { BluetoothHandler.connect(peripheral) }
    }

    @Test
    fun disconnectShouldDelegateToBluetoothHandlerWhenConnected(): Unit {
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>()
        mockBleState.value = BleConnectionState.Connected(peripheral)
        viewModel.disconnect()
        verify { BluetoothHandler.disconnect(peripheral) }
    }

    @Test
    fun sensorViewModelFactoryShouldCreateSensorViewModel(): Unit {
        val factory: SensorViewModelFactory = SensorViewModelFactory(application)
        val createdViewModel: SensorViewModel = factory.create(SensorViewModel::class.java)
        assertEquals(SensorViewModel::class.java, createdViewModel::class.java)
    }

    @Test
    fun setSelectedTabUpdatesSelectedTab(): Unit {
        viewModel.setSelectedTab(1)
        assertEquals(1, viewModel.selectedTab.value)
        viewModel.setSelectedTab(0)
        assertEquals(0, viewModel.selectedTab.value)
    }

    @Test
    fun updateHistorySettingsUpdatesSettingsAndEmitsSignal(): Unit = runTest {
        viewModel.updateHistorySettings(showCo2 = false, timeRange = 1.days)
        
        val settings: HistorySettings = viewModel.historySettings.value
        assertEquals(false, settings.showCo2)
        assertEquals(1.days, settings.timeRange)
        // Ensure defaults are still present
        assertEquals(true, settings.showTemperature)
    }
}
