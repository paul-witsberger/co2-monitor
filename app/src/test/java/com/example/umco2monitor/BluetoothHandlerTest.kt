package com.example.umco2monitor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.from16BitString
import com.welie.blessed.GattStatus
import com.welie.blessed.HciStatus
import com.welie.blessed.ScanFailure
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import java.util.UUID
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Local unit tests for BluetoothHandler.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothHandlerTest {

    private lateinit var testDispatcher: CoroutineDispatcher
    @BeforeEach
    fun setup() {
        // Clear old data from previous tests and set up the test dispatcher
        testDispatcher = StandardTestDispatcher()
        BluetoothHandler.reset(testDispatcher)
    }

    @Test
    fun onCharacteristicUpdate_parsesCO2Correctly() = runTest(testDispatcher) {
        // Use mock to create objects that can't be created here
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>()
        val characteristic: BluetoothGattCharacteristic = mockk<BluetoothGattCharacteristic>()

        // Use mock to return the CO2 UUID when asked
        every { characteristic.uuid } returns UUID.fromString(
            "b70c91c7-40b6-461f-aeff-4b15a16fd0e7")

        // Create two 2-byte arrays, corresponding to readings of 500ppm and of 45000ppm
        val value500Ppm: ByteArray = byteArrayOf(0xF4.toByte(), 0x01)
        val value45000Ppm: ByteArray = byteArrayOf(0xC8.toByte(), 0xAF.toByte())

        // Define the status as success
        val status: GattStatus = GattStatus.SUCCESS

        // Call onCharacteristicUpdate for the first reading
        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, value500Ppm,
            characteristic, status)
        advanceUntilIdle()
        assertEquals(500.toUShort(), BluetoothHandler.co2Value.value)

        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, value45000Ppm,
            characteristic, status)
        advanceUntilIdle()
        assertEquals(45000.toUShort(), BluetoothHandler.co2Value.value)
    }

    @Test
    fun onCharacteristicUpdate_parsesTemperatureCorrectly() = runTest(testDispatcher) {
        // Use mock to create objects that can't be created here
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>()
        val characteristic: BluetoothGattCharacteristic = mockk<BluetoothGattCharacteristic>()

        // Use mock to return the temperature UUID when asked
        every { characteristic.uuid } returns from16BitString("2A6E")

        // Create two 2-byte arrays, corresponding to readings of 68 F (20 C) and of 104 F (40 C)
        val value20C: ByteArray = byteArrayOf(0xD0.toByte(), 0x07)
        val value40C: ByteArray = byteArrayOf(0xA0.toByte(), 0x0F.toByte())
        val expected20C: Float = 20f * 1.8f + 32f
        val expected40C: Float = 40f * 1.8f + 32f


        // Define the status as success
        val status: GattStatus = GattStatus.SUCCESS

        // Call onCharacteristicUpdate on both readings
        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, value20C,
            characteristic, status)
        advanceUntilIdle()
        assertEquals(expected20C, BluetoothHandler.temperatureValue.value)

        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, value40C,
            characteristic, status)
        advanceUntilIdle()
        assertEquals(expected40C, BluetoothHandler.temperatureValue.value)
    }

    @Test
    fun onCharacteristicUpdate_parsesHumidityCorrectly() = runTest(testDispatcher) {
        // Use mock to create objects that can't be created here
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>()
        val characteristic: BluetoothGattCharacteristic = mockk<BluetoothGattCharacteristic>()

        // Use mock to return the humidity UUID when asked
        every { characteristic.uuid } returns from16BitString("2A6F")

        // Create two 2-byte arrays, corresponding to readings of 0% and of 100%
        val value0Percent: ByteArray = byteArrayOf(0x00, 0x00)
        val value100Percent: ByteArray = byteArrayOf(0x10, 0x27)

        // Define the status as success
        val status: GattStatus = GattStatus.SUCCESS

        // Call onCharacteristicUpdate on both readings
        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, value0Percent,
            characteristic, status)
        advanceUntilIdle()
        assertEquals(0f, BluetoothHandler.humidityValue.value)

        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, value100Percent,
            characteristic, status)
        advanceUntilIdle()
        assertEquals(100f, BluetoothHandler.humidityValue.value)
    }

    @Test
    fun onCharacteristicUpdate_parsesBatteryCorrectly() = runTest(testDispatcher) {
        // Use mock to create objects that can't be created here
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>()
        val characteristic: BluetoothGattCharacteristic = mockk<BluetoothGattCharacteristic>()

        // Use mock to return the battery level UUID when asked
        every { characteristic.uuid } returns from16BitString("2A19")

        // Create two 1-byte arrays, corresponding to readings of 0% and of 100%
        val value0Percent: ByteArray = byteArrayOf(0x00)
        val value100Percent: ByteArray = byteArrayOf(0x64)

        // Define the status as success
        val status: GattStatus = GattStatus.SUCCESS

        // Call onCharacteristicUpdate on both readings
        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, value0Percent,
            characteristic, status)
        advanceUntilIdle()
        assertEquals(0.toUInt(), BluetoothHandler.batteryLevel.value)

        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, value100Percent,
            characteristic, status)
        advanceUntilIdle()
        assertEquals(100.toUInt(), BluetoothHandler.batteryLevel.value)
    }

    @Test
    fun onCharacteristicUpdate_ignoresFailedStatus() = runTest(testDispatcher) {
        // Use mock to create objects that can't be created here
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>()
        val characteristic: BluetoothGattCharacteristic = mockk<BluetoothGattCharacteristic>()

        // Use mock to return the CO2 UUID when asked
        every { characteristic.uuid } returns UUID.fromString(
            "b70c91c7-40b6-461f-aeff-4b15a16fd0e7")

        // Create an empty byte array
        val value: ByteArray = byteArrayOf()

        // Define the status as success
        val status: GattStatus = GattStatus.ERROR

        // Get current value of CO2
        val previousValue = BluetoothHandler.co2Value.value

        // Call onCharacteristicUpdate
        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, value,
            characteristic, status)
        advanceUntilIdle()

        // Assert that the CO2 value has not changed
        assertEquals(previousValue, BluetoothHandler.co2Value.value)
    }

    @Test
    fun onServicesDiscovered_verifyMethodCalls() = runTest(testDispatcher) {
        // Use mock to create objects that can't be created here
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>(relaxed = true)
        val service: BluetoothGattService = mockk<BluetoothGattService>()

        // Simulate the ESS Service when getService is called
        every { peripheral.getService(any()) } returns service
        // Simulate characteristics when getCharacteristic is called
        every { peripheral.getCharacteristic(any(), any()) } returns mockk(relaxed = true)
        every { service.getCharacteristic(any()) } returns mockk(relaxed = true)

        // Call onServicesDiscovered
        BluetoothHandler.peripheralCallback.onServicesDiscovered(peripheral)
        advanceUntilIdle()

        // Verify that the correct methods are called
        verify { peripheral.requestConnectionPriority(any()) }
        verify { peripheral.readCharacteristic(any(), any()) }
        verify { peripheral.startNotify(any()) }
    }

    @Test
    fun onNotificationStateUpdate_verifyMethodCalls() = runTest(testDispatcher) {
        // Create mock peripheral and characteristic
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>(relaxed = true)
        val characteristic: BluetoothGattCharacteristic = mockk<BluetoothGattCharacteristic>()

        // Set the UUID to the CO2 characteristic
        every { characteristic.uuid } returns UUID.fromString("b70c91c7-40b6-461f-aeff-4b15a16fd0e7")

        // Simulate that notifications are on for the CO2 characteristic
        every { peripheral.isNotifying(characteristic) } returns true

        // Set the GattStatus to success
        val status: GattStatus = GattStatus.SUCCESS

        // Call onNotificationStateUpdate
        BluetoothHandler.peripheralCallback.onNotificationStateUpdate(peripheral, characteristic, status)
        advanceUntilIdle()

        // Verify that the notification state is checked
        verify { peripheral.isNotifying(characteristic) }
    }

    @Test
    fun onNotificationStateUpdate_errorHandling() = runTest(testDispatcher) {
        // Create mock peripheral and characteristic
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>(relaxed = true)
        val characteristic: BluetoothGattCharacteristic = mockk<BluetoothGattCharacteristic>()

        // Use a random UUID for the characteristic
        every { characteristic.uuid } returns UUID.randomUUID()

        // Set the GattStatus to success
        val status: GattStatus = GattStatus.SUCCESS

        // Call onNotificationStateUpdate
        BluetoothHandler.peripheralCallback.onNotificationStateUpdate(peripheral, characteristic, status)
        advanceUntilIdle()

        // Verify that the notification state is checked
        verify { peripheral.isNotifying(characteristic) }
    }

    @Test
    fun onDiscovered_addsNewDevice() = runTest(testDispatcher) {
        // Put app into scanning state
        BluetoothHandler.startScan()
        advanceUntilIdle()

        // Create a mock peripheral
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>(relaxed = true)
        every { peripheral.name } returns "Test Device"
        every { peripheral.address } returns "00:00:00:00:00:00"

        // Call onDiscovered
        BluetoothHandler.centralManagerCallback.onDiscovered(peripheral, mockk())
        advanceUntilIdle()

        // Verify that the device was added to the list
        val state: BleConnectionState = BluetoothHandler.bleConnectionState.value
        assert(state is BleConnectionState.Scanning)
        val devices: List<DiscoveredDevice> = (state as BleConnectionState.Scanning).discoveredDevices
        assertEquals(1, devices.size)
        assertEquals("Test Device", devices[0].name)
        assertEquals("00:00:00:00:00:00", devices[0].address)
    }

    @Test
    fun onDiscovered_ignoresDuplicateDevice() = runTest(testDispatcher) {
        // Put app into scanning state
        BluetoothHandler.startScan()
        advanceUntilIdle()

        // Create a mock peripheral
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>(relaxed = true)
        every { peripheral.name } returns "Test Device"
        every { peripheral.address } returns "00:00:00:00:00:00"

        // Call onDiscovered twice with the same device
        BluetoothHandler.centralManagerCallback.onDiscovered(peripheral, mockk())
        advanceUntilIdle()
        BluetoothHandler.centralManagerCallback.onDiscovered(peripheral, mockk())
        advanceUntilIdle()

        // Verify that the device was only added once
        val state: BleConnectionState.Scanning = BluetoothHandler.bleConnectionState.value as BleConnectionState.Scanning
        assertEquals(1, state.discoveredDevices.size)
    }

    @Test
    fun onDiscovered_ignoresBlankNames() = runTest(testDispatcher) {
        // Put app into scanning state
        BluetoothHandler.startScan()
        advanceUntilIdle()

        // Create a mock peripheral with a blank name
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>(relaxed = true)
        every { peripheral.name } returns ""

        // Call onDiscovered
        BluetoothHandler.centralManagerCallback.onDiscovered(peripheral, mockk())
        advanceUntilIdle()

        // Check that no devices were added
        val state: BleConnectionState.Scanning = BluetoothHandler.bleConnectionState.value as BleConnectionState.Scanning
        assertEquals(0, state.discoveredDevices.size)
    }

    @Test
    fun onConnected_updatesState() = runTest(testDispatcher) {
        // Create a mock peripheral
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>(relaxed = true)

        // Create a mock device
        every { peripheral.name } returns "Test Device"
        every { peripheral.address } returns "00:00:00:00:00:00"

        // Call onConnected
        BluetoothHandler.centralManagerCallback.onConnected(peripheral)
        advanceUntilIdle()

        // Verify that the state is Connected and the correct peripheral is set
        val state: BleConnectionState = BluetoothHandler.bleConnectionState.value
        assert(state is BleConnectionState.Connected)

        val connectedState: BleConnectionState.Connected = state as BleConnectionState.Connected
        assertEquals(peripheral, connectedState.peripheral)
        assertEquals("Test Device", connectedState.peripheral.name)
    }

    @Test
    fun onDisconnected_resetsState() = runTest(testDispatcher) {
        // Create a mock peripheral
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>(relaxed = true)

        // Create a characteristic and give it a value
        val characteristic: BluetoothGattCharacteristic = mockk<BluetoothGattCharacteristic>()
        every { characteristic.uuid } returns UUID.fromString("b70c91c7-40b6-461f-aeff-4b15a16fd0e7")
        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, byteArrayOf(0xF4.toByte(), 0x01), characteristic, GattStatus.SUCCESS)
        advanceUntilIdle()
        assertEquals(500.toUShort(), BluetoothHandler.co2Value.value)

        // Call onDisconnected
        BluetoothHandler.centralManagerCallback.onDisconnected(peripheral, HciStatus.SUCCESS)
        advanceUntilIdle()

        // Verify the state is Disconnected and that values have been reset
        val state: BleConnectionState = BluetoothHandler.bleConnectionState.value
        assert(state is BleConnectionState.Disconnected)
        assertEquals(null, BluetoothHandler.co2Value.value)
        assertEquals(null, BluetoothHandler.temperatureValue.value)
        assertEquals(null, BluetoothHandler.humidityValue.value)
        assertEquals(null, BluetoothHandler.batteryLevel.value)
    }

    @Test
    fun onConnectionFailed_throwsError() = runTest(testDispatcher) {
        // Create a mock peripheral
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>(relaxed = true)
        every { peripheral.name } returns "Test Device"

        // Call onConnectionFailed
        BluetoothHandler.centralManagerCallback.onConnectionFailed(peripheral, HciStatus.ERROR)
        advanceUntilIdle()

        // Verify that the state is Error
        val state: BleConnectionState = BluetoothHandler.bleConnectionState.value
        assert(state is BleConnectionState.Error)
        val errorMessage = (state as BleConnectionState.Error).message
        assert(errorMessage.contains("Connection failed"))
    }

    @Test
    fun onScanFailed_throwsError() = runTest(testDispatcher) {
        // Start a scan
        BluetoothHandler.startScan()
        advanceUntilIdle()

        // Call onScanFailed
        BluetoothHandler.centralManagerCallback.onScanFailed(ScanFailure.OUT_OF_HARDWARE_RESOURCES)
        advanceUntilIdle()
        // Verify that the state is Error
        val state: BleConnectionState = BluetoothHandler.bleConnectionState.value
        assert(state is BleConnectionState.Error)
        assert((state as BleConnectionState.Error).message.contains("Scan failed"))
    }

    @Test
    fun onBluetoothAdapterStateChanged_stateChanges() = runTest(testDispatcher) {
        // Start a scan
        BluetoothHandler.startScan()
        advanceUntilIdle()

        // Turn Bluetooth off on the phone
        BluetoothHandler.centralManagerCallback.onBluetoothAdapterStateChanged(BluetoothAdapter.STATE_OFF)
        advanceUntilIdle()

        // Verify that the state is Disconnected
        val state: BleConnectionState = BluetoothHandler.bleConnectionState.value
        assert(state is BleConnectionState.Error)
    }

    @Test
    fun startScan_scanIsStarted() = runTest(testDispatcher) {
        // Start a scan
        BluetoothHandler.startScan()
        advanceUntilIdle()

        // Verify that the state is Scanning
        val state: BleConnectionState = BluetoothHandler.bleConnectionState.value
        assert(state is BleConnectionState.Scanning)

        // Verify that the central manager is scanning
        verify { BluetoothHandler.centralManager.scanForPeripheralsWithServices(any()) }

        // REMOVED: The timer has been removed from the scanner
        // Verify that the timer was set on the bleHandler
//        verify { BluetoothHandler.bleHandler.postDelayed(any(), 15000) }
    }

    @Test
    fun stopScan_scanHasStopped() = runTest(testDispatcher) {
        // Start scanning and mock that centralManager.isScanning is true
        every { BluetoothHandler.centralManager.isScanning } returns true
        BluetoothHandler.startScan()
        advanceUntilIdle()

        // Stop scanning
        BluetoothHandler.stopScan()
        advanceUntilIdle()

        // Verify that the state is Disconnected and the central manager was called
        val state: BleConnectionState = BluetoothHandler.bleConnectionState.value
        assert(state is BleConnectionState.Disconnected)
        verify { BluetoothHandler.centralManager.stopScan() }
    }

    @Test
    fun connect_callsManagerAndUpdatesState() = runTest(testDispatcher) {
        // Create a mock peripheral
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>(relaxed = true)

        // Call connect
        BluetoothHandler.connect(peripheral)
        advanceUntilIdle()

        // Verify that the state is Connected and the correct peripheral is set
        val state: BleConnectionState = BluetoothHandler.bleConnectionState.value
        assert(state is BleConnectionState.Connecting)
        assertEquals(peripheral.name, (state as BleConnectionState.Connecting).deviceName)

        // Connect to the device
        BluetoothHandler.centralManagerCallback.onConnected(peripheral)
        advanceUntilIdle()

        // Verify that the state is Connected and the correct peripheral is set
        val connectedState: BleConnectionState = BluetoothHandler.bleConnectionState.value
        assert(connectedState is BleConnectionState.Connected)
        assertEquals(peripheral, (connectedState as BleConnectionState.Connected).peripheral)

        // Verify that centralManager.connect was called
        verify { BluetoothHandler.centralManager.connect(peripheral, BluetoothHandler.peripheralCallback) }
    }

    @Test
    fun disconnect_callsManagerAndUpdatesState() = runTest(testDispatcher) {
        // Create a mock peripheral
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>(relaxed = true)

        // Call connect to set the state to Connected
        BluetoothHandler.connect(peripheral)
        advanceUntilIdle()

        // Verify that we are connected first
        BluetoothHandler.centralManagerCallback.onConnected(peripheral)
        advanceUntilIdle()
        assert(BluetoothHandler.bleConnectionState.value is BleConnectionState.Connected)

        // Call disconnect
        BluetoothHandler.disconnect(peripheral)
        advanceUntilIdle()

        // Verify that centralManager.disconnect was called
        verify { BluetoothHandler.centralManager.cancelConnection(peripheral) }

        // Simulate the onDisconnected callback
        BluetoothHandler.centralManagerCallback.onDisconnected(peripheral, HciStatus.SUCCESS)
        advanceUntilIdle()

        // Verify that the state is Disconnected
        val state: BleConnectionState = BluetoothHandler.bleConnectionState.value
        assert(state is BleConnectionState.Disconnected)
    }

    /**
     * Verifies that an unintentional disconnect triggers the auto-reconnect flow.
     */
    @Test
    fun onDisconnected_nonSuccessStatus_triggersAutoConnect() = runTest(testDispatcher) {
        // Create a mock peripheral
        val peripheral: BluetoothPeripheral = mockk<BluetoothPeripheral>(relaxed = true)
        every { peripheral.name } returns "Test Device"

        // Simulate an unintentional drop (e.g., connection timeout or device went out of range)
        BluetoothHandler.centralManagerCallback.onDisconnected(peripheral, HciStatus.CONNECTION_TIMEOUT)
        advanceUntilIdle()

        // Verify that the state transitions to Connecting
        val state: BleConnectionState = BluetoothHandler.bleConnectionState.value
        assert(state is BleConnectionState.Connecting)
        assertEquals("Test Device", (state as BleConnectionState.Connecting).deviceName)

        // Verify that autoConnect was called on the central manager
        verify { BluetoothHandler.centralManager.autoConnect(peripheral, BluetoothHandler.peripheralCallback) }
    }
}