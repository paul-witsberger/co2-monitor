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
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import timber.log.Timber

/**
 * Handles all Android Framework interactions for displaying notifications and playing sounds.
 *
 * @param context The application context.
 */
class AlarmNotifier(private val context: Context) {

    private val notificationManager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var mediaPlayer: MediaPlayer? = null

    /** Registry of currently active urgent alarm IDs. */
    private val activeUrgentAlarms: MutableSet<Int> = mutableSetOf()

    init {
        createNotificationChannels()
    }

    /**
     * Displays a notification and optionally plays an audible alarm based on the [AlertType].
     */
    @SuppressLint("InlinedApi")
    fun showAlarm(title: String, message: String, alertType: AlertType, notificationId: Int) {
        val postNotificationsGranted: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        if (!postNotificationsGranted) {
            Timber.w("Cannot send alarm notification: POST_NOTIFICATIONS permission not granted.")
            return
        }

        val channelId: String = when (alertType) {
            AlertType.URGENT -> URGENT_ALARM_CHANNEL_ID
            AlertType.REGULAR -> REGULAR_ALARM_CHANNEL_ID
            AlertType.INFO -> INFO_NOTIFICATION_CHANNEL_ID
        }

        val priority: Int = when (alertType) {
            AlertType.URGENT -> NotificationCompat.PRIORITY_MAX
            AlertType.REGULAR -> NotificationCompat.PRIORITY_HIGH
            AlertType.INFO -> NotificationCompat.PRIORITY_DEFAULT
        }

        val builder: NotificationCompat.Builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setAutoCancel(true)

        if (alertType == AlertType.URGENT) {
            activeUrgentAlarms.add(notificationId)
            startAudibleAlarm()

            val dismissIntent: Intent = Intent(MeasurementService.ACTION_DISMISS_ALARM).apply {
                setPackage(context.packageName)
                putExtra("NOTIFICATION_ID", notificationId)
            }
            val dismissPendingIntent: PendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId,
                dismissIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "DISMISS ALARM", dismissPendingIntent)
            builder.setDeleteIntent(dismissPendingIntent)
        }

        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * Removes an alarm from the active registry and stops the sound if no urgent alarms remain.
     */
    fun resolveUrgentAlarm(notificationId: Int) {
        activeUrgentAlarms.remove(notificationId)

        if (activeUrgentAlarms.isEmpty()) {
            mediaPlayer?.let { player: MediaPlayer ->
                if (player.isPlaying) player.stop()
                player.release()
            }
            mediaPlayer = null
        }
    }

    private fun startAudibleAlarm() {
        if (mediaPlayer?.isPlaying == true) return

        try {
            val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val currentVolume: Int = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

            // Force volume to at least 70% of max
            val safeVolume: Int = (maxVolume * 0.7).toInt()
            if (currentVolume < safeVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, safeVolume, 0)
            }

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val audioAttributes: AudioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(audioAttributes)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to play audible alarm")
        }
    }

    private fun createNotificationChannels() {
        val urgentChannel = NotificationChannel(URGENT_ALARM_CHANNEL_ID, "Urgent Sensor Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Critical alerts requiring immediate attention."
        }
        val regularChannel = NotificationChannel(REGULAR_ALARM_CHANNEL_ID, "Regular Sensor Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Initial sensor warnings."
        }
        val infoChannel = NotificationChannel(INFO_NOTIFICATION_CHANNEL_ID, "Sensor Info Notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "General info like returning to normal levels."
        }
        notificationManager.createNotificationChannel(urgentChannel)
        notificationManager.createNotificationChannel(regularChannel)
        notificationManager.createNotificationChannel(infoChannel)
    }

    /**
     * Clears the visual notification from the user's screen.
     *
     * @param notificationId The ID of the notification to clear.
     */
    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    companion object {
        private const val URGENT_ALARM_CHANNEL_ID: String = "urgent_alarms"
        private const val REGULAR_ALARM_CHANNEL_ID: String = "regular_alarms"
        private const val INFO_NOTIFICATION_CHANNEL_ID: String = "info_alarms"
    }
}