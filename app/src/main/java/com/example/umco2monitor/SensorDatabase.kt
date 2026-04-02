package com.example.umco2monitor

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The converters for the Room database.
 */
class Converters {
    /**
     * Converts a Long timestamp to an Instant.
     * @param value The Long timestamp to convert.
     * @return The corresponding Instant, or null if the value is null.
     */
    @TypeConverter
    fun fromTimestamp(value: Long?): Instant? = value?.let { Instant.fromEpochMilliseconds(it) }

    /**
     * Converts an Instant to a Long timestamp.
     * @param date The Instant to convert.
     * @return The corresponding Long timestamp, or null if the date is null.
     */
    @TypeConverter
    fun dateToTimestamp(date: Instant?): Long? = date?.toEpochMilliseconds()

    /**
     * Converts a UShort to an Int for database storage.
     * @param value The UShort to convert.
     * @return The corresponding Int, or null if the value is null.
     */
    @TypeConverter
    fun fromUShort(value: UShort?): Int? = value?.toInt()

    /**
     * Converts an Int from the database to a UShort.
     * @param value The Int to convert.
     * @return The corresponding UShort, or null if the value is null.
     */
    @TypeConverter
    fun toUShort(value: Int?): UShort? = value?.toUShort()
}

/**
 * The entity for the Room database representing a sensor reading.
 *
 * @property id The unique ID of the reading.
 * @property co2Reading The CO2 reading in ppm.
 * @property temperatureReading The temperature reading.
 * @property humidityReading The humidity reading.
 * @property timestamp The time the reading was taken.
 */
@Entity(tableName = "sensor_data")
data class SensorDataEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val co2Reading: UShort,
    val temperatureReading: Float,
    val humidityReading: Float,
    val timestamp: Instant = Clock.System.now()
)

/**
 * The Data Access Object for the Room database.
 */
@Dao
interface SensorDataDao {
    /**
     * Inserts a new sensor reading into the database.
     * @param sensorData The [SensorDataEntity] to insert.
     */
    @Insert
    suspend fun insert(sensorData: SensorDataEntity)

    /**
     * Retrieves all sensor readings from the database, ordered by timestamp descending.
     * @return A Flow emitting the list of all sensor readings.
     */
    @Query("SELECT * FROM sensor_data ORDER BY timestamp DESC")
    fun getAllReadings(): Flow<List<SensorDataEntity>>

    /**
     * Retrieves sensor readings within a specific time range.
     * @param start The start of the time range.
     * @param end The end of the time range.
     * @return A Flow emitting the list of sensor readings in the given range.
     */
    @Query("SELECT * FROM sensor_data WHERE timestamp BETWEEN :start AND :end")
    fun getReadingsInRange(start: Instant, end: Instant): Flow<List<SensorDataEntity>>

    /**
     * Deletes all sensor readings from the database.
     */
    @Query("DELETE FROM sensor_data")
    suspend fun deleteAll()

    /**
     * Deletes sensor readings within a specific time range.
     * @param start The start of the time range.
     * @param end The end of the time range.
     */
    @Query("DELETE FROM sensor_data WHERE timestamp BETWEEN :start AND :end")
    fun deleteReadingsInRange(start: Instant, end: Instant)
}

/**
 * The Room database for storing sensor data.
 */
@Database(entities = [SensorDataEntity::class], version = 1)
@TypeConverters(Converters::class)
abstract class SensorDatabase : RoomDatabase() {
    /**
     * Retrieves the [SensorDataDao] for accessing the database.
     * @return The [SensorDataDao].
     */
    abstract fun sensorDataDao(): SensorDataDao

    companion object {
        @Volatile
        private var INSTANCE: SensorDatabase? = null

        /**
         * Gets the singleton instance of the [SensorDatabase].
         * @param context The application context.
         * @return The [SensorDatabase] instance.
         */
        fun getInstance(context: Context): SensorDatabase {
            val currentInstance: SensorDatabase? = INSTANCE
            return currentInstance ?: synchronized(this) {
                val instance: SensorDatabase = Room.databaseBuilder(
                    context.applicationContext,
                    SensorDatabase::class.java,
                    "sensor_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
