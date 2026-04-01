package com.example.umco2monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/**
 * Handles the logic for recording sensor data to persistent storage and checking for alarm conditions.
 * This class is designed to run in a background service.
 */
@OptIn(FlowPreview::class)
class DataRecorder(
    private val context: Context,
    private val repository: SensorRepository,
    private val scope: CoroutineScope
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val ALARM_CHANNEL_ID = "sensor_alarms"

    init {
        createNotificationChannel()
        observeAndRecordData()
    }

    private fun observeAndRecordData() {
        combine(
            BluetoothHandler.co2Value,
            BluetoothHandler.temperatureValue,
            BluetoothHandler.humidityValue
        ) { co2, temp, humid ->
            if (co2 != null && temp != null && humid != null) {
                SensorDataEntity(co2Reading = co2, temperatureReading = temp, humidityReading = humid)
            } else null
        }
        .debounce(1000) // Debounce to capture a stable set of readings
        .onEach { entity ->
            entity?.let {
                Timber.d("Recording data: $it")
                repository.insert(it)
                checkAlarms(it)
            }
        }
        .launchIn(scope)
    }

    private fun checkAlarms(data: SensorDataEntity) {
        // Example alarm for high CO2
        if (data.co2Reading > 2000u) {
            sendAlarmNotification("High CO2 Alert", "CO2 level has exceeded 2000 ppm: ${data.co2Reading} ppm")
        }
        // Example alarm for low CO2 as requested
        if (data.co2Reading < 400u) {
            sendAlarmNotification("Low CO2 Alert", "CO2 level has dropped below 400 ppm: ${data.co2Reading} ppm")
        }
    }

    private fun sendAlarmNotification(title: String, message: String) {
        // Check for POST_NOTIFICATIONS permission
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            Timber.w("Cannot send alarm notification: POST_NOTIFICATIONS permission not granted.")
            return
        }

        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure you have this drawable
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            ALARM_CHANNEL_ID,
            "Sensor Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for critical sensor alerts"
        }
        notificationManager.createNotificationChannel(channel)
    }
}
