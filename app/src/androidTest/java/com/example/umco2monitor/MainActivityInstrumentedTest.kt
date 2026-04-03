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
    val composeTestRule: androidx.compose.ui.test.junit4.ComposeContentTestRule = createComposeRule()

    private val mockState: MutableStateFlow<BleConnectionState> = MutableStateFlow(BleConnectionState.Disconnected)
    private val mockCo2: MutableStateFlow<UShort?> = MutableStateFlow(null)
    private val mockTemp: MutableStateFlow<Float?> = MutableStateFlow(null)
    private val mockHum: MutableStateFlow<Float?> = MutableStateFlow(null)
    private val mockBatt: MutableStateFlow<UInt?> = MutableStateFlow(null)
    private val mockTab: MutableStateFlow<Int> = MutableStateFlow(0)
    private val mockHistory: MutableStateFlow<List<SensorData>> = MutableStateFlow(emptyList())
    private val mockHistorySettings: MutableStateFlow<HistorySettings> = MutableStateFlow(HistorySettings())

    @Before
    fun setup(): Unit {
        mockkObject(BluetoothHandler)

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
    fun tearDown(): Unit {
        unmockkAll()
    }

    private fun createMockViewModel(): SensorViewModel {
        val mockViewModel: SensorViewModel = mockk<SensorViewModel>(relaxed = true)
        every { mockViewModel.bleConnectionState } returns mockState
        every { mockViewModel.co2Value } returns mockCo2
        every { mockViewModel.temperatureValue } returns mockTemp
        every { mockViewModel.humidityValue } returns mockHum
        every { mockViewModel.batteryLevel } returns mockBatt
        every { mockViewModel.selectedTab } returns mockTab
        every { mockViewModel.history } returns mockHistory
        every { mockViewModel.historySettings } returns mockHistorySettings
        return mockViewModel
    }

    @Test
    fun disconnectedScreen_displaysScanButton(): Unit {
        var scanClicked: Boolean = false
        composeTestRule.setContent {
            DisconnectedScreen(onScanClicked = { scanClicked = true })
        }
        composeTestRule.onNodeWithText("Ready to scan for your sensor.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scan for Devices").performClick()
        assert(scanClicked)
    }

    @Test
    fun connectedScreen_displaysCo2Value(): Unit {
        val viewModel: SensorViewModel = createMockViewModel()
        mockCo2.value = 450u
        mockTemp.value = 72.14f
        mockHum.value = 93.4f

        composeTestRule.setContent {
            ConnectedScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("450 ppm").assertIsDisplayed()
    }

    @Test
    fun deviceListItem_nullName_showsUnknownDevice(): Unit {
        var clicked: Boolean = false
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
    fun scanningScreen_emptyState_showsNoDevicesMessage(): Unit {
        composeTestRule.setContent {
            ScanningScreen(devices = emptyList(), onDeviceClicked = {}, onStopScanClicked = {})
        }
        composeTestRule.onNodeWithText("Scanning...").assertIsDisplayed()
        composeTestRule.onNodeWithText("No devices found yet...").assertIsDisplayed()
    }

    @Test
    fun scanningScreen_withDevices_showsDeviceList(): Unit {
        val devices: List<DiscoveredDevice> = listOf(
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
    fun scanningScreen_deviceClick_invokesCallback(): Unit {
        val device: DiscoveredDevice = DiscoveredDevice("Sensor A", "00:11:22:33:44:55", mockk(relaxed = true))
        var clickedDevice: DiscoveredDevice? = null
        composeTestRule.setContent {
            ScanningScreen(devices = listOf(device), onDeviceClicked = { clickedDevice = it }, onStopScanClicked = {})
        }
        composeTestRule.onNodeWithText("Sensor A").performClick()
        assert(clickedDevice == device)
    }

    @Test
    fun connectedScreen_nullCo2_showsDashes(): Unit {
        val viewModel: SensorViewModel = createMockViewModel()
        composeTestRule.setContent {
            ConnectedScreen(viewModel = viewModel)
        }
        composeTestRule.onAllNodesWithText("-- ppm").onFirst().assertIsDisplayed()
    }

    @Test
    fun connectedScreen_disconnectButton_invokesCallback(): Unit {
        val viewModel: SensorViewModel = createMockViewModel()
        composeTestRule.setContent {
            ConnectedScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Disconnect").performClick()
        verify { viewModel.disconnect() }
    }

    @Test
    fun errorScreen_withBothCallbacks_showsBothButtons(): Unit {
        composeTestRule.setContent {
            ErrorScreen(
                message = "Test error",
                onRerequestClicked = {},
                onSettingsClicked = {},
                firstButtonText = "Rerequest Permissions"
            )
        }
        composeTestRule.onNodeWithText("An Error Occurred").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rerequest Permissions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Go to Settings").assertIsDisplayed()
    }

    @Test
    fun errorScreen_withNoCallbacks_showsNoButtons(): Unit {
        composeTestRule.setContent {
            ErrorScreen(message = "Test error", onRerequestClicked = {}, onSettingsClicked = null, firstButtonText = "Restart Scan")
        }
        composeTestRule.onNodeWithText("Go to Settings").assertDoesNotExist()
    }

    @Test
    fun mainScreen_routing_workflow(): Unit {
        val mockViewModel: SensorViewModel = createMockViewModel()

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
        composeTestRule.onNodeWithText("500 ppm").assertIsDisplayed()
    }

    @Test
    fun mainScreen_historyTabRouting(): Unit {
        val mockViewModel: SensorViewModel = createMockViewModel()
        mockState.value = BleConnectionState.Connected(mockk(relaxed = true))
        mockTab.value = 1 // History tab

        composeTestRule.setContent {
            MainScreen(viewModel = mockViewModel, activity = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithText("Sensor History").assertIsDisplayed()
    }

    @Test
    fun mainScreen_errorRouting_permissionLogic(): Unit {
        val mockViewModel: SensorViewModel = createMockViewModel()
        val mockActivity: MainActivity = mockk(relaxed = true)

        composeTestRule.setContent {
            MainScreen(viewModel = mockViewModel, activity = mockActivity)
        }

        mockState.value = BleConnectionState.Error("Bluetooth permission denied")

        composeTestRule.onNodeWithText("Rerequest Permissions").assertIsDisplayed().performClick()
        verify { mockActivity.reRequestPermissions() }

        composeTestRule.onNodeWithText("Go to Settings").assertIsDisplayed().performClick()
        verify { mockActivity.openAppSettings() }
    }

    @Test
    fun mainScreen_errorRouting_genericLogic(): Unit {
        val mockViewModel: SensorViewModel = createMockViewModel()
        val mockActivity: MainActivity = mockk(relaxed = true)

        composeTestRule.setContent {
            MainScreen(viewModel = mockViewModel, activity = mockActivity)
        }

        mockState.value = BleConnectionState.Error("Device Busy")

        composeTestRule.onNodeWithText("Restart Scan").assertIsDisplayed().performClick()
        verify { mockViewModel.startScan() }
        verify(exactly = 0) { mockActivity.reRequestPermissions() }

        composeTestRule.onNodeWithText("Go to Settings").assertDoesNotExist()
    }
}
