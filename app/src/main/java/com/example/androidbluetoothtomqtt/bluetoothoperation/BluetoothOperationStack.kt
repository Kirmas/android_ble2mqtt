package com.example.androidbluetoothtomqtt.bluetoothoperation

import android.bluetooth.BluetoothGatt
import java.util.Stack

class BluetoothOperation(
    val operation: (gatt: BluetoothGatt) -> Unit
)

class BluetoothOperationStack {
    private val stack = Stack<BluetoothOperation>()

    fun addOperation(operation: BluetoothOperation) {
        stack.push(operation)
    }

    fun performNextOperation(gatt: BluetoothGatt) {
        if (!stack.isEmpty()) {
            val operation = stack.pop()
            operation.operation.invoke(gatt)
        }
    }
}