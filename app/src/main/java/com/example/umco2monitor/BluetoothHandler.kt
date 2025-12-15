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

@SuppressLint("StaticFieldLeak")
object BluetoothHandler {

    // Create the BluetoothCentralManager object
    private lateinit var centralManager: BluetoothCentralManager
    private var isInitialized = false

    // Create a dedicated thread for BLE operations
    private val handlerThread = HandlerThread("BlessedBleThread", Process.THREAD_PRIORITY_DEFAULT)
    private lateinit var bleHandler: Handler

    // State Flows to communicate with the UI (ViewModel)
    private val _bleConnectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val bleConnectionState = _bleConnectionState.asStateFlow()

    private val _co2Value = MutableStateFlow<UShort?>(null)
    val co2Value = _co2Value.asStateFlow()

    private val _temperatureValue = MutableStateFlow<Double?>(null)
    val temperatureValue = _temperatureValue.asStateFlow()

    private val _humidityValue = MutableStateFlow<Double?>(null)
    val humidityValue = _humidityValue.asStateFlow()

    private val _batteryLevel = MutableStateFlow<UShort?>(null)
    val batteryLevel = _batteryLevel.asStateFlow()

    // Scope for launching coroutines
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    // Callback for a connected peripheral
    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            // Triggered when a peripheral's services are discovered
            Timber.i("Services discovered for ${peripheral.name}")

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

        override fun onNotificationStateUpdate(peripheral: BluetoothPeripheral, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            // Triggered when notifications are turned on or off for a characteristic
            if (status == GattStatus.SUCCESS) {
                val characteristicName = when (characteristic.uuid) {
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

        override fun onCharacteristicUpdate(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            // Triggered when the value of a characteristic is updated
            if (status != GattStatus.SUCCESS) return

            // Create a parser object to convert the incoming data from bytes to their intended data types
            val parser = BluetoothBytesParser(value, byteOrder = ByteOrder.LITTLE_ENDIAN)

            when (characteristic.uuid) {
                CO2_CHARACTERISTIC_UUID -> {
                    // Check if there are enough bytes for a 16-bit unsigned integer
                    if (value.size >= 2) {
                        val co2: UShort = parser.getUInt16()
                        Timber.i("Received CO2 value: $co2")
                        scope.launch { _co2Value.emit(co2) }
                    }
                }

                TEMPERATURE_CHARACTERISTIC_UUID -> {
                    // Check if there are enough bytes for a 32-bit float
                    if (value.size >= 4) {
                        val temp: Double = parser.getFloat()
                        Timber.i("Received Temperature value: $temp")
                        scope.launch { _temperatureValue.emit(temp) }
                    }
                }

                HUMIDITY_CHARACTERISTIC_UUID -> {
                    // Check if there are enough bytes for a 32-bit float
                    if (value.size >= 4) {
                        // CLEANER WAY: Use the parser to get the float value
                        val humidity: Double = parser.getFloat()
                        Timber.i("Received Humidity value: $humidity")
                        scope.launch { _humidityValue.emit(humidity) }
                    }
                }

                // Add cases for other characteristics like Battery Level here
                BATTERY_LEVEL_CHARACTERISTIC_UUID -> {
                    if (value.size >= 2) {
                        val batteryLevel: UShort = parser.getUInt16()
                        Timber.i("Received Battery Level: $batteryLevel%")
                        scope.launch { _batteryLevel.emit(batteryLevel) }
                    }
                }
            }
        }
    }

    // Callback for the central manager
    private val centralManagerCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            // Triggered when a peripheral is discovered
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

        override fun onConnected(peripheral: BluetoothPeripheral) {
            // Triggered when a peripheral is connected
            Timber.i("Connected to ${peripheral.name}")
            _bleConnectionState.value = BleConnectionState.Connected(peripheral)
        }

        override fun onDisconnected(peripheral: BluetoothPeripheral, status: HciStatus) {
            // Triggered when a peripheral is disconnected
            Timber.i("Disconnected from ${peripheral.name}")
            _bleConnectionState.value = BleConnectionState.Disconnected
            scope.launch { _co2Value.emit(null) }
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            // Triggered when a connection attempt fails
            Timber.e("Connection to ${peripheral.name} failed with status $status")
            _bleConnectionState.value = BleConnectionState.Error("Connection failed")
        }

        override fun onScanFailed(scanFailure: ScanFailure) {
            // Triggered when a scan fails
            Timber.e("Scan failed with error $scanFailure")
            _bleConnectionState.value = BleConnectionState.Error("Scan failed")
        }

        override fun onBluetoothAdapterStateChanged(state: Int) {
            // Triggered when the Bluetooth adapter state changes
            if (state == BluetoothAdapter.STATE_OFF) {
                Timber.e("Bluetooth adapter turned off")
                _bleConnectionState.value = BleConnectionState.Error("Bluetooth is off")
            }
        }
    }

    // Public functions to be called from the ViewModel
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

    fun startScan() {
        bleHandler.postDelayed({ stopScan() }, 15000) // Stop scan after 15 seconds
        _bleConnectionState.value = BleConnectionState.Scanning(emptyList())
        centralManager.scanForPeripheralsWithServices(setOf(SENSOR_SERVICE_UUID))
    }

    fun stopScan() {
        if (centralManager.isScanning) {
            centralManager.stopScan()
            if (_bleConnectionState.value is BleConnectionState.Scanning) {
                _bleConnectionState.value = BleConnectionState.Disconnected
            }
        }
    }

    fun connect(peripheral: BluetoothPeripheral) {
        stopScan()
        centralManager.connect(peripheral, peripheralCallback)
    }

    fun disconnect(peripheral: BluetoothPeripheral) {
        centralManager.cancelConnection(peripheral)
    }
}
