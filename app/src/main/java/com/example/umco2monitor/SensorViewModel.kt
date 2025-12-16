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
import kotlinx.coroutines.flow.StateFlow

/**
 * Data class representing a discovered BLE device.
 * @property name The name of the device, or null if unknown.
 * @property address The MAC address of the device.
 * @property peripheral The BluetoothPeripheral object representing the device.
 */
data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val peripheral: BluetoothPeripheral
)

/**
 * Sealed interface representing the possible states of the BLE connection. This interface ensures
 * that the app can only be in one of the defined states at any given time ([Disconnected],
 * [Scanning], [Connected], or [Error]).
 * @property Disconnected The device is not connected.
 * @property Scanning The device is scanning for BLE devices.
 * @property Connected The device is connected to a peripheral.
 * @property Error The device encountered an error.
 */
sealed interface BleConnectionState {
    object Disconnected : BleConnectionState
    data class Scanning(val discoveredDevices: List<DiscoveredDevice>) : BleConnectionState
    data class Connected(val peripheral: BluetoothPeripheral) : BleConnectionState
    data class Error(val message: String) : BleConnectionState
}

/**
 * The central ViewModel for the application. This class acts as a bridge between the UI layer
 * ([MainActivity]) and the data layer ([BluetoothHandler]). It exposes state information (like
 * connection status and sensor values) to the UI and provides functions that delegate user actions
 * (like scanning or connecting) to the [BluetoothHandler].
 * @param application The application context.
 */
class SensorViewModel(private val application: Application) : ViewModel() {
    /**
     * The current state of the BLE connection, sourced from BluetoothHandler.
     */
    val bleConnectionState: StateFlow<BleConnectionState> = BluetoothHandler.bleConnectionState

    /**
     * The most recent CO2 value, sourced from BluetoothHandler.
     */
    val co2Value: StateFlow<UShort?> = BluetoothHandler.co2Value

    // Initialize the BluetoothHandler when the ViewModel is created.
    init {
        BluetoothHandler.initialize(application)
    }

    /**
     * Starts a scan for BLE devices.
     */
    fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(application,
                Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            // TODO In a real app, you'd trigger a permission request from the UI.
            // For now, we can update a state to inform the user.
            // This logic correctly lives in the ViewModel, not the BLE handler.
            (bleConnectionState as? MutableStateFlow)?.value = BleConnectionState.Error("Bluetooth Scan permission not granted")
            return
        }
        BluetoothHandler.startScan()
    }

    /**
     * Stops the current scan.
     */
    // TODO make sure this is used somewhere
    fun stopScan() {
        BluetoothHandler.stopScan()
    }

    /**
     * Connects to a discovered device.
     * @param device The device to connect to.
     */
    fun connectToDevice(device: DiscoveredDevice) {
        BluetoothHandler.connect(device.peripheral)
    }

    /**
     * Disconnects from the currently connected device.
     */
    fun disconnect() {
        (bleConnectionState.value as? BleConnectionState.Connected)?.peripheral?.let {
            BluetoothHandler.disconnect(it)
        }
    }
}

/**
 * Factory class for creating instances of [SensorViewModel].
 * @property application The application context.
 * @property create Creates an instance of [SensorViewModel].
 * @return A new instance of [SensorViewModel].
 */
class SensorViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    /**
     * Creates an instance of the specified ViewModel class.
     * @param modelClass The class of the ViewModel to create
     * @throws IllegalArgumentException if the specified ViewModel class is not supported
     */
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SensorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SensorViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}