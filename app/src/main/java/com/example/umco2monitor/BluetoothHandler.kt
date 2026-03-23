package com.example.umco2monitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.welie.blessed.BluetoothBytesParser
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.ConnectionPriority
import com.welie.blessed.from16BitString
import com.welie.blessed.GattStatus
import com.welie.blessed.HciStatus
import com.welie.blessed.ScanFailure
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteOrder
import java.util.UUID

/**
 * Singleton object that handles all BLE operations for the application.
 *
 * This handler is responsible for scanning, connecting, and interacting with the CO2 sensor
 * peripheral. It uses the BLESSED library to handle the underlying BLE operations and exposes the
 * connection state and sensor data to the rest of the app via StateFlows.
 *
 * It is designed to be initialized once in the application's lifecycle and used throughout the app.
 */
@SuppressLint("StaticFieldLeak")
object BluetoothHandler {

    // --- BLE Core Components ---
    // Create the BluetoothCentralManager object
    private lateinit var centralManager: BluetoothCentralManager
    private var isInitialized = false

    // Create a dedicated thread for BLE operations
    private val handlerThread = HandlerThread("BlessedBleThread", Process.THREAD_PRIORITY_DEFAULT)
    private lateinit var bleHandler: Handler

    // Set the scope for launching coroutines
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO
    private var scope = CoroutineScope(dispatcher + SupervisorJob())

    // --- Public StateFlows ---
    private val _bleConnectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    /**
     * Public state flow for BLE connection state.
     */
    val bleConnectionState = _bleConnectionState.asStateFlow()

    private val _co2Value = MutableStateFlow<UShort?>(null)
    /**
     * Public state flow for CO2 value.
     */
    val co2Value = _co2Value.asStateFlow()

    private val _temperatureValue = MutableStateFlow<Short?>(null)
    /**
     * Public state flow for temperature value.
     */
    val temperatureValue = _temperatureValue.asStateFlow()

    private val _humidityValue = MutableStateFlow<UShort?>(null)
    /**
     * Public state flow for humidity value.
     */
    val humidityValue = _humidityValue.asStateFlow()

    private val _batteryLevel = MutableStateFlow<UInt?>(null)
    /**
     * Public state flow for battery level.
     */
    val batteryLevel = _batteryLevel.asStateFlow()

    // --- UUID Definitions ---
    // UUIDs for the CO2 sensor
    private val SENSOR_SERVICE_UUID: UUID = UUID.fromString("3e6cebcd-d4f8-46e2-9513-056d94a6377c")
    private val CO2_CHARACTERISTIC_UUID: UUID = UUID.fromString("b70c91c7-40b6-461f-aeff-4b15a16fd0e7")

    // UUIDs for the standardized Environmental Sensing Service (ESS) with temperature and humidity characteristics
    private val ESS_SERVICE_UUID: UUID = from16BitString("181A")
    private val TEMPERATURE_CHARACTERISTIC_UUID: UUID = from16BitString("2A6E")
    private val HUMIDITY_CHARACTERISTIC_UUID: UUID = from16BitString("2A6F")

    // UUIDs for the Device Information service (DIS)
    private val DIS_SERVICE_UUID: UUID = from16BitString("180A")
    private val MANUFACTURER_NAME_CHARACTERISTIC_UUID: UUID = from16BitString("2A29")
    private val MODEL_NUMBER_CHARACTERISTIC_UUID: UUID = from16BitString("2A24")

    // UUIDs for the Battery Service (BAS)
    private val BTS_SERVICE_UUID: UUID = from16BitString("180F")
    private val BATTERY_LEVEL_CHARACTERISTIC_UUID: UUID = from16BitString("2A19")

    /**
     * Resets all sensor values to null. Used for testing and clean disconnections.
     * @param testDispatcher The dispatcher that tests will run on, instead of Dispatcher.IO
     */
    internal fun reset(testDispatcher: CoroutineDispatcher? = null) {
        _co2Value.value = null
        _temperatureValue.value = null
        _humidityValue.value = null
        _batteryLevel.value = null
        _bleConnectionState.value = BleConnectionState.Disconnected

        // Use the test dispatcher if provided, otherwise use the default dispatcher
        testDispatcher?.let {
            this.dispatcher = it
            this.scope = CoroutineScope(it + SupervisorJob())
            if (!::bleHandler.isInitialized) {
                bleHandler = mockk(relaxed = true)
            }
            if (!::centralManager.isInitialized) {
                centralManager = mockk(relaxed = true)
            }
        }
    }

    /**
     * Handles all events related to a specific, connected peripheral.
     */
    internal val peripheralCallback = object : BluetoothPeripheralCallback() {
        /**
         * Triggered when a peripheral's services are discovered.
         * @param peripheral The peripheral that was discovered.
         */
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            Timber.i("Services discovered for ${peripheral.name}")

            // Set connection priority to balanced and increase Maximum Transmission Unit from 23 to 185
            peripheral.requestConnectionPriority(ConnectionPriority.BALANCED)
//            peripheral.requestMtu(185)

            // Read DIS characteristics
            peripheral.readCharacteristic(DIS_SERVICE_UUID, MANUFACTURER_NAME_CHARACTERISTIC_UUID)
            peripheral.readCharacteristic(DIS_SERVICE_UUID, MODEL_NUMBER_CHARACTERISTIC_UUID)

            // Read the initial battery level and enable notifications
            peripheral.getCharacteristic(BTS_SERVICE_UUID, BATTERY_LEVEL_CHARACTERISTIC_UUID)?.let {
                Timber.i("Enabling notifications for battery level characteristic")
                peripheral.readCharacteristic(it)
                peripheral.startNotify(it) // Many devices notify on battery level change
            }

            // Enable notifications for the CO2 characteristic
            peripheral.getCharacteristic(SENSOR_SERVICE_UUID, CO2_CHARACTERISTIC_UUID)?.let {
                Timber.i("Enabling notifications for CO2 characteristic")
                peripheral.startNotify(it)
            }

            // Enable notifications for the ESS service, including temperature and humidity
            peripheral.getService(ESS_SERVICE_UUID)?.let { service ->
                Timber.i("Found Environmental Sensing Service")
                // Temperature
                service.getCharacteristic(TEMPERATURE_CHARACTERISTIC_UUID)?.let {
                    Timber.i("Enabling notifications for temperature characteristic")
                    peripheral.startNotify(it)
                }
                // Humidity
                service.getCharacteristic(HUMIDITY_CHARACTERISTIC_UUID)?.let {
                    Timber.i("Enabling notifications for humidity characteristic")
                    peripheral.startNotify(it)
                }
            }
        }

        /**
         * Triggered when notifications are turned on or off for a characteristic.
         * @param peripheral The peripheral that owns the relevant characteristic.
         * @param characteristic The characteristic that has had its notification state change.
         * @param status The status of the notification state change.
         */
        override fun onNotificationStateUpdate(peripheral: BluetoothPeripheral, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            if (status == GattStatus.SUCCESS) {
                val characteristicName: String = when (characteristic.uuid) {
                    CO2_CHARACTERISTIC_UUID -> "CO2"
                    TEMPERATURE_CHARACTERISTIC_UUID -> "Temperature"
                    HUMIDITY_CHARACTERISTIC_UUID -> "Humidity"
                    BATTERY_LEVEL_CHARACTERISTIC_UUID -> "Battery Level"
                    else -> "Unknown Characteristic"
                }
                if (peripheral.isNotifying(characteristic)) {
                    Timber.i("SUCCESS: Notifications are ON for $characteristicName characteristic")
                } else {
                    Timber.i("SUCCESS: Notifications are OFF for $characteristicName characteristic")
                }
            } else {
                Timber.e("ERROR: Changing notification state failed for ${characteristic.uuid} with status $status")
            }
        }

        /**
         * Triggered when the value of a characteristic is updated. Parses the raw data into a
         * usable data type and updates the corresponding StateFlow.
         * @param peripheral The peripheral that owns the relevant characteristic.
         * @param value The new value of the characteristic.
         * @param characteristic The characteristic that has had its value updated.
         * @param status The status of the value update.
         */
        override fun onCharacteristicUpdate(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            if (status != GattStatus.SUCCESS) return

            // Create a parser object to convert the incoming data from bytes to their intended data types
            val parser = BluetoothBytesParser(value, offset = 0, byteOrder = ByteOrder.LITTLE_ENDIAN)

            when (characteristic.uuid) {
                CO2_CHARACTERISTIC_UUID -> {
                    // Check if there are enough bytes for a 16-bit unsigned integer (2)
                    if (value.size >= 2) {
                        val co2: UShort = parser.getUInt16()
                        Timber.i("Received CO2 value: $co2")
                        scope.launch { _co2Value.emit(co2) }
                    }
                }

                TEMPERATURE_CHARACTERISTIC_UUID -> {
                    // Check if there are enough bytes for a 16-bit signed integer (2)
                    if (value.size >= 2) {
                        val rawTemp: Short = parser.getInt16()
                        val temp: Short = (rawTemp / 100.0).toInt().toShort()
                        Timber.i("Received Temperature value: $temp")
                        scope.launch { _temperatureValue.emit(temp) }
                    }
                }

                HUMIDITY_CHARACTERISTIC_UUID -> {
                    // Check if there are enough bytes for a 16-bit unsigned integer (2)
                    if (value.size >= 2) {
                        val humidity: UShort = parser.getUInt16()
                        Timber.i("Received Humidity value: $humidity")
                        scope.launch { _humidityValue.emit(humidity) }
                    }
                }

                BATTERY_LEVEL_CHARACTERISTIC_UUID -> {
                    // Check if there are enough bytes for an 8-bit unsigned integer (1)
                    if (value.isNotEmpty()) {
                        val batteryLevel: UInt = parser.getUInt8()
                        Timber.i("Received Battery Level: $batteryLevel%")
                        scope.launch { _batteryLevel.emit(batteryLevel) }
                    }
                }
            }
        }
    }

    /**
     * Handles all events related to the central manager. This includes scanning, connecting, and
     * disconnecting from peripherals.
     */
    internal val centralManagerCallback = object : BluetoothCentralManagerCallback() {
        /**
         * Triggered when a peripheral is discovered. Adds the discovered peripheral to the list of
         * discovered devices if necessary.
         * @param peripheral The peripheral object representing the device that was found.
         * @param scanResult The raw scan result data, containing RSSI and advertising information.
         */
        override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            if (peripheral.name.isBlank()) return

            Timber.i("Discovered '${peripheral.name}' with address '${peripheral.address}'")
            val currentDevices = (_bleConnectionState.value as? BleConnectionState.Scanning)?.discoveredDevices ?: emptyList()
            if (currentDevices.none { it.address == peripheral.address }) {
                val newDevice = DiscoveredDevice(peripheral.name, peripheral.address, peripheral)
                _bleConnectionState.update {
                    if (it is BleConnectionState.Scanning) it.copy(discoveredDevices = it.discoveredDevices + newDevice) else it
                }
            }
        }

        /**
         * Triggered when a peripheral is connected. Sets the connection state to connected.
         * @param peripheral The peripheral that was connected.
         */
        override fun onConnected(peripheral: BluetoothPeripheral) {
            Timber.i("Connected to ${peripheral.name}")
            _bleConnectionState.value = BleConnectionState.Connected(peripheral)
        }

        /**
         * Triggered when a peripheral is disconnected. Sets the connection state to disconnected,
         * and resets all sensor values.
         * @param peripheral The peripheral that was disconnected.
         * @param status The HCI status of the (dis)connection.
         */
        override fun onDisconnected(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.i("Disconnected from ${peripheral.name}")
            _bleConnectionState.value = BleConnectionState.Disconnected
            // Reset all sensor values on disconnect
            scope.launch {
                _co2Value.emit(null)
                _temperatureValue.emit(null)
                _humidityValue.emit(null)
                _batteryLevel.emit(null)
            }
        }

        /**
         * Triggered when a connection attempt fails. Sets the connection state to error.
         * @param peripheral The peripheral that was being connected.
         * @param status The HCI status of the connection attempt.
         */
        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.e("Connection to ${peripheral.name} failed with status $status")
            _bleConnectionState.value = BleConnectionState.Error("Connection failed")
        }

        /**
         * Triggered when a scan fails. Sets the connection state to error.
         * @param scanFailure The ScanFailure that occurred.
         */
        override fun onScanFailed(scanFailure: ScanFailure) {
            Timber.e("Scan failed with error $scanFailure")
            _bleConnectionState.value = BleConnectionState.Error("Scan failed")
        }

        /**
         * Triggered when the Bluetooth adapter state changes. Sets the connection state to error
         * if the adapter is turned off.
         * @param state The new Bluetooth adapter state.
         */
        override fun onBluetoothAdapterStateChanged(state: Int) {
            if (state == BluetoothAdapter.STATE_OFF) {
                Timber.e("Bluetooth adapter turned off")
                _bleConnectionState.value = BleConnectionState.Error("Bluetooth is off")
            }
        }
    }

    // Public functions to be called from the ViewModel
    /**
     * Initializes the Bluetooth handler, creates a thread for BLE operations, and initializes the
     * central manager.
     * @param context The application context.
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        Timber.plant(Timber.DebugTree())

        // Start the dedicated BLE thread and create its handler
        handlerThread.start()
        bleHandler = Handler(handlerThread.looper)

        // Initialize the central manager
        this.centralManager = BluetoothCentralManager(context.applicationContext, centralManagerCallback, bleHandler)
        isInitialized = true
    }

    /**
     * Starts a scan for BLE peripherals.
     */
    fun startScan() {
        bleHandler.postDelayed({ stopScan() }, 15000) // Stop scan after 15 seconds
        _bleConnectionState.value = BleConnectionState.Scanning(emptyList())
        centralManager.scanForPeripheralsWithServices(setOf(SENSOR_SERVICE_UUID))
    }

    /**
     * Stops the current scan if one is in progress.
     */
    fun stopScan() {
        if (centralManager.isScanning) {
            centralManager.stopScan()
            if (_bleConnectionState.value is BleConnectionState.Scanning) {
                _bleConnectionState.value = BleConnectionState.Disconnected
            }
        }
    }

    /**
     * Stops the scan and connects to a peripheral.
     * @param peripheral The peripheral to connect to.
     */
    fun connect(peripheral: BluetoothPeripheral) {
        centralManager.stopScan()
        _bleConnectionState.value = BleConnectionState.Connected(peripheral)
        centralManager.connect(peripheral, peripheralCallback)
    }

    /**
     * Disconnects from the connected peripheral.
     * @param peripheral The peripheral to disconnect from.
     */
    fun disconnect(peripheral: BluetoothPeripheral) {
        centralManager.cancelConnection(peripheral)
    }
}
