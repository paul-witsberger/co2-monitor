package com.example.umco2monitor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import timber.log.Timber

class MainActivityInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Explicit flows to prevent UI "hanging" while waiting for data
    private val mockState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    private val mockCo2 = MutableStateFlow<UShort?>(null)
    private val mockTemp = MutableStateFlow<Float?>(null)
    private val mockHum = MutableStateFlow<Float?>(null)
    private val mockBatt = MutableStateFlow<UInt?>(null)

    @Before
    fun setup() {
        mockkObject(BluetoothHandler)

        // Stub initialize as a complete no-op — no real BluetoothCentralManager ever constructed
        every { BluetoothHandler.initialize(any()) } returns Unit
        every { BluetoothHandler.bleConnectionState } returns mockState
        every { BluetoothHandler.co2Value } returns mockCo2
        every { BluetoothHandler.temperatureValue } returns mockTemp
        every { BluetoothHandler.humidityValue } returns mockHum
        every { BluetoothHandler.batteryLevel } returns mockBatt
        every { BluetoothHandler.shutdown() } returns Unit
        every { BluetoothHandler.startScan() } returns Unit
        every { BluetoothHandler.stopScan() } returns Unit

        Timber.uprootAll()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun disconnectedScreen_displaysScanButton() {
        var scanClicked = false
        composeTestRule.setContent {
            DisconnectedScreen(onScanClicked = { scanClicked = true })
        }
        composeTestRule.onNodeWithText("Ready to scan for your sensor.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scan for Devices").performClick()
        assert(scanClicked)
    }

    @Test
    fun connectedScreen_displaysCo2Value() {
        composeTestRule.setContent {
            ConnectedScreen(co2Value = 450u, temperatureValue = 72.14f, humidityValue = 93.4f, onDisconnectClicked = {})
        }
        composeTestRule.onNodeWithText("450").assertIsDisplayed()
        composeTestRule.onNodeWithText("ppm").assertIsDisplayed()
    }

    @Test
    fun deviceListItem_nullName_showsUnknownDevice() {
        var clicked = false
        composeTestRule.setContent {
            DeviceListItem(
                device = DiscoveredDevice(null, "AA:BB:CC:DD:EE:FF", mockk(relaxed = true)),
                onClick = { clicked = true }
            )
        }
        composeTestRule.onNodeWithText("Unknown Device").assertIsDisplayed()
        composeTestRule.onNodeWithText("AA:BB:CC:DD:EE:FF").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unknown Device").performClick()
        assert(clicked)
    }

    @Test
    fun scanningScreen_emptyState_showsNoDevicesMessage() {
        composeTestRule.setContent {
            ScanningScreen(devices = emptyList(), onDeviceClicked = {}, onStopScanClicked = {})
        }
        composeTestRule.onNodeWithText("Scanning...").assertIsDisplayed()
        composeTestRule.onNodeWithText("No devices found yet...").assertIsDisplayed()
    }

    @Test
    fun scanningScreen_withDevices_showsDeviceList() {
        val devices = listOf(
            DiscoveredDevice("Sensor A", "00:11:22:33:44:55", mockk(relaxed = true)),
            DiscoveredDevice(null, "AA:BB:CC:DD:EE:FF", mockk(relaxed = true))
        )
        composeTestRule.setContent {
            ScanningScreen(devices = devices, onDeviceClicked = {}, onStopScanClicked = {})
        }
        composeTestRule.onNodeWithText("Sensor A").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unknown Device").assertIsDisplayed()
    }

    @Test
    fun scanningScreen_deviceClick_invokesCallback() {
        val device = DiscoveredDevice("Sensor A", "00:11:22:33:44:55", mockk(relaxed = true))
        var clickedDevice: DiscoveredDevice? = null
        composeTestRule.setContent {
            ScanningScreen(devices = listOf(device), onDeviceClicked = { clickedDevice = it }, onStopScanClicked = {})
        }
        composeTestRule.onNodeWithText("Sensor A").performClick()
        assert(clickedDevice == device)
    }

    @Test
    fun connectedScreen_nullCo2_showsDashes() {
        composeTestRule.setContent {
            ConnectedScreen(co2Value = null, temperatureValue = null, humidityValue = null, onDisconnectClicked = {})
        }
        composeTestRule.onAllNodesWithText("--").onFirst().assertIsDisplayed()
    }

    @Test
    fun connectedScreen_disconnectButton_invokesCallback() {
        var disconnectClicked = false
        composeTestRule.setContent {
            ConnectedScreen(co2Value = 400u, temperatureValue = 72.14f, humidityValue = 93.4f, onDisconnectClicked = { disconnectClicked = true })
        }
        composeTestRule.onNodeWithText("Disconnect").performClick()
        assert(disconnectClicked)
    }

    @Test
    fun errorScreen_withBothCallbacks_showsBothButtons() {
        composeTestRule.setContent {
            ErrorScreen(
                message = "Test error",
                onRerequestClicked = {},
                onSettingsClicked = {}
            )
        }
        composeTestRule.onNodeWithText("An Error Occurred").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rerequest Permissions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Go to Settings").assertIsDisplayed()
    }

    @Test
    fun errorScreen_withNoCallbacks_showsNoButtons() {
        composeTestRule.setContent {
            ErrorScreen(message = "Test error")
        }
        composeTestRule.onNodeWithText("Rerequest Permissions").assertDoesNotExist()
        composeTestRule.onNodeWithText("Go to Settings").assertDoesNotExist()
    }

    @Test
    fun mainScreen_routing_workflow() {
        val mockViewModel = mockk<SensorViewModel>(relaxed = true)
        every { mockViewModel.bleConnectionState } returns mockState
        every { mockViewModel.co2Value } returns mockCo2
        every { mockViewModel.temperatureValue } returns mockTemp
        every { mockViewModel.humidityValue } returns mockHum

        composeTestRule.setContent {
            MainScreen(viewModel = mockViewModel, activity = mockk(relaxed = true))
        }

        // 1. Check initial route
        composeTestRule.onNodeWithText("Ready to scan for your sensor.").assertIsDisplayed()

        // 2. Check transition to Scanning
        mockState.value = BleConnectionState.Scanning(emptyList())
        composeTestRule.onNodeWithText("Scanning...").assertIsDisplayed()

        // 3. Check transition to Connected
        mockState.value = BleConnectionState.Connected(mockk(relaxed = true))
        mockCo2.value = 500u
        composeTestRule.onNodeWithText("500").assertIsDisplayed()
    }

    @Test
    fun mainScreen_errorRouting_permissionLogic() {
        val mockViewModel = mockk<SensorViewModel>(relaxed = true)
        val mockActivity = mockk<MainActivity>(relaxed = true)
        every { mockViewModel.bleConnectionState } returns mockState

        composeTestRule.setContent {
            MainScreen(viewModel = mockViewModel, activity = mockActivity)
        }

        // GIVEN: A message containing "permission"
        mockState.value = BleConnectionState.Error("Bluetooth permission denied")

        // THEN: Verify permission buttons appear
        composeTestRule.onNodeWithText("Rerequest Permissions").assertIsDisplayed().performClick()
        verify { mockActivity.reRequestPermissions() }

        composeTestRule.onNodeWithText("Go to Settings").assertIsDisplayed().performClick()
        verify { mockActivity.openAppSettings() }
    }

    @Test
    fun mainScreen_errorRouting_genericLogic() {
        val mockViewModel = mockk<SensorViewModel>(relaxed = true)
        val mockActivity = mockk<MainActivity>(relaxed = true)
        every { mockViewModel.bleConnectionState } returns mockState

        composeTestRule.setContent {
            MainScreen(viewModel = mockViewModel, activity = mockActivity)
        }

        // GIVEN: A message NOT containing "permission"
        mockState.value = BleConnectionState.Error("Device Busy")

        // THEN: Button should call startScan instead of reRequest
        composeTestRule.onNodeWithText("Restart Scan").assertIsDisplayed().performClick()
        verify { mockViewModel.startScan() }
        verify(exactly = 0) { mockActivity.reRequestPermissions() }

        // Settings button should NOT exist
        composeTestRule.onNodeWithText("Go to Settings").assertDoesNotExist()
    }

    @Test
    fun mainScreen_disconnectButton_callsViewModel() {
        val mockViewModel = mockk<SensorViewModel>(relaxed = true)
        every { mockViewModel.bleConnectionState } returns mockState
        every { mockViewModel.co2Value } returns mockCo2
        every { mockViewModel.temperatureValue } returns mockTemp
        every { mockViewModel.humidityValue } returns mockHum

        composeTestRule.setContent {
            MainScreen(viewModel = mockViewModel, activity = mockk(relaxed = true))
        }

        mockState.value = BleConnectionState.Connected(mockk(relaxed = true))
        composeTestRule.onNodeWithText("Disconnect").performClick()
        verify { mockViewModel.disconnect() }
    }

    @Test
    fun mainScreen_scanButton_callsViewModel() {
        val mockViewModel = mockk<SensorViewModel>(relaxed = true)
        every { mockViewModel.bleConnectionState } returns mockState
        every { mockViewModel.co2Value } returns mockCo2

        composeTestRule.setContent {
            MainScreen(viewModel = mockViewModel, activity = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithText("Scan for Devices").performClick()
        verify { mockViewModel.startScan() }
    }

    @Test
    fun baseline_emptyTest() {
        // Does nothing - verifies that there aren't any issues with timing increasing between tests
    }
}