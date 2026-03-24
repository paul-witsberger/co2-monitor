package com.example.umco2monitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.Test
import timber.log.Timber

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class BluetoothHandlerInstrumentedTest {

    @Before
    fun setup() {
        // Reset the BluetoothHandler state before any tests
        BluetoothHandler.reset()
    }

    @Test
    fun testInitialize() {
        // Get the real Android context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        // Call initialize
        BluetoothHandler.initialize(appContext)

        // Wait briefly for the initialization to complete
        Thread.sleep(200)

        // Verify that Timber was planted
        assertTrue("Timber should be planted", Timber.treeCount > 0)

        // Verify that centralManager is not null
        assertNotNull("Central Manager should be initialized", BluetoothHandler.centralManager)

        // Check if the dedicated BLE thread is actually running
        val bleThread = BluetoothHandler.handlerThread
        assertNotNull("HandlerThread should be initialized", bleThread)
        assertTrue("HandlerThread should be alive", bleThread.isAlive)
        assertEquals("BlessedBleThread", bleThread.name)

        // Check if the Handler is correctly bound to that thread's Looper
        val handler = BluetoothHandler.bleHandler
        assertNotNull("BLE Handler should be initialized", handler)
        assertEquals("Handler must use the BlessedBleThread looper",
            bleThread.looper, handler.looper)

        // If this returns false, it means the app doesn't have permissions or BT is off
        val isBluetoothAvailable = BluetoothHandler.centralManager.isBluetoothEnabled
        Timber.i("Test: Bluetooth enabled on device: $isBluetoothAvailable")
    }
}