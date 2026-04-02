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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
    private val URGENT_ALARM_CHANNEL_ID = "urgent_sensor_alarms"
    private val REGULAR_ALARM_CHANNEL_ID = "regular_sensor_alarms"

    private enum class AlarmStatus { NORMAL, INITIAL_ALARM, URGENT_ALARM }
    private data class AlarmState(var status: AlarmStatus = AlarmStatus.NORMAL, var triggeredAt: Instant? = null)

    private val highCo2Alarm = AlarmState()
    private val lowCo2Alarm = AlarmState()

    // Notification IDs
    private val HIGH_CO2_NOTIFICATION_ID = 101
    private val LOW_CO2_NOTIFICATION_ID = 102


    init {
        createNotificationChannels()
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
        val now = kotlin.time.Clock.System.now()
        val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())

        // --- High CO2 Alarm ---
        checkAlarmState(
            currentValue = data.co2Reading,
            threshold = 2000u,
            isHighAlarm = true,
            alarmState = highCo2Alarm,
            notificationId = HIGH_CO2_NOTIFICATION_ID,
            now = now,
            timeFormat = timeFormat,
            sensorName = "CO2",
            unit = "ppm"
        )

        // --- Low CO2 Alarm ---
        checkAlarmState(
            currentValue = data.co2Reading,
            threshold = 400u,
            isHighAlarm = false,
            alarmState = lowCo2Alarm,
            notificationId = LOW_CO2_NOTIFICATION_ID,
            now = now,
            timeFormat = timeFormat,
            sensorName = "CO2",
            unit = "ppm"
        )
    }

    private fun <T: Comparable<T>> checkAlarmState(
        currentValue: T,
        threshold: T,
        isHighAlarm: Boolean,
        alarmState: AlarmState,
        notificationId: Int,
        now: Instant,
        timeFormat: SimpleDateFormat,
        sensorName: String,
        unit: String
    ) {
        val isOutOfRange = if (isHighAlarm) currentValue > threshold else currentValue < threshold

        if (isOutOfRange) {
            when (alarmState.status) {
                AlarmStatus.NORMAL -> {
                    alarmState.status = AlarmStatus.INITIAL_ALARM
                    alarmState.triggeredAt = now
                    val timeString = timeFormat.format(Date(now.toEpochMilliseconds()))
                    val direction = if (isHighAlarm) "exceeded" else "dropped below"
                    sendAlarmNotification(
                        title = "High $sensorName Alert",
                        message = "$sensorName level $direction $threshold $unit at $timeString.",
                        isUrgent = false,
                        notificationId = notificationId
                    )
                }
                AlarmStatus.INITIAL_ALARM -> {
                    val duration = now - alarmState.triggeredAt!!
                    if (duration > 30.seconds) {
                        alarmState.status = AlarmStatus.URGENT_ALARM
                        val direction = if (isHighAlarm) "above" else "below"
                        sendAlarmNotification(
                            title = "URGENT: High $sensorName Alert",
                            message = "$sensorName level has been $direction $threshold $unit for over 30 seconds.",
                            isUrgent = true,
                            notificationId = notificationId
                        )
                    }
                }
                AlarmStatus.URGENT_ALARM -> { /* Do nothing, urgent notification already sent */ }
            }
        } else {
            if (alarmState.status != AlarmStatus.NORMAL) {
                val timeString = timeFormat.format(Date(now.toEpochMilliseconds()))
                sendAlarmNotification(
                    title = "$sensorName Level Normal",
                    message = "$sensorName level returned to normal range at $timeString.",
                    isUrgent = false, // This is a "good" notification
                    notificationId = notificationId
                )
                alarmState.status = AlarmStatus.NORMAL
                alarmState.triggeredAt = null
            }
        }
    }

    private fun sendAlarmNotification(title: String, message: String, isUrgent: Boolean, notificationId: Int) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            Timber.w("Cannot send alarm notification: POST_NOTIFICATIONS permission not granted.")
            return
        }

        val channelId = if (isUrgent) URGENT_ALARM_CHANNEL_ID else REGULAR_ALARM_CHANNEL_ID
        val priority = if (isUrgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message)) // Allows for longer message text
            .setPriority(priority)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    private fun createNotificationChannels() {
        // Urgent Channel
        val urgentChannel = NotificationChannel(
            URGENT_ALARM_CHANNEL_ID,
            "Urgent Sensor Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for critical sensor alerts that require immediate attention."
        }
        notificationManager.createNotificationChannel(urgentChannel)

        // Regular Channel
        val regularChannel = NotificationChannel(
            REGULAR_ALARM_CHANNEL_ID,
            "Regular Sensor Alarms",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for initial sensor alerts."
        }
        notificationManager.createNotificationChannel(regularChannel)
    }
}
