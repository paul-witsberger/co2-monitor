package com.example.umco2monitor

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

// UUID for the CCCD (Client Characteristic Configuration Descriptor); this is a standard UUID
private val CCCD_UUID: UUID = UUID.fromString("0x2902")

// Standard UUIDs exist for temperature and humidity characteristics and environmental sensing service
val ENVS_SERVICE_UUID: UUID = UUID.fromString("0x181a")
val TEMPERATURE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0x2a6e")
val HUMIDITY_CHARACTERISTIC_UUID: UUID = UUID.fromString("0x2a6f")

// Version 4 UUIDs generated from https://www.uuidgenerator.net/
val SENSOR_SERVICE_UUID: UUID = UUID.fromString("3e6cebcd-d4f8-46e2-9513-056d94a6377c")
val CO2_CHARACTERISTIC_UUID: UUID = UUID.fromString("b70c91c7-40b6-461f-aeff-4b15a16fd0e7")

class ArduinoBleManager(private val context: Context) {

    private val _co2Value: MutableStateFlow<Int?> = MutableStateFlow<Int?>(null)
    val co2Value: StateFlow<Int?> = _co2Value.asStateFlow()

    private val _connectionState: MutableStateFlow<Int> = MutableStateFlow<Int>(BluetoothProfile.STATE_DISCONNECTED)
    val connectionState: StateFlow<Int> = _connectionState.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            _connectionState.value = newState
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _co2Value.value = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service: BluetoothGattService? = gatt.getService(SENSOR_SERVICE_UUID)
                val characteristic: BluetoothGattCharacteristic? = service?.getCharacteristic(CO2_CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    enableNotifications(gatt, characteristic)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CO2_CHARACTERISTIC_UUID) {
                val co2: Int? = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0)
                _co2Value.value = co2
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt = device.connectGatt(context, true, gattCallback)
        }
    }

    fun disconnect() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor: BluetoothGattDescriptor? = characteristic.getDescriptor(CCCD_UUID)
        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }
}