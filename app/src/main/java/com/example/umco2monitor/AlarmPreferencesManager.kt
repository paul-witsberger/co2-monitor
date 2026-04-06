package com.example.umco2monitor

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Creates a single instance of DataStore tied to the application context
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "alarm_settings")

/**
 * Represents the different priorities of alarm notifications.
 */
enum class AlertType { URGENT, REGULAR, INFO }

// TODO what other alarms should be added?
/**
 * Stores the preferences for different alarm types.
 * @param initialOutageType The type of alarm to trigger when CO2 initially exits the acceptable range.
 * @param sustainedOutageType The type of alarm to trigger when CO2 remains outside the acceptable range.
 * @param returnToNormalType The type of alarm to trigger when CO2 returns to the normal range.
 * @param connectionLostType The type of alarm to trigger when the Bluetooth connection is lost.
 */
data class AlarmPreferences(
    var initialOutageType: AlertType = AlertType.REGULAR,
    var sustainedOutageType: AlertType = AlertType.URGENT,
    var returnToNormalType: AlertType = AlertType.INFO,
    var connectionLostType: AlertType = AlertType.URGENT
)

/**
 * Manages the persistence of user alarm preferences using Jetpack DataStore.
 *
 * @param context The application context.
 */
class AlarmPreferencesManager(private val context: Context) {

    // Define the keys used to store the values
    private val INITIAL_OUTAGE_KEY = stringPreferencesKey("initial_outage_type")
    private val SUSTAINED_OUTAGE_KEY = stringPreferencesKey("sustained_outage_type")
    private val RETURN_NORMAL_KEY = stringPreferencesKey("return_normal_type")
    private val CONNECTION_LOST_KEY = stringPreferencesKey("connection_lost_type")

    /**
     * A Flow that emits the current [AlarmPreferences] whenever they are updated on disk.
     */
    val preferencesFlow: Flow<AlarmPreferences> = context.dataStore.data.map { preferences ->
        AlarmPreferences(
            initialOutageType = valueToEnum(preferences[INITIAL_OUTAGE_KEY], AlertType.REGULAR),
            sustainedOutageType = valueToEnum(preferences[SUSTAINED_OUTAGE_KEY], AlertType.URGENT),
            returnToNormalType = valueToEnum(preferences[RETURN_NORMAL_KEY], AlertType.INFO),
            connectionLostType = valueToEnum(preferences[CONNECTION_LOST_KEY], AlertType.URGENT)
        )
    }

    /**
     * Updates the saved preferences. (You will call this from your ViewModel later).
     */
    suspend fun updatePreferences(newPreferences: AlarmPreferences) {
        context.dataStore.edit { preferences ->
            preferences[INITIAL_OUTAGE_KEY] = newPreferences.initialOutageType.name
            preferences[SUSTAINED_OUTAGE_KEY] = newPreferences.sustainedOutageType.name
            preferences[RETURN_NORMAL_KEY] = newPreferences.returnToNormalType.name
            preferences[CONNECTION_LOST_KEY] = newPreferences.connectionLostType.name
        }
    }

    private fun valueToEnum(value: String?, default: AlertType): AlertType {
        return try {
            if (value != null) AlertType.valueOf(value) else default
        } catch (e: IllegalArgumentException) {
            default // TODO handle exception
        }
    }
}