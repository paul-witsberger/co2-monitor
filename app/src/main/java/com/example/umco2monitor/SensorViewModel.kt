@file:OptIn(FlowPreview::class)

package com.example.umco2monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Data class representing a discovered BLE device.
 *
 * @property name The name of the discovered device.
 * @property address The MAC address of the device.
 * @property peripheral The BLE peripheral object.
 */
data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val peripheral: BluetoothPeripheral
)

/**
 * Sealed interface representing the possible states of the BLE connection.
 */
sealed interface BleConnectionState {
    /**
     * Represents a disconnected state.
     */
    object Disconnected : BleConnectionState

    /**
     * Represents a scanning state.
     * @property discoveredDevices The list of devices found during scanning.
     */
    data class Scanning(val discoveredDevices: List<DiscoveredDevice>) : BleConnectionState

    /**
     * Represents a connecting state.
     * @property deviceName The name of the device being connected to.
     */
    data class Connecting(val deviceName: String?) : BleConnectionState

    /**
     * Represents a connected state.
     * @property peripheral The connected BLE peripheral.
     */
    data class Connected(val peripheral: BluetoothPeripheral) : BleConnectionState

    /**
     * Represents an error state.
     * @property message The error message.
     */
    data class Error(val message: String) : BleConnectionState
}

/**
 * Data class representing sensor data for the UI.
 *
 * @property co2Value The CO2 reading in ppm.
 * @property temperatureValue The temperature reading.
 * @property humidityValue The humidity reading.
 * @property timestamp The time the reading was taken.
 */
data class SensorData(
    val co2Value: UShort,
    val temperatureValue: Float,
    val humidityValue: Float,
    val timestamp: Instant
)

/**
 * Data class to track which sensor types are visible in the history plot.
 *
 * @property showCo2 Whether to show the CO2 plot.
 * @property showTemperature Whether to show the temperature plot.
 * @property showHumidity Whether to show the humidity plot.
 * @property timeRange The time range to display in the plot.
 */
data class HistorySettings(
    val showCo2: Boolean = true,
    val showTemperature: Boolean = true,
    val showHumidity: Boolean = true,
    val timeRange: Duration = 1.hours
)

/**
 * The central ViewModel for the application.
 *
 * @param application The application instance.
 */
class SensorViewModel(private val application: Application) : ViewModel() {
    
    private val repository: SensorRepository = SensorRepository(SensorDatabase.getInstance(application).sensorDataDao())

    private val _bleConnectionState: MutableStateFlow<BleConnectionState> = MutableStateFlow(BleConnectionState.Disconnected)
    /**
     * StateFlow representing the current BLE connection state.
     */
    val bleConnectionState: StateFlow<BleConnectionState> = _bleConnectionState.asStateFlow()

    /**
     * StateFlow representing the current CO2 value.
     */
    val co2Value: StateFlow<UShort?> = BluetoothHandler.co2Value

    /**
     * StateFlow representing the current temperature value.
     */
    val temperatureValue: StateFlow<Float?> = BluetoothHandler.temperatureValue

    /**
     * StateFlow representing the current humidity value.
     */
    val humidityValue: StateFlow<Float?> = BluetoothHandler.humidityValue

    /**
     * StateFlow representing the current battery level.
     */
    @Suppress("unused")
    val batteryLevel: StateFlow<UInt?> = BluetoothHandler.batteryLevel

    private val _selectedTab: MutableStateFlow<Int> = MutableStateFlow(0)
    /**
     * StateFlow representing the currently selected tab index.
     */
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _historySettings: MutableStateFlow<HistorySettings> = MutableStateFlow(HistorySettings())
    /**
     * StateFlow representing the current history plot settings.
     */
    val historySettings: StateFlow<HistorySettings> = _historySettings.asStateFlow()

    /**
     * Sampled history of sensor data for the plot.
     * We provide enough data to allow for zooming out in the UI.
     */
    val history: StateFlow<List<SensorData>> = combine(
        repository.allReadings,
        _historySettings
    ) { entities: List<SensorDataEntity>, _: HistorySettings ->
        val cutoff: Instant = kotlin.time.Clock.System.now() - 8.days
        val filtered: List<SensorDataEntity> = entities.filter { it.timestamp >= cutoff }

        filtered
            .map { SensorData(it.co2Reading, it.temperatureReading, it.humidityReading, it.timestamp) }
            .reversed()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        BluetoothHandler.initialize(application)

        BluetoothHandler.bleConnectionState.onEach { state: BleConnectionState ->
            _bleConnectionState.value = state
        }.launchIn(viewModelScope)
    }

    /**
     * Sets the currently selected tab.
     *
     * @param index The index of the selected tab.
     */
    fun setSelectedTab(index: Int) {
        _selectedTab.value = index
    }

    /**
     * Updates the history plot settings.
     *
     * @param showCo2 Whether to show the CO2 plot.
     * @param showTemperature Whether to show the temperature plot.
     * @param showHumidity Whether to show the humidity plot.
     * @param timeRange The time range to display in the plot.
     */
    fun updateHistorySettings(
        showCo2: Boolean? = null,
        showTemperature: Boolean? = null,
        showHumidity: Boolean? = null,
        timeRange: Duration? = null
    ) {
        _historySettings.update { current: HistorySettings ->
            current.copy(
                showCo2 = showCo2 ?: current.showCo2,
                showTemperature = showTemperature ?: current.showTemperature,
                showHumidity = showHumidity ?: current.showHumidity,
                timeRange = timeRange ?: current.timeRange
            )
        }
    }

    /**
     * Handles the case where required permissions are denied.
     */
    fun onPermissionsDenied() {
        _bleConnectionState.value = BleConnectionState.Error("Permissions were denied. Please enable them in settings.")
    }

    /**
     * Starts a scan for BLE devices.
     *
     * @param sdkInt The current Android SDK version.
     */
    @SuppressLint("InlinedApi")
    fun startScan(sdkInt: Int = Build.VERSION.SDK_INT) {
        if (sdkInt >= Build.VERSION_CODES.S) {
            val scanPermissionGranted: Boolean = ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connectPermissionGranted: Boolean = ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!scanPermissionGranted || !connectPermissionGranted) {
                onPermissionsDenied()
                return
            }
        }
        BluetoothHandler.startScan()
    }

    /**
     * Stops the current BLE scan.
     */
    fun stopScan() {
        BluetoothHandler.stopScan()
    }

    /**
     * Connects to the specified BLE device.
     *
     * @param device The discovered device to connect to.
     */
    fun connectToDevice(device: DiscoveredDevice) {
        BluetoothHandler.connect(device.peripheral)
    }

    /**
     * Disconnects from the currently connected BLE device.
     */
    fun disconnect() {
        val currentState: BleConnectionState = bleConnectionState.value
        if (currentState is BleConnectionState.Connected) {
            BluetoothHandler.disconnect(currentState.peripheral)
        }
    }
}

/**
 * Factory for creating [SensorViewModel] instances.
 *
 * @param application The application instance.
 */
class SensorViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    /**
     * Creates a new instance of the given Class.
     *
     * @param modelClass a Class whose instance is requested
     * @return a newly created ViewModel
     */
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SensorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SensorViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
