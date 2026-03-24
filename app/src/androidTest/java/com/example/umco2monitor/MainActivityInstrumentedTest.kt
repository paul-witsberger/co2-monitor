package com.example.umco2monitor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.welie.blessed.BluetoothPeripheral
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MainActivityInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        // Force the BluetoothHandler to be a mock so it doesn't
        // actually start real threads or timers during UI tests
        io.mockk.mockkObject(BluetoothHandler)
        every { BluetoothHandler.initialize(any()) } returns Unit
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    @Test
    fun mainScreen_transitionsFromScanningToConnected() {
        // 1. GIVEN: A mock ViewModel with a state flow we can control
        val mockViewModel = mockk<SensorViewModel>(relaxed = true)
        val mockState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
        val mockCo2 = MutableStateFlow<UShort?>(null)

        every { mockViewModel.bleConnectionState } returns mockState
        every { mockViewModel.co2Value } returns mockCo2
        every { mockViewModel.temperatureValue } returns MutableStateFlow(null)
        every { mockViewModel.humidityValue } returns MutableStateFlow(null)
        every { mockViewModel.batteryLevel } returns MutableStateFlow(null)

        every { mockViewModel.startScan() } returns Unit
        every { mockViewModel.stopScan() } returns Unit

        composeTestRule.setContent {
            // We pass a mock Activity context as well
            MainScreen(viewModel = mockViewModel, activity = mockk(relaxed = true))
        }

        // 2. ASSERT: Initial state is Disconnected
        composeTestRule.onNodeWithText("Scan for Devices").assertIsDisplayed()

        // 3. WHEN: State changes to Scanning
        mockState.value = BleConnectionState.Scanning(emptyList())

        // 4. THEN: UI should update to ScanningScreen
        composeTestRule.onNodeWithText("Scanning...").assertIsDisplayed()

        // 5. WHEN: State changes to Connected
        val mockPeripheral = mockk<BluetoothPeripheral>(relaxed = true)
        mockState.value = BleConnectionState.Connected(mockPeripheral)
        mockCo2.value = 500u

        // 6. THEN: UI should update to ConnectedScreen
        composeTestRule.onNodeWithText("CO2 Reading").assertIsDisplayed()
        composeTestRule.onNodeWithText("500").assertIsDisplayed()
    }

    @Test
    fun mainScreen_errorState_showsCorrectButtonsBasedOnMessage() {
        val mockViewModel = mockk<SensorViewModel>(relaxed = true)
        val mockState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)

        every { mockViewModel.bleConnectionState } returns mockState
        every { mockViewModel.co2Value } returns MutableStateFlow(null)
        every { mockViewModel.temperatureValue } returns MutableStateFlow(null)
        every { mockViewModel.humidityValue } returns MutableStateFlow(null)
        every { mockViewModel.batteryLevel } returns MutableStateFlow(null)

        every { mockViewModel.startScan() } returns Unit
        every { mockViewModel.stopScan() } returns Unit

        composeTestRule.setContent {
            MainScreen(viewModel = mockViewModel, activity = mockk(relaxed = true))
        }

        // 1. GIVEN: A generic error
        mockState.value = BleConnectionState.Error("Device Busy")
        // THEN: Should show Rerequest but NOT necessarily Go to Settings (depends on your ErrorScreen logic)
        composeTestRule.onNodeWithText("Device Busy").assertIsDisplayed()

        // 2. GIVEN: A permission-related error
        mockState.value = BleConnectionState.Error("Bluetooth permission denied")
        // THEN: Should specifically show "Rerequest Permissions" and "Go to Settings"
        composeTestRule.onNodeWithText("Rerequest Permissions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Go to Settings").assertIsDisplayed()
    }

    @Test
    fun mainScreen_errorButtons_triggerActivityMethods() {
        val mockViewModel = mockk<SensorViewModel>(relaxed = true)
        val mockActivity = mockk<MainActivity>(relaxed = true)
        val mockState = MutableStateFlow<BleConnectionState>(
            BleConnectionState.Error("permission denied")
        )

        every { mockViewModel.bleConnectionState } returns mockState
        every { mockViewModel.co2Value } returns MutableStateFlow(null)
        every { mockViewModel.temperatureValue } returns MutableStateFlow(null)
        every { mockViewModel.humidityValue } returns MutableStateFlow(null)
        every { mockViewModel.batteryLevel } returns MutableStateFlow(null)

        every { mockViewModel.startScan() } returns Unit
        every { mockViewModel.stopScan() } returns Unit

        composeTestRule.setContent {
            MainScreen(viewModel = mockViewModel, activity = mockActivity)
        }

        // WHEN: Click Rerequest
        composeTestRule.onNodeWithText("Rerequest Permissions").performClick()
        // THEN: MainActivity.reRequestPermissions() should be called
        io.mockk.verify { mockActivity.reRequestPermissions() }

        // WHEN: Click Go to Settings
        composeTestRule.onNodeWithText("Go to Settings").performClick()
        // THEN: MainActivity.openAppSettings() should be called
        io.mockk.verify { mockActivity.openAppSettings() }
    }

    @Test
    fun disconnectedScreen_displaysScanButton() {
        var scanClicked = false
        composeTestRule.setContent {
            DisconnectedScreen(onScanClicked = { scanClicked = true })
        }

        composeTestRule.onNodeWithText("Ready to scan for your sensor.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scan for Devices").assertIsDisplayed().performClick()
        assert(scanClicked)
    }

    @Test
    fun scanningScreen_displaysDevices() {
        val mockPeripheral = mockk<BluetoothPeripheral>(relaxed = true)
        val devices = listOf(
            DiscoveredDevice("Test Device 1", "00:11:22:33:44:55", mockPeripheral),
            DiscoveredDevice(null, "AA:BB:CC:DD:EE:FF", mockPeripheral)
        )

        composeTestRule.setContent {
            ScanningScreen(devices = devices, onDeviceClicked = {})
        }

        composeTestRule.onNodeWithText("Scanning...").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Device 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("00:11:22:33:44:55").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unknown Device").assertIsDisplayed()
        composeTestRule.onNodeWithText("AA:BB:CC:DD:EE:FF").assertIsDisplayed()
    }

    @Test
    fun scanningScreen_displaysEmptyMessage() {
        composeTestRule.setContent {
            ScanningScreen(devices = emptyList(), onDeviceClicked = {})
        }

        composeTestRule.onNodeWithText("No devices found yet...").assertIsDisplayed()
    }

    @Test
    fun connectedScreen_displaysCo2Value() {
        var disconnectClicked = false
        val co2Value: UShort = 450u
        
        composeTestRule.setContent {
            ConnectedScreen(co2Value = co2Value, onDisconnectClicked = { disconnectClicked = true })
        }

        composeTestRule.onNodeWithText("CO2 Reading").assertIsDisplayed()
        composeTestRule.onNodeWithText("450").assertIsDisplayed()
        composeTestRule.onNodeWithText("ppm").assertIsDisplayed()
        composeTestRule.onNodeWithText("Disconnect").assertIsDisplayed().performClick()
        assert(disconnectClicked)
    }

    @Test
    fun errorScreen_displaysMessageAndButtons() {
        var rerequestClicked = false
        var settingsClicked = false
        val errorMessage = "Test Error Message"

        composeTestRule.setContent {
            ErrorScreen(
                message = errorMessage,
                onRerequestClicked = { rerequestClicked = true },
                onSettingsClicked = { settingsClicked = true }
            )
        }

        composeTestRule.onNodeWithText("An Error Occurred").assertIsDisplayed()
        composeTestRule.onNodeWithText(errorMessage).assertIsDisplayed()
        
        composeTestRule.onNodeWithText("Rerequest Permissions").assertIsDisplayed().performClick()
        assert(rerequestClicked)
        
        composeTestRule.onNodeWithText("Go to Settings").assertIsDisplayed().performClick()
        assert(settingsClicked)
    }

    @Test
    fun deviceListItem_displaysCorrectInfo() {
        val mockPeripheral = mockk<BluetoothPeripheral>(relaxed = true)
        val device = DiscoveredDevice("My Sensor", "12:34:56:78:90:AB", mockPeripheral)
        var itemClicked = false

        composeTestRule.setContent {
            DeviceListItem(device = device, onClick = { itemClicked = true })
        }

        composeTestRule.onNodeWithText("My Sensor").assertIsDisplayed()
        composeTestRule.onNodeWithText("12:34:56:78:90:AB").assertIsDisplayed().performClick()
        assert(itemClicked)
    }
}
