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
 *
 * @param context The application context.
 * @param repository The repository for storing sensor data.
 * @param scope The coroutine scope for running background tasks.
 */
@OptIn(FlowPreview::class)
class DataRecorder(
    private val context: Context,
    private val repository: SensorRepository,
    private val scope: CoroutineScope
) {
    private val notificationManager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private enum class AlarmStatus { NORMAL, INITIAL_ALARM, URGENT_ALARM }
    private data class AlarmState(var status: AlarmStatus = AlarmStatus.NORMAL, var triggeredAt: Instant? = null)

    private val highCo2Alarm: AlarmState = AlarmState()
    private val lowCo2Alarm: AlarmState = AlarmState()

    init {
        createNotificationChannels()
        observeAndRecordData()
    }

    private fun observeAndRecordData() {
        combine(
            BluetoothHandler.co2Value,
            BluetoothHandler.temperatureValue,
            BluetoothHandler.humidityValue
        ) { co2: UShort?, temp: Float?, humid: Float? ->
            if (co2 != null && temp != null && humid != null) {
                SensorDataEntity(co2Reading = co2, temperatureReading = temp, humidityReading = humid)
            } else null
        }
        .debounce(1000)
        .onEach { entity: SensorDataEntity? ->
            entity?.let {
                Timber.d("Recording data: $it")
                repository.insert(it)
                checkAlarms(it)
            }
        }
        .launchIn(scope)
    }

    private fun checkAlarms(data: SensorDataEntity) {
        val now: Instant = kotlin.time.Clock.System.now()
        val timeFormat: SimpleDateFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())

        checkAlarmState(
            currentValue = data.co2Reading,
            threshold = 2000u,
            isHighAlarm = true,
            alarmState = highCo2Alarm,
            notificationId = HIGH_CO2_NOTIFICATION_ID,
            now = now,
            timeFormat = timeFormat
        )

        checkAlarmState(
            currentValue = data.co2Reading,
            threshold = 400u,
            isHighAlarm = false,
            alarmState = lowCo2Alarm,
            notificationId = LOW_CO2_NOTIFICATION_ID,
            now = now,
            timeFormat = timeFormat
        )
    }

    private fun <T: Comparable<T>> checkAlarmState(
        currentValue: T,
        threshold: T,
        isHighAlarm: Boolean,
        alarmState: AlarmState,
        notificationId: Int,
        now: Instant,
        timeFormat: SimpleDateFormat
    ) {
        val isOutOfRange: Boolean = if (isHighAlarm) currentValue > threshold else currentValue < threshold

        if (isOutOfRange) {
            when (alarmState.status) {
                AlarmStatus.NORMAL -> {
                    alarmState.status = AlarmStatus.INITIAL_ALARM
                    alarmState.triggeredAt = now
                    val timeString: String = timeFormat.format(Date(now.toEpochMilliseconds()))
                    val direction: String = if (isHighAlarm) "exceeded" else "dropped below"
                    sendAlarmNotification(
                        title = "High CO2 Alert",
                        message = "CO2 level $direction $threshold ppm at $timeString.",
                        isUrgent = false,
                        notificationId = notificationId
                    )
                }
                AlarmStatus.INITIAL_ALARM -> {
                    val duration: kotlin.time.Duration = now - alarmState.triggeredAt!!
                    if (duration > 30.seconds) {
                        alarmState.status = AlarmStatus.URGENT_ALARM
                        val direction: String = if (isHighAlarm) "above" else "below"
                        sendAlarmNotification(
                            title = "URGENT: High CO2 Alert",
                            message = "CO2 level has been $direction $threshold ppm for over 30 seconds.",
                            isUrgent = true,
                            notificationId = notificationId
                        )
                    }
                }
                AlarmStatus.URGENT_ALARM -> { }
            }
        } else {
            if (alarmState.status != AlarmStatus.NORMAL) {
                val timeString: String = timeFormat.format(Date(now.toEpochMilliseconds()))
                sendAlarmNotification(
                    title = "CO2 Level Normal",
                    message = "CO2 level returned to normal range at $timeString.",
                    isUrgent = false,
                    notificationId = notificationId
                )
                alarmState.status = AlarmStatus.NORMAL
                alarmState.triggeredAt = null
            }
        }
    }

    private fun sendAlarmNotification(title: String, message: String, isUrgent: Boolean, notificationId: Int) {
        // On API 33 and above, POST_NOTIFICATIONS permission is required
        val postNotificationsGranted: Boolean = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // On API 32 and below, no runtime permission is needed
        }

        if (!postNotificationsGranted) {
            Timber.w("Cannot send alarm notification: POST_NOTIFICATIONS permission not granted.")
            return
        }

        val channelId: String = if (isUrgent) URGENT_ALARM_CHANNEL_ID else REGULAR_ALARM_CHANNEL_ID
        val priority: Int = if (isUrgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT

        val notification: android.app.Notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    private fun createNotificationChannels() {
        val urgentChannel: NotificationChannel = NotificationChannel(
            URGENT_ALARM_CHANNEL_ID,
            "Urgent Sensor Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for critical sensor alerts that require immediate attention."
        }
        notificationManager.createNotificationChannel(urgentChannel)

        val regularChannel: NotificationChannel = NotificationChannel(
            REGULAR_ALARM_CHANNEL_ID,
            "Regular Sensor Alarms",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for initial sensor alerts."
        }
        notificationManager.createNotificationChannel(regularChannel)
    }

    companion object {
        private const val URGENT_ALARM_CHANNEL_ID: String = "urgent_sensor_alarms"
        private const val REGULAR_ALARM_CHANNEL_ID: String = "regular_sensor_alarms"
        private const val HIGH_CO2_NOTIFICATION_ID: Int = 101
        private const val LOW_CO2_NOTIFICATION_ID: Int = 102
    }
}
