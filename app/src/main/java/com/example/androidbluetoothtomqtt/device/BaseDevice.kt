package com.example.androidbluetoothtomqtt.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.util.Log
import com.example.androidbluetoothtomqtt.ServiceBluetoothToMQTT
import com.example.androidbluetoothtomqtt.bluetoothoperation.BluetoothOperation
import com.example.androidbluetoothtomqtt.bluetoothoperation.BluetoothOperationStack
import com.example.androidbluetoothtomqtt.yaml.ConfigHandler
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Suppress("unused") //REVIEW: remove this after implementing Write
enum class CharacteristicAccessType{
    Notification,
    Read,
    Write
}

data class AvailableCharacteristicInformation(
    val serviceUUID: UUID,
    val characteristicUUID: UUID,
    val type: CharacteristicAccessType
)

data class SubDevice(
    val deviceType: String,
    val deviceClass: String,
    val enabledByDefault: Boolean,
    val entityCategory: String,
    val stateClass: String,
    val unitOfMeasurement: String,
)

abstract class BaseDevice(private val connectedBTDevice: BluetoothDevice, private val serviceContext: ServiceBluetoothToMQTT) {
    private val TAG = "BaseDevice"

    open val availableCharacteristics: ArrayList<AvailableCharacteristicInformation> = arrayListOf()
    abstract val manufacturer: String
    abstract val subDevices: ArrayList<SubDevice>
    abstract val model: String

    open val autoConnect: Boolean
        get() {
            return false
        }


    private val deviceName: String
        get() {
            return connectedBTDevice.address.replace(":", "").lowercase()
        }

    fun isMacAddressEqual (macAddress: String): Boolean {
        return connectedBTDevice.address == macAddress
    }

    fun created() {
        sendInitialPayload()
        if(autoConnect && availableCharacteristics.isNotEmpty()) connectToDevice(connectedBTDevice)
    }

    fun mqttReconnected() {
        sendInitialPayload()
        sendData2MQTT()
    }

    private fun sendInitialPayload() {
        for (subDevice in subDevices) {
            val message = MqttMessage()
            message.payload = createInitialPayloadForSubDevice(subDevice)
            try {
                serviceContext.registerPublish(
                    subDevice.deviceType,
                    deviceName,
                    subDevice.deviceClass,
                    message
                )
            } catch (e: MqttException) {
                e.printStackTrace()
            }
        }
    }

    abstract fun newPassiveBLEData(newData: ByteArray)


    abstract fun enableCharacteristicNotification(
        gatt: BluetoothGatt,
        service: BluetoothGattService,
        characteristicUUID: UUID)

    @SuppressLint("MissingPermission")
    open fun readCharacteristic(
        gatt: BluetoothGatt,
        service: BluetoothGattService,
        characteristicUUID: UUID
    ) {
        val characteristic = service.getCharacteristic(characteristicUUID)
        characteristic?.let {
            gatt.readCharacteristic(it)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        val gattCallback = object : BluetoothGattCallback() {

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    Log.d(TAG, "Connected to device: ${device.name}")
                    gatt.discoverServices()
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    Log.d(TAG, "Disconnected from device: ${device.name}")
                    // reconnecting
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        gatt.connect()
                    }
                }
            }


            private val operationStack = BluetoothOperationStack()

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val services: List<BluetoothGattService> = gatt.services
                for (service in services) {
                    for (availableCharacteristic in availableCharacteristics)
                    {
                        if (service.uuid == availableCharacteristic.serviceUUID) {
                            if(availableCharacteristic.type == CharacteristicAccessType.Notification) {
                                operationStack.addOperation(BluetoothOperation() { localGatt ->
                                    enableCharacteristicNotification(localGatt, service, availableCharacteristic.characteristicUUID)
                                })
                            }
                            else if(availableCharacteristic.type == CharacteristicAccessType.Read) {
                                operationStack.addOperation(BluetoothOperation() { localGatt ->
                                    readCharacteristic(localGatt, service, availableCharacteristic.characteristicUUID)
                                })
                            }
                        }
                    }
                }
                operationStack.performNextOperation(gatt)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                operationStack.performNextOperation(gatt)
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                onCharacteristicRead(characteristic.uuid, characteristic.value)

                operationStack.performNextOperation(gatt)
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value:ByteArray, status: Int) {
                onCharacteristicRead(characteristic.uuid, value)

                operationStack.performNextOperation(gatt)
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                onCharacteristicChanged(characteristic.uuid, characteristic.value)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value:ByteArray) {
                onCharacteristicChanged(characteristic.uuid, value)
            }
        }

        device.connectGatt(serviceContext, false, gattCallback)
    }

    protected fun sendData2MQTT() {
        // Відправка даних на MQTT-брокер
        val message = MqttMessage()
        message.payload = createPayload()
        try {
            serviceContext.publish(deviceName, message)
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    fun createInitialPayloadForSubDevice(subDevice: SubDevice): ByteArray {
        val jsonObject = JSONObject()
        val deviceObject = JSONObject()
        val identifiersArray = JSONArray()

        identifiersArray.put("${ConfigHandler.mqttTopic}_${deviceName}")

        deviceObject.put("identifiers", identifiersArray)
        deviceObject.put("manufacturer", manufacturer)
        deviceObject.put("model", model)
        deviceObject.put("name", deviceName)

        jsonObject.put("device", deviceObject)
        jsonObject.put("device_class", subDevice.deviceClass)
        jsonObject.put("enabled_by_default", subDevice.enabledByDefault)
        if(subDevice.entityCategory.isNotEmpty()) {
            jsonObject.put("entity_category", subDevice.entityCategory)
        }
        jsonObject.put("json_attributes_topic", "${ConfigHandler.mqttTopic}/${deviceName}")
        jsonObject.put("name", "$deviceName ${subDevice.deviceClass}")
        jsonObject.put("state_class", subDevice.stateClass)
        jsonObject.put("state_topic", "${ConfigHandler.mqttTopic}/${deviceName}")
        jsonObject.put("unique_id", "${deviceName}_${subDevice.deviceClass}_${ConfigHandler.mqttTopic}")
        jsonObject.put("unit_of_measurement", subDevice.unitOfMeasurement)
        jsonObject.put("value_template", "{{ value_json.${subDevice.deviceClass} }}")
        jsonObject.put("platform", "mqtt")

        return jsonObject.toString().toByteArray()
    }

    open fun createPayload() = byteArrayOf()

    abstract fun onCharacteristicChanged(
        characteristicUUID: UUID,
        value: ByteArray
    )

    abstract fun onCharacteristicRead(
        characteristicUUID: UUID,
        value: ByteArray
    )
}