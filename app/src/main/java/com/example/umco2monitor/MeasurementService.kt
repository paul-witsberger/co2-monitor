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

    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dataRecorder: DataRecorder

    /**
     * Called when the service is created.
     */
    override fun onCreate() {
        super.onCreate()
        Timber.d("MeasurementService is being created.")
        val repository: SensorRepository = SensorRepository(SensorDatabase.getInstance(this).sensorDataDao())
        dataRecorder = DataRecorder(this, repository, serviceScope)
    }

    /**
     * Called when the service is started.
     *
     * @param intent The intent used to start the service.
     * @param flags Additional data about this start request.
     * @param startId A unique integer representing this specific request to start.
     * @return The return value indicates what semantics the system should use for the service's current started state.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("MeasurementService is starting.")
        createNotificationChannel()
        val notification: Notification = createServiceNotification()

        // On API 33 and above, POST_NOTIFICATIONS permission is required
        val postNotificationsGranted: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // On API 32 and below, no runtime permission is needed
        }

        if (!postNotificationsGranted) {
            Timber.w("Cannot start foreground service: POST_NOTIFICATIONS permission not granted.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    /**
     * Called when the service is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        Timber.d("MeasurementService is being destroyed.")
        serviceScope.cancel()
    }

    /**
     * Called when a client binds to the service.
     *
     * @param intent The intent used to bind to the service.
     * @return The IBinder for communication, or null if binding is not supported.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createServiceNotification(): Notification {
        val notificationIntent: Intent = Intent(this, MainActivity::class.java)
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UM CO2 Monitor")
            .setContentText("Actively monitoring sensor readings.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val serviceChannel: NotificationChannel = NotificationChannel(
            CHANNEL_ID,
            "Measurement Service",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager: NotificationManager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    companion object {
        private const val NOTIFICATION_ID: Int = 1
        private const val CHANNEL_ID: String = "MeasurementServiceChannel"
    }
}
