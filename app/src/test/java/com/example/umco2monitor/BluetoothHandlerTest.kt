package com.example.umco2monitor

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.GattStatus
import com.welie.blessed.from16BitString
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import java.util.UUID
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber

/**
 * Local unit tests for BluetoothHandler.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

        // Define the status as success
        val status: GattStatus = GattStatus.SUCCESS

        // Call onCharacteristicUpdate on both readings
        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, value20C,
            characteristic, status)
        advanceUntilIdle()
        assertEquals(20.toShort(), BluetoothHandler.temperatureValue.value)

        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, value40C,
            characteristic, status)
        advanceUntilIdle()
        assertEquals(40.toShort(), BluetoothHandler.temperatureValue.value)
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
        val value100Percent: ByteArray = byteArrayOf(0x64, 0x00)

        // Define the status as success
        val status: GattStatus = GattStatus.SUCCESS

        // Call onCharacteristicUpdate on both readings
        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, value0Percent,
            characteristic, status)
        advanceUntilIdle()
        assertEquals(0.toUShort(), BluetoothHandler.humidityValue.value)

        BluetoothHandler.peripheralCallback.onCharacteristicUpdate(peripheral, value100Percent,
            characteristic, status)
        advanceUntilIdle()
        assertEquals(100.toUShort(), BluetoothHandler.humidityValue.value)
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


}