package com.example.umco2monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.Manifest
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber

/**
 * A foreground service that ensures continuous BLE connection and data recording.
 */
class MeasurementService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dataRecorder: DataRecorder

    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "MeasurementServiceChannel"

    override fun onCreate() {
        super.onCreate()
        Timber.d("MeasurementService is being created.")
        val repository = SensorRepository(SensorDatabase.getInstance(this).sensorDataDao())
        dataRecorder = DataRecorder(this, repository, serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("MeasurementService is starting.")
        createNotificationChannel()
        val notification = createServiceNotification()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {

            Timber.w("Cannot start foreground service: POST_NOTIFICATIONS permission not granted.")
            // If permission is not granted, you cannot start the foreground service.
            // Stop the service to prevent it from running in a broken state.
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC // Specify the type
            )
        } else {
            // Fallback for older versions
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY // Ensures the service is restarted if killed by the system
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("MeasurementService is being destroyed.")
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createServiceNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UM CO2 Monitor")
            .setContentText("Actively monitoring sensor readings.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use an appropriate icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Measurement Service",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }
}
