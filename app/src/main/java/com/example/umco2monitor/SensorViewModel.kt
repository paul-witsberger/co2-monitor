package com.example.umco2monitor

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
// BLESSED Library Imports - These will replace many of the native android.bluetooth imports
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.HciStatus
import com.welie.blessed.ScanFailure
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber // BLESSED uses Timber for logging, it's good practice to use it too.

data class DiscoveredDevice(
    val name: String?,
    val address: String,
    // We will now store the BluetoothPeripheral object from BLESSED
    val peripheral: BluetoothPeripheral
)

sealed interface BleConnectionState {
    object Disconnected : BleConnectionState
    data class Scanning(val discoveredDevices: List<DiscoveredDevice>) : BleConnectionState
    // Store the peripheral in the Connected state
    data class Connected(val peripheral: BluetoothPeripheral) : BleConnectionState
    data class Error(val message: String) : BleConnectionState
}

// Define your UUIDs here, so they are accessible
val SENSOR_SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-1234-567890abcdef") // Use your actual random UUID
val CO2_CHARACTERISTIC_UUID: UUID = UUID.fromString("fedcba09-8765-4321-0fed-cba987654321") // Use your actual random UUID

class SensorViewModel(private val application: Application) : ViewModel() {
    private val _bleConnectionState: MutableStateFlow<BleConnectionState> = MutableStateFlow(BleConnectionState.Disconnected)
    val bleConnectionState: StateFlow<BleConnectionState> = _bleConnectionState.asStateFlow()

    private val _co2Value: MutableStateFlow<Int?> = MutableStateFlow(null)
    val co2Value: StateFlow<Int?> = _co2Value.asStateFlow()

    // ### REFACTOR 1: Replace native BLE objects with BluetoothCentralManager ###
    // This one object will manage scanning, connecting, and callbacks.
    private val central: BluetoothCentralManager

    // ### REFACTOR 2: Create a callback for the peripheral ###
    // This callback handles events for a *specific* connected device.
    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            Timber.i("Services discovered for ${peripheral.name}")
            // Enable notifications for the CO2 characteristic
            peripheral.getCharacteristic(SENSOR_SERVICE_UUID, CO2_CHARACTERISTIC_UUID)?.let {
                peripheral.setNotify(it, true)
            }
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: HciStatus
        ) {
            if (status != HciStatus.SUCCESS) return
            if (characteristic.uuid == CO2_CHARACTERISTIC_UUID) {
                // Assuming the value is a 16-bit unsigned integer like before
                val co2 = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0)
                Timber.i("Received CO2 value: $co2")
                _co2Value.value = co2
            }
        }
    }

    // ### REFACTOR 3: Create a callback for the central manager ###
    // This callback handles scanning and connection state changes.
    private val centralManagerCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            // Ignore devices without a name
            if (peripheral.name.isNullOrBlank()) return

            Timber.i("Discovered '${peripheral.name}' with address '${peripheral.address}'")

            val currentDevices = (_bleConnectionState.value as? BleConnectionState.Scanning)?.discoveredDevices ?: emptyList()
            if (currentDevices.none { it.address == peripheral.address }) {
                val newDevice = DiscoveredDevice(peripheral.name, peripheral.address, peripheral)
                _bleConnectionState.update {
                    if (it is BleConnectionState.Scanning) {
                        it.copy(discoveredDevices = it.discoveredDevices + newDevice)
                    } else {
                        // This case is unlikely but safe to handle
                        BleConnectionState.Scanning(listOf(newDevice))
                    }
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
            _co2Value.value = null // Reset value on disconnect
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.e("Connection to ${peripheral.name} failed with status $status")
            _bleConnectionState.value = BleConnectionState.Error("Connection failed")
        }

        override fun onScanFailed(scanFailure: ScanFailure) {
            Timber.e("Scan failed with error $scanFailure")
            _bleConnectionState.value = BleConnectionState.Error("Scan failed")
        }
    }

    init {
        // Initialize Timber for logging
        Timber.plant(Timber.DebugTree())
        // Initialize the central manager
        central = BluetoothCentralManager(application, centralManagerCallback, android.os.Handler(application.mainLooper))
    }

    // ### REFACTOR 4: Update the public functions to use the new `central` manager ###
    fun startScan() {
        // Permissions check is still important!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            _bleConnectionState.value = BleConnectionState.Error("Bluetooth Scan permission not granted")
            return
        }

        _bleConnectionState.value = BleConnectionState.Scanning(emptyList())
        // Use the central manager to scan for peripherals that advertise your specific service UUID
        central.scanForPeripheralsWithServices(arrayOf(SENSOR_SERVICE_UUID))
    }

    fun stopScan() {
        central.stopScan()
        if (_bleConnectionState.value is BleConnectionState.Scanning) {
            _bleConnectionState.value = BleConnectionState.Disconnected
        }
    }

    fun connectToDevice(device: DiscoveredDevice) {
        stopScan()
        // The peripheral object is already stored in our DiscoveredDevice data class
        central.connectPeripheral(device.peripheral, peripheralCallback)
    }

    fun disconnect() {
        // We need to get the currently connected peripheral from our state
        val connectedPeripheral = (_bleConnectionState.value as? BleConnectionState.Connected)?.peripheral
        connectedPeripheral?.let {
            central.cancelConnection(it)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // It's good practice to stop any ongoing scan when the ViewModel is cleared
        if (central.isScanning) {
            stopScan()
        }
    }
}

// The ViewModelFactory remains unchanged.
class SensorViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SensorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SensorViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
