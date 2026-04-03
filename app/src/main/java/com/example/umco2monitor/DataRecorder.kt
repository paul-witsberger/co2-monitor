package com.example.umco2monitor

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

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
    private val scope: CoroutineScope,
    private val sdkInt: Int = android.os.Build.VERSION.SDK_INT
) {
    private val notificationManager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private enum class AlarmStatus { NORMAL, INITIAL_ALARM, URGENT_ALARM }
    private data class AlarmState(var status: AlarmStatus = AlarmStatus.NORMAL, var triggeredAt: Instant? = null)

    private val highCo2Alarm: AlarmState = AlarmState()
    private val lowCo2Alarm: AlarmState = AlarmState()

    // TODO: Implement user-customizable CO2 threshold below which the alarm will trigger.
    private val lowCo2Threshold: UShort = 400u
    private val highCo2Threshold: UShort = 2000u

    private var watchdogJob: Job? = null

    // Add a MediaPlayer for urgent alarms
    private var mediaPlayer: MediaPlayer? = null

    init {
        createNotificationChannels()
        observeAndRecordData()
    }

    private fun observeAndRecordData() {
        // Monitor CO2 independently for life-saving alarms
        BluetoothHandler.co2Value
            .onEach { co2 ->
                if (co2 != null) {
                    resetWatchdog()
                    checkAlarms(co2)
                }
            }
            .launchIn(scope)

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

    private fun resetWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(30.seconds) // If no data received for 15 seconds
            sendAlarmNotification(
                title = "URGENT: Sensor Connection Lost",
                message = "No CO2 data received for 30 seconds. Check device and battery.",
                isUrgent = true,
                notificationId = 103
            )
        }
    }

    private fun checkAlarms(co2Reading: UShort) {
        val now: Instant = kotlin.time.Clock.System.now()
        val timeFormat: SimpleDateFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())

        checkAlarmState(
            currentValue = co2Reading,
            threshold = highCo2Threshold,
            isHighAlarm = true,
            alarmState = highCo2Alarm,
            notificationId = HIGH_CO2_NOTIFICATION_ID,
            now = now,
            timeFormat = timeFormat
        )

        checkAlarmState(
            currentValue = co2Reading,
            threshold = lowCo2Threshold,
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
                // Stop the audible alarm if the levels returned to normal on their own
                stopAudibleAlarm()

                alarmState.status = AlarmStatus.NORMAL
                alarmState.triggeredAt = null
            }
        }
    }

    @SuppressLint("InlinedApi")
    private fun sendAlarmNotification(title: String, message: String, isUrgent: Boolean, notificationId: Int) {
        // On API 33 and above, POST_NOTIFICATIONS permission is required
        val postNotificationsGranted: Boolean = if (sdkInt >= android.os.Build.VERSION_CODES.TIRAMISU) {
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

        // Create an intent that will tell the MeasurementService to stop the alarm
        val dismissIntent = Intent(context, MeasurementService::class.java).apply {
            action = MeasurementService.ACTION_DISMISS_ALARM
        }
        val dismissPendingIntent = PendingIntent.getService(
            context,
            notificationId,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // TODO Make sure to replace with a real icon
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setAutoCancel(true)

        if (isUrgent) {
            startAudibleAlarm()

            // Add a "Stop Alarm" button to the notification
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "DISMISS ALARM", dismissPendingIntent)

            // Also stop the alarm if the user swipes the notification away
            builder.setDeleteIntent(dismissPendingIntent)
        }

        notificationManager.notify(notificationId, builder.build())
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

    /**
     * Starts playing the system's default Alarm sound continuously.
     * It uses the ALARM audio stream, which bypasses silent mode.
     */
    private fun startAudibleAlarm() {
        if (mediaPlayer?.isPlaying == true) return // Already playing

        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM) // Bypasses silent mode
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(audioAttributes)
                isLooping = true // Play endlessly
                prepare()
                start()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to play audible alarm")
        }
    }

    /**
     * Public method to stop the alarm when acknowledged by the user or when levels return to normal.
     */
    fun stopAudibleAlarm() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null

        // Also ensure we reset the alarm state so it can trigger again later
        highCo2Alarm.status = AlarmStatus.NORMAL
        lowCo2Alarm.status = AlarmStatus.NORMAL
    }

    companion object {
        private const val URGENT_ALARM_CHANNEL_ID: String = "urgent_sensor_alarms"
        private const val REGULAR_ALARM_CHANNEL_ID: String = "regular_sensor_alarms"
        private const val HIGH_CO2_NOTIFICATION_ID: Int = 101
        private const val LOW_CO2_NOTIFICATION_ID: Int = 102
    }
}
