package com.example.umco2monitor

import kotlinx.coroutines.flow.Flow

/**
 * Repository class that abstracts the data source (Room database) from the rest of the app.
 * It provides a clean API for data access to the rest of the application.
 */
class SensorRepository(private val dao: SensorDataDao) {

    /**
     * A flow that emits the full list of sensor readings whenever the data changes.
     * The list is ordered by timestamp in descending order.
     */
    val allReadings: Flow<List<SensorDataEntity>> = dao.getAllReadings()

    /**
     * Inserts a new sensor reading into the database.
     * This is a suspend function, so it must be called from a coroutine.
     * @param reading The [SensorDataEntity] to insert.
     */
    suspend fun insert(reading: SensorDataEntity) {
        dao.insert(reading)
    }
}
