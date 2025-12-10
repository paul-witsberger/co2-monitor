package com.example.umco2monitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.ConnectionPriority
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
import java.util.UUID

// Define your UUIDs in a central place, accessible to the whole app
val SENSOR_SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-1234-567890abcdef") // Replace with your actual random UUID
val CO2_CHARACTERISTIC_UUID: UUID = UUID.fromString("fedcba09-8765-4321-0fed-cba987654321") // Replace with your actual random UUID

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

    private val _co2Value = MutableStateFlow<Int?>(null)
    val co2Value = _co2Value.asStateFlow()

    // Scope for launching coroutines
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // UUIDs for the Device Information service (DIS)
    private val DIS_SERVICE_UUID: UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")
    private val MANUFACTURER_NAME_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A29-0000-1000-8000-00805f9b34fb")
    private val MODEL_NUMBER_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb")

    // Callback for a connected peripheral
    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            Timber.i("Services discovered for ${peripheral.name}")

            peripheral.requestConnectionPriority(ConnectionPriority.HIGH)
            peripheral.readCharacteristic(DIS_SERVICE_UUID, MANUFACTURER_NAME_CHARACTERISTIC_UUID)
            peripheral.readCharacteristic(DIS_SERVICE_UUID, MODEL_NUMBER_CHARACTERISTIC_UUID)

            // Enable notifications for the CO2 characteristic
            peripheral.getCharacteristic(SENSOR_SERVICE_UUID, CO2_CHARACTERISTIC_UUID)?.let {
                Timber.i("Enabling notifications for CO2 characteristic")
                peripheral.startNotify(it)
            }
        }

        override fun onNotificationStateUpdate(peripheral: BluetoothPeripheral, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            if (status == GattStatus.SUCCESS) {
                if (peripheral.isNotifying(characteristic)) {
                    Timber.i("SUCCESS: Notifications are ON for CO2 characteristic")
                } else {
                    Timber.i("SUCCESS: Notifications are OFF for CO2 characteristic")
                }
            } else {
                Timber.e("ERROR: Changing notification state failed for ${characteristic.uuid} with status $status")
            }
        }

        override fun onCharacteristicUpdate(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            if (status != GattStatus.SUCCESS) return

            if (characteristic.uuid == CO2_CHARACTERISTIC_UUID && value.size >= 2) {
                val co2 = (value[1].toUByte().toInt() shl 8) or value[0].toUByte().toInt()
                Timber.i("Received CO2 value: $co2")
                scope.launch { _co2Value.emit(co2) }
            }
        }
    }

    // Callback for the central manager
    private val centralManagerCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            if (peripheral.name.isNullOrBlank()) return

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
            Timber.i("Connected to ${peripheral.name}")
            _bleConnectionState.value = BleConnectionState.Connected(peripheral)
        }

        override fun onDisconnected(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.i("Disconnected from ${peripheral.name}")
            _bleConnectionState.value = BleConnectionState.Disconnected
            scope.launch { _co2Value.emit(null) }
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.e("Connection to ${peripheral.name} failed with status $status")
            _bleConnectionState.value = BleConnectionState.Error("Connection failed")
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
