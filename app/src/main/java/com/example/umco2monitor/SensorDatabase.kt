package com.example.umco2monitor

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Instant

// The converters for the Room database
class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Instant? = value?.let { Instant.fromEpochMilliseconds(it) }
    @TypeConverter
    fun dateToTimestamp(date: Instant?): Long? = date?.toEpochMilliseconds()
}

// The entity for the Room database
@Entity(tableName = "sensor_data")
data class SensorDataEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val co2Reading: UShort,
    val temperatureReading: Float,
    val humidityReading: Float,
    val timestamp: Instant = Clock.System.now()
)

// The DAO for the Room database, where DAO stands for "Data Access Object"
@Dao
interface SensorDataDao {
    // Insert a new reading into the database
    @Insert
    suspend fun insert(sensorData: SensorDataEntity)

    // Get all readings from the database
    @Query("SELECT * FROM sensor_data ORDER BY timestamp DESC")
    fun getAllReadings(): Flow<List<SensorDataEntity>>

    // Get all readings from the database within a specific time range
    @Query("SELECT * FROM sensor_data WHERE timestamp BETWEEN :start AND :end")
    fun getReadingsInRange(start: Instant, end: Instant): Flow<List<SensorDataEntity>>

    // Delete all readings from the database
    @Query("DELETE FROM sensor_data")
    suspend fun deleteAll()

    // Delete all readings from the database within a specific time range
    @Query("DELETE FROM sensor_data WHERE timestamp BETWEEN :start AND :end")
    fun deleteReadingsInRange(start: Instant, end: Instant)
}

// The Room database
@Database(entities = [SensorDataEntity::class], version = 1)
@TypeConverters(Converters::class)
abstract class SensorDatabase : RoomDatabase() {
    abstract fun sensorDataDao(): SensorDataDao
}
