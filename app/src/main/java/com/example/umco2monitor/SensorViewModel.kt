package com.example.umco2monitor

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.flow.MutableStateFlow

// The ViewModel only needs to know about the data classes and the peripheral object
data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val peripheral: BluetoothPeripheral
)

sealed interface BleConnectionState {
    object Disconnected : BleConnectionState
    data class Scanning(val discoveredDevices: List<DiscoveredDevice>) : BleConnectionState
    data class Connected(val peripheral: BluetoothPeripheral) : BleConnectionState
    data class Error(val message: String) : BleConnectionState
}

class SensorViewModel(private val application: Application) : ViewModel() {

    // The ViewModel now simply exposes the StateFlows from the BluetoothHandler singleton
    val bleConnectionState = BluetoothHandler.bleConnectionState
    val co2Value = BluetoothHandler.co2Value

    init {
        // Initialize the BluetoothHandler when the ViewModel is created.
        BluetoothHandler.initialize(application)
    }

    // Public functions now just delegate their calls to the BluetoothHandler
    fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            // In a real app, you'd trigger a permission request from the UI.
            // For now, we can update a state to inform the user.
            // This logic correctly lives in the ViewModel, not the BLE handler.
            (bleConnectionState as? MutableStateFlow)?.value = BleConnectionState.Error("Bluetooth Scan permission not granted")
            return
        }
        BluetoothHandler.startScan()
    }

    fun connectToDevice(device: DiscoveredDevice) {
        BluetoothHandler.connect(device.peripheral)
    }

    fun disconnect() {
        (bleConnectionState.value as? BleConnectionState.Connected)?.peripheral?.let {
            BluetoothHandler.disconnect(it)
        }
    }
}

// The ViewModelFactory remains unchanged and is still necessary
class SensorViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SensorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SensorViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}