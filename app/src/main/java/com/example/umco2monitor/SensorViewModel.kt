@file:OptIn(FlowPreview::class)

package com.example.umco2monitor

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.launch
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Data class representing a discovered BLE device.
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
    object Disconnected : BleConnectionState
    data class Scanning(val discoveredDevices: List<DiscoveredDevice>) : BleConnectionState
    data class Connecting(val deviceName: String?) : BleConnectionState
    data class Connected(val peripheral: BluetoothPeripheral) : BleConnectionState
    data class Error(val message: String) : BleConnectionState
}

/**
 * Data class representing sensor data for the UI.
 */
data class SensorData(
    val co2Value: UShort,
    val temperatureValue: Float,
    val humidityValue: Float,
    val timestamp: Instant
)

/**
 * Data class to track which sensor types are visible in the history plot.
 */
data class HistorySettings(
    val showCo2: Boolean = true,
    val showTemperature: Boolean = true,
    val showHumidity: Boolean = true,
    val timeRange: Duration = 1.hours
)

/**
 * The central ViewModel for the application.
 */
class SensorViewModel(private val application: Application) : ViewModel() {
    
    private val repository = SensorRepository(SensorDatabase.getInstance(application).sensorDataDao())

    private val _bleConnectionState: MutableStateFlow<BleConnectionState> = MutableStateFlow(BleConnectionState.Disconnected)
    val bleConnectionState: StateFlow<BleConnectionState> = _bleConnectionState.asStateFlow()

    val co2Value: StateFlow<UShort?> = BluetoothHandler.co2Value
    val temperatureValue: StateFlow<Float?> = BluetoothHandler.temperatureValue
    val humidityValue: StateFlow<Float?> = BluetoothHandler.humidityValue
    val batteryLevel: StateFlow<UInt?> = BluetoothHandler.batteryLevel

    // UI State for tabs (0: Live, 1: History)
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _historySettings = MutableStateFlow(HistorySettings())
    val historySettings: StateFlow<HistorySettings> = _historySettings.asStateFlow()

    private val _resetPlotSignal = MutableSharedFlow<Unit>(replay = 0)
    val resetPlotSignal = _resetPlotSignal.asSharedFlow()

    /**
     * Sampled history of sensor data for the plot.
     * We provide enough data to allow for zooming out in the UI.
     */
    val history: StateFlow<List<SensorData>> = combine(
        repository.allReadings,
        _historySettings
    ) { entities, _ ->
        // Always provide a generous window (e.g., 8 days) so panning/zooming
        // out from 1H or 6H doesn't hit a "wall" of empty data.
        val cutoff = kotlin.time.Clock.System.now() - 8.days
        val filtered = entities.filter { it.timestamp >= cutoff }

//        // Sampling: keep it responsive (max 1000 points)
//        val maxPoints = 1000
//        val step = (filtered.size / maxPoints).coerceAtLeast(1)

        filtered
//            .filterIndexed { index, _ -> index % step == 0 }
            .map { SensorData(it.co2Reading, it.temperatureReading, it.humidityReading, it.timestamp) }
            .reversed()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        BluetoothHandler.initialize(application)

        BluetoothHandler.bleConnectionState.onEach { state ->
            _bleConnectionState.value = state
        }.launchIn(viewModelScope)
    }

    fun setSelectedTab(index: Int) {
        _selectedTab.value = index
    }

    fun updateHistorySettings(
        showCo2: Boolean? = null,
        showTemperature: Boolean? = null,
        showHumidity: Boolean? = null,
        timeRange: Duration? = null
    ) {
        _historySettings.update { current ->
            current.copy(
                showCo2 = showCo2 ?: current.showCo2,
                showTemperature = showTemperature ?: current.showTemperature,
                showHumidity = showHumidity ?: current.showHumidity,
                timeRange = timeRange ?: current.timeRange
            )
        }
        // If timeRange was changed, trigger a reset signal
        if (timeRange != null) {
            viewModelScope.launch { _resetPlotSignal.emit(Unit) }
        }
    }

    fun onPermissionsDenied() {
        _bleConnectionState.value = BleConnectionState.Error("Permissions were denied. Please enable them in settings.")
    }

    fun startScan(sdkInt: Int = Build.VERSION.SDK_INT) {
        if (sdkInt >= Build.VERSION_CODES.S &&
            (ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
             ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
        ) {
            onPermissionsDenied()
            return
        }
        BluetoothHandler.startScan()
    }

    fun stopScan() {
        BluetoothHandler.stopScan()
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

class SensorViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SensorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SensorViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
