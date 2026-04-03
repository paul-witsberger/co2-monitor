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
import com.welie.blessed.GattStatus
import com.welie.blessed.HciStatus
import com.welie.blessed.ScanFailure
import com.welie.blessed.from16BitString
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    internal lateinit var centralManager: BluetoothCentralManager
    private var isInitialized: Boolean = false

    internal lateinit var handlerThread: HandlerThread
    internal lateinit var bleHandler: Handler

    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO
    private var scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    private val _bleConnectionState: MutableStateFlow<BleConnectionState> = MutableStateFlow(BleConnectionState.Disconnected)
    /**
     * Public state flow for BLE connection state.
     */
    val bleConnectionState: StateFlow<BleConnectionState> = _bleConnectionState.asStateFlow()

    private val _co2Value: MutableStateFlow<UShort?> = MutableStateFlow(null)
    /**
     * Public state flow for CO2 value.
     */
    val co2Value: StateFlow<UShort?> = _co2Value.asStateFlow()

    private val _temperatureValue: MutableStateFlow<Float?> = MutableStateFlow(null)
    /**
     * Public state flow for temperature value.
     */
    val temperatureValue: StateFlow<Float?> = _temperatureValue.asStateFlow()

    private val _humidityValue: MutableStateFlow<Float?> = MutableStateFlow(null)
    /**
     * Public state flow for humidity value.
     */
    val humidityValue: StateFlow<Float?> = _humidityValue.asStateFlow()

    private val _batteryLevel: MutableStateFlow<UInt?> = MutableStateFlow(null)
    /**
     * Public state flow for battery level.
     */
    val batteryLevel: StateFlow<UInt?> = _batteryLevel.asStateFlow()

    private val SENSOR_SERVICE_UUID: UUID = UUID.fromString("3e6cebcd-d4f8-46e2-9513-056d94a6377c")
    private val CO2_CHARACTERISTIC_UUID: UUID = UUID.fromString("b70c91c7-40b6-461f-aeff-4b15a16fd0e7")

    private val ESS_SERVICE_UUID: UUID = from16BitString("181A")
    private val TEMPERATURE_CHARACTERISTIC_UUID: UUID = from16BitString("2A6E")
    private val HUMIDITY_CHARACTERISTIC_UUID: UUID = from16BitString("2A6F")

    private val DIS_SERVICE_UUID: UUID = from16BitString("180A")
    private val MANUFACTURER_NAME_CHARACTERISTIC_UUID: UUID = from16BitString("2A29")
    private val MODEL_NUMBER_CHARACTERISTIC_UUID: UUID = from16BitString("2A24")

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
     * Used in instrumented testing to stop the bleHandler and handlerThread.
     */
    internal fun shutdown() {
        scope.cancel()
        scope = CoroutineScope(dispatcher + SupervisorJob())
        if (::bleHandler.isInitialized) {
            bleHandler.removeCallbacksAndMessages(null)
        }
        if (::handlerThread.isInitialized && handlerThread.isAlive) {
            handlerThread.quitSafely()
            handlerThread.join()
        }
        isInitialized = false
    }

    internal val peripheralCallback: BluetoothPeripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            Timber.i("Services discovered for ${peripheral.name}")

            peripheral.readCharacteristic(DIS_SERVICE_UUID, MANUFACTURER_NAME_CHARACTERISTIC_UUID)
            peripheral.readCharacteristic(DIS_SERVICE_UUID, MODEL_NUMBER_CHARACTERISTIC_UUID)

            peripheral.getCharacteristic(BTS_SERVICE_UUID, BATTERY_LEVEL_CHARACTERISTIC_UUID)?.let { characteristic: BluetoothGattCharacteristic ->
                Timber.i("Enabling notifications for battery level characteristic")
                peripheral.readCharacteristic(characteristic)
                peripheral.startNotify(characteristic)
            }

            peripheral.getCharacteristic(SENSOR_SERVICE_UUID, CO2_CHARACTERISTIC_UUID)?.let { characteristic: BluetoothGattCharacteristic ->
                Timber.i("Enabling notifications for CO2 characteristic")
                peripheral.startNotify(characteristic)
            }

            peripheral.getService(ESS_SERVICE_UUID)?.let { service ->
                Timber.i("Found Environmental Sensing Service")
                service.getCharacteristic(TEMPERATURE_CHARACTERISTIC_UUID)?.let { characteristic: BluetoothGattCharacteristic ->
                    Timber.i("Enabling notifications for temperature characteristic")
                    peripheral.startNotify(characteristic)
                }
                service.getCharacteristic(HUMIDITY_CHARACTERISTIC_UUID)?.let { characteristic: BluetoothGattCharacteristic ->
                    Timber.i("Enabling notifications for humidity characteristic")
                    peripheral.startNotify(characteristic)
                }
            }

            peripheral.requestConnectionPriority(ConnectionPriority.BALANCED)
        }

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

        override fun onCharacteristicUpdate(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            if (status != GattStatus.SUCCESS) return

            val parser: BluetoothBytesParser = BluetoothBytesParser(value, offset = 0, byteOrder = ByteOrder.LITTLE_ENDIAN)

            when (characteristic.uuid) {
                CO2_CHARACTERISTIC_UUID -> {
                    val co2: UShort = parser.getUInt16()
                    Timber.i("Received CO2 value: $co2")
                    scope.launch { _co2Value.emit(co2) }
                }

                TEMPERATURE_CHARACTERISTIC_UUID -> {
                    val rawTemp: Short = parser.getInt16()
                    val tempC: Float = rawTemp / 100.0f
                    val temp: Float = (tempC * 9.0f / 5.0f) + 32f
                    Timber.i("Received Temperature value: $temp")
                    scope.launch { _temperatureValue.emit(temp) }
                }

                HUMIDITY_CHARACTERISTIC_UUID -> {
                    val rawHumidity: UShort = parser.getUInt16()
                    val humidity: Float = rawHumidity.toFloat() / 100.0f
                    Timber.i("Received Humidity value: $humidity")
                    scope.launch { _humidityValue.emit(humidity) }
                }

                BATTERY_LEVEL_CHARACTERISTIC_UUID -> {
                    if (value.isNotEmpty()) {
                        val batteryLevel: UInt = parser.getUInt8()
                        Timber.i("Received Battery Level: $batteryLevel%")
                        scope.launch { _batteryLevel.emit(batteryLevel) }
                    }
                }
            }
        }
    }

    internal val centralManagerCallback: BluetoothCentralManagerCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            if (peripheral.name.isBlank()) return

            Timber.i("Discovered '${peripheral.name}' with address '${peripheral.address}'")
            val currentState: BleConnectionState = _bleConnectionState.value
            val currentDevices: List<DiscoveredDevice> = if (currentState is BleConnectionState.Scanning) currentState.discoveredDevices else emptyList()
            
            if (currentDevices.none { it.address == peripheral.address }) {
                val newDevice: DiscoveredDevice = DiscoveredDevice(peripheral.name, peripheral.address, peripheral)
                _bleConnectionState.update { state: BleConnectionState ->
                    if (state is BleConnectionState.Scanning) state.copy(discoveredDevices = state.discoveredDevices + newDevice) else state
                }
            }
        }

        override fun onConnected(peripheral: BluetoothPeripheral) {
            peripheral.requestConnectionPriority(ConnectionPriority.HIGH)
            Timber.i("Connected to ${peripheral.name}")
            _bleConnectionState.value = BleConnectionState.Connected(peripheral)
        }

        override fun onDisconnected(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.i("Disconnected from ${peripheral.name}. Reason: $status")

            // TODO consider adding an error state depending on how the disconnect happened
            // Check if this was an intentional disconnect or a drop
            if (status != HciStatus.SUCCESS) {
                _bleConnectionState.value = BleConnectionState.Connecting(peripheral.name)
                // Auto-reconnect infinitely in the background
                centralManager.autoConnect(peripheral, peripheralCallback)
            } else {
                _bleConnectionState.value = BleConnectionState.Disconnected
            }

            scope.launch {
                _co2Value.emit(null)
                _temperatureValue.emit(null)
                _humidityValue.emit(null)
                _batteryLevel.emit(null)
            }
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.e("Connection to ${peripheral.name} failed with status $status")
            _bleConnectionState.value = BleConnectionState.Error("Connection failed with status: $status \n(Error code: ${status.value})")
        }

        override fun onScanFailed(scanFailure: ScanFailure) {
            Timber.e("Scan failed with error $scanFailure")
            _bleConnectionState.value = BleConnectionState.Error("Scan failed")
        }

        override fun onBluetoothAdapterStateChanged(state: Int) {
            if (state == BluetoothAdapter.STATE_OFF) {
                Timber.e("Bluetooth adapter turned off")
                _bleConnectionState.value = BleConnectionState.Error("Bluetooth is off")
            }
        }
    }

    /**
     * Initializes the Bluetooth handler, creates a thread for BLE operations, and initializes the
     * central manager.
     * @param context The application context.
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        Timber.plant(Timber.DebugTree())

        handlerThread = HandlerThread("BlessedBleThread", Process.THREAD_PRIORITY_DEFAULT)
        handlerThread.start()
        bleHandler = Handler(handlerThread.looper)

        this.centralManager = BluetoothCentralManager(context.applicationContext, centralManagerCallback, bleHandler)
        isInitialized = true
    }

    /**
     * Starts a scan for BLE peripherals.
     */
    fun startScan() {
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
        bleHandler.removeCallbacksAndMessages(null)
        centralManager.stopScan()
        _bleConnectionState.value = BleConnectionState.Connecting(peripheral.name)
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
