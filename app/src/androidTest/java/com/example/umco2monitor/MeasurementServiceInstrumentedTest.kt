package com.example.umco2monitor

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertNull

@RunWith(AndroidJUnit4::class)
class MeasurementServiceInstrumentedTest {

    @Test
    fun testServiceBindReturnsNull() {
        val service: MeasurementService = MeasurementService()
        val context: Context = ApplicationProvider.getApplicationContext<Context>()
        val intent: Intent = Intent(context, MeasurementService::class.java)
        
        val binder: android.os.IBinder? = service.onBind(intent)
        assertNull("Binder should be null as onBind returns null", binder)
    }

    /**
     * Verifies that the service creates its mandatory notification channel upon starting.
     */
    @Test
    fun testServiceStartsAndCreatesNotificationChannel() {
        val context: Context = ApplicationProvider.getApplicationContext<Context>()
        val intent: Intent = Intent(context, MeasurementService::class.java)

        // Start the service
        context.startService(intent)

        // Verify channel exists
        val manager: android.app.NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        val channel: android.app.NotificationChannel? =
            manager.getNotificationChannel("MeasurementServiceChannel")

        org.junit.Assert.assertNotNull("Foreground service must create a notification channel", channel)

        context.stopService(intent)
    }
}
