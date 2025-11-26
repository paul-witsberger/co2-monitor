package com.example.umco2monitor

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val device: BluetoothDevice
)

sealed interface BleConnectionState {
    object Disconnected : BleConnectionState
    data class Scanning(val discoveredDevices: List<DiscoveredDevice>) : BleConnectionState
    data class Connected(val device: DiscoveredDevice) : BleConnectionState
    data class Error(val message: String) : BleConnectionState
}

class SensorViewModel(private val application: Application) : ViewModel() {
    private val _bleConnectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val bleConnectionState: StateFlow<BleConnectionState> = _bleConnectionState.asStateFlow()

    private val _co2Value = MutableStateFlow<Int?>(null)
    val co2Value = _co2Value.asStateFlow()

    private val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner

    private val bleManager = ArduinoBleManager(application)

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return // Ignore null results

            // Check if the device is already in the list to avoid duplicates
            val currentDevices = if (_bleConnectionState.value is BleConnectionState.Scanning) {
                (_bleConnectionState.value as BleConnectionState.Scanning).discoveredDevices
            } else {
                emptyList()
            }
            val existingAddresses = currentDevices.map { it.address }.toSet()

            if (result.device.address != null && result.device.address !in existingAddresses) {
                if (ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
                val newDevice = DiscoveredDevice(
                    name = result.device.name ?: "Unknown Device",
                    address = result.device.address,
                    device = result.device
                )

                // Update the state with the new device added to the list
                _bleConnectionState.update {
                    if (it is BleConnectionState.Scanning) {
                        it.copy(discoveredDevices = it.discoveredDevices + newDevice)
                    } else {
                        BleConnectionState.Scanning(listOf(newDevice))
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("SensorViewModel", "BLE Scan Failed with error code: $errorCode")
            _bleConnectionState.value = BleConnectionState.Disconnected
        }
    }

    init {
        bleManager.connectionState.onEach { state ->
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                _bleConnectionState.value = BleConnectionState.Disconnected
            } else if (state == BluetoothProfile.STATE_CONNECTED) {
                // The connected device details are not directly available here.
                // We will need to manage this state carefully.
            }
        }.launchIn(viewModelScope)

        bleManager.co2Value.onEach { co2 ->
            _co2Value.value = co2
        }.launchIn(viewModelScope)
    }

    fun startScan() {
        if (ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        _bleConnectionState.value = BleConnectionState.Scanning(emptyList())
        scanner?.startScan(null, scanSettings, scanCallback)
    }

    fun stopScan() {
        if (ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        scanner?.stopScan(scanCallback)
        if (_bleConnectionState.value is BleConnectionState.Scanning) {
            _bleConnectionState.value = BleConnectionState.Disconnected
        }
    }

    fun connectToDevice(device: DiscoveredDevice) {
        stopScan()
        bleManager.connect(device.device)
        _bleConnectionState.value = BleConnectionState.Connected(device) // Optimistically update the state
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}

class SensorViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SensorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SensorViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
