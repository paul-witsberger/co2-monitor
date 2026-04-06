package com.example.umco2monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/**
 * Listens to incoming BLE sensor flows and persists them to the local database.
 *
 * @param repository The data repository for sensor readings.
 * @param scope The coroutine scope used to collect the flows in the background.
 */
class DataLogger(
    private val repository: SensorRepository,
    private val scope: CoroutineScope
) {
    init {
        startLogging()
    }

    private fun startLogging() {
        combine(
            BluetoothHandler.co2Value,
            BluetoothHandler.temperatureValue,
            BluetoothHandler.humidityValue
        ) { co2: UShort?, temp: Float?, humid: Float? ->
            if (co2 != null && temp != null && humid != null) {
                SensorDataEntity(co2Reading = co2, temperatureReading = temp, humidityReading = humid)
            } else null
        }
            .onEach { entity: SensorDataEntity? ->
                entity?.let {
                    Timber.d("Recording data: $it")
                    repository.insert(it)
                }
            }
            .launchIn(scope)
    }
}