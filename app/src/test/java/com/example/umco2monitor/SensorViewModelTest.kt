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
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SensorViewModelTest {

    private lateinit var viewModel: SensorViewModel
    private val application = mockk<Application>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    private val mockBleState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    private val mockCo2Value = MutableStateFlow<UShort?>(null)
    private val mockTemp = MutableStateFlow<Short?>(null)
    private val mockHum = MutableStateFlow<UShort?>(null)
    private val mockBatt = MutableStateFlow<UInt?>(null)

    @BeforeEach
    fun setup() {
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
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `startScan should call onPermissionsDenied when permissions missing on Android S`() {
        // GIVEN: Permissions are denied
        every { ActivityCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_DENIED

        // GIVEN: Create a SPY of the real ViewModel
        val spyViewModel = spyk(viewModel, recordPrivateCalls = true)

        // WHEN: Calling startScan and EXPLICITLY passing Android S (31)
        spyViewModel.startScan(Build.VERSION_CODES.S)

        // THEN: Verify the internal method was reached
        verify(exactly = 1) { spyViewModel.onPermissionsDenied() }
        verify(exactly = 0) { BluetoothHandler.startScan() }
    }

    @Test
    fun `startScan should delegate to BluetoothHandler when permissions granted`() {
        // GIVEN: Permissions are granted
        every { ActivityCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        // WHEN: Calling startScan (defaulting to current SDK_INT is fine here)
        viewModel.startScan(Build.VERSION_CODES.S)

        verify { BluetoothHandler.startScan() }
    }

    @Test
    fun `init should initialize BluetoothHandler and observe state`() {
        verify { BluetoothHandler.initialize(application) }
        val newState = BleConnectionState.Scanning(emptyList())
        mockBleState.value = newState
        assertEquals(newState, viewModel.bleConnectionState.value)
    }

    @Test
    fun `onPermissionsDenied should set state to Error`() {
        viewModel.onPermissionsDenied()
        assertTrue(viewModel.bleConnectionState.value is BleConnectionState.Error)
    }

    @Test
    fun `stopScan should delegate to BluetoothHandler`() {
        viewModel.stopScan()
        verify { BluetoothHandler.stopScan() }
    }

    @Test
    fun `connectToDevice should delegate to BluetoothHandler`() {
        val peripheral = mockk<BluetoothPeripheral>()
        val device = DiscoveredDevice("Test", "00:00", peripheral)
        viewModel.connectToDevice(device)
        verify { BluetoothHandler.connect(peripheral) }
    }

    @Test
    fun `disconnect should delegate to BluetoothHandler when connected`() {
        val peripheral = mockk<BluetoothPeripheral>()
        mockBleState.value = BleConnectionState.Connected(peripheral)
        viewModel.disconnect()
        verify { BluetoothHandler.disconnect(peripheral) }
    }

    @Test
    fun `SensorViewModelFactory should create SensorViewModel`() {
        val factory = SensorViewModelFactory(application)
        val createdViewModel = factory.create(SensorViewModel::class.java)
        assertEquals(SensorViewModel::class.java, createdViewModel::class.java)
    }
}