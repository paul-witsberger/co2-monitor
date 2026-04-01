package com.example.umco2monitor

import android.content.Context
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
    @TypeConverter
    fun fromUShort(value: UShort?): Int? = value?.toInt()
    @TypeConverter
    fun toUShort(value: Int?): UShort? = value?.toUShort()
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

// The DAO for the Room database
@Dao
interface SensorDataDao {
    @Insert
    suspend fun insert(sensorData: SensorDataEntity)

    @Query("SELECT * FROM sensor_data ORDER BY timestamp DESC")
    fun getAllReadings(): Flow<List<SensorDataEntity>>

    @Query("SELECT * FROM sensor_data WHERE timestamp BETWEEN :start AND :end")
    fun getReadingsInRange(start: Instant, end: Instant): Flow<List<SensorDataEntity>>

    @Query("DELETE FROM sensor_data")
    suspend fun deleteAll()

    @Query("DELETE FROM sensor_data WHERE timestamp BETWEEN :start AND :end")
    fun deleteReadingsInRange(start: Instant, end: Instant)
}

// The Room database
@Database(entities = [SensorDataEntity::class], version = 1)
@TypeConverters(Converters::class)
abstract class SensorDatabase : RoomDatabase() {
    abstract fun sensorDataDao(): SensorDataDao

    companion object {
        @Volatile
        private var INSTANCE: SensorDatabase? = null

        fun getInstance(context: Context): SensorDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
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
