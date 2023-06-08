package com.example.androidbluetoothtomqtt.device

import android.bluetooth.BluetoothDevice
import com.example.androidbluetoothtomqtt.ServiceBluetoothToMQTT

open class XiaomiDevice(connectedBTDevice: BluetoothDevice, serviceContext: ServiceBluetoothToMQTT) : BaseDevice(connectedBTDevice, serviceContext) {
}