package com.example.umco2monitor

import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SensorViewModelAndroidTest {

    private lateinit var application: android.app.Application

    @Before
    fun setup() {
        // Get the real Application context from the device
        application = ApplicationProvider.getApplicationContext()

        // Ensure the BluetoothHandler is initialized so the ViewModel doesn't crash
        BluetoothHandler.initialize(application)
        BluetoothHandler.reset()
    }

    @Test
    fun testViewModelFactory_createsViewModelOnDevice() {
        // 1. GIVEN: Your custom Factory
        val factory = SensorViewModelFactory(application)

        // 2. WHEN: Ask ViewModelProvider to create the ViewModel USING your factory
        // This is how it's actually done in your MainActivity or Compose code
        val viewModel = ViewModelProvider(
            androidx.lifecycle.ViewModelStore(),
            factory
        )[SensorViewModel::class.java]

        // 3. THEN: Verify it was created successfully
        assertNotNull("ViewModel should be created successfully on device using custom factory", viewModel)
        assertEquals(SensorViewModel::class.java, viewModel::class.java)
    }

    @Test
    fun testStartScan_logicOnRealDevice() {
        // This test verifies that on a real device (regardless of permissions),
        // the startScan function can execute its version-check logic without crashing.
        val viewModel = SensorViewModel(application)

        // We can't easily check for 'Error' state here because it depends on
        // the device's actual permission state, but we verify it runs to completion.
        try {
            viewModel.startScan()
        } catch (e: Exception) {
            org.junit.Assert.fail("startScan crashed on a real device: ${e.message}")
        }
    }
}