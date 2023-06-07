package com.example.androidbluetoothtomqtt

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.androidbluetoothtomqtt.bluetoothoperation.BluetoothOperation
import com.example.androidbluetoothtomqtt.bluetoothoperation.BluetoothOperationStack
import com.example.androidbluetoothtomqtt.device.BaseDevice
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.pow

data class BTDeviceInformation(
    val name: String,
    val address: String,
    val distance: Double
)

class ServiceBluetoothToMQTT : Service() {

    private val TAG = "ServiceBluetooth"
    private val deviceNameToClass: Map<String, Class<out BaseDevice>> = mapOf(
        "LYWSD02" to com.example.androidbluetoothtomqtt.device.LYWSD02Device::class.java
    )
//        "LYWSD03MMC" to com.example.androidbluetoothtomqtt.device.LYWSD03MMCDevice::class.java,
//        "LYWSDCGQ" to com.example.androidbluetoothtomqtt.device.LYWSDCGQDevice::class.java,
//        "CGG1" to com.example.androidbluetoothtomqtt.device.CGG1Device::class.java,
//        "CGD1" to com.example.androidbluetoothtomqtt.device.CGD1Device::class.java,
//        "CGP1W" to com.example.androidbluetoothtomqtt.device.CGP1WDevice::class.java,
//        "MHO-C401" to com.example.androidbluetoothtomqtt.device.MHOC401Device::class.java,
//        "MHO-C303" to com.example.androidbluetoothtomqtt.device.MHOC303Device::class.java,
//        "JQJCY01YM" to com.example.androidbluetoothtomqtt.device.JQJCY01YMDevice::class.java,
//        "HHCCJCY01" to com.example.androidbluetoothtomqtt.device.HHCCJCY01Device::class.java,
//        "GCLS002" to com.example.androidbluetoothtomqtt.device.GCLS002Device::class.java,
//        "HHCCPOT002" to com.example.androidbluetoothtomqtt.device.HHCCPOT002Device::class.java,
//        "WX08ZM" to com.example.androidbluetoothtomqtt.device.WX08ZMDevice::class.java,
//        "MUE4094RT" to com.example.androidbluetoothtomqtt.device.MUE4094RTDevice::class.java,
//        "MHO-C401" to com.example.androidbluetoothtomqtt.device.MHOC401Device::class.java,
//        "MHO-C303" to com.example.androidbluetoothtomqtt.device.MHOC303Device::class.java,
//        "LYWSD03MMC" to com.example.androidbluetoothtomqtt.device.LYWSD03MMCDevice::class.java,
//        "CGD1" to com.example.androidbluetoothtomqtt.device.CGD1Device::class.java,
//        "CGG1" to com.example.androidbluetoothtomqtt.device.CGG1Device::class.java,
//        "CGP1W" to com.example.androidbluetooth

    private lateinit var mqttServer: String
    private lateinit var mqttTopic: String
    private lateinit var mqttUser: String
    private lateinit var mqttPassword: CharArray

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var mqttClient: MqttClient
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    var availableBTDevice: ArrayList<BTDeviceInformation> = arrayListOf()
    private var chosenBTDevice: ArrayList<String> = arrayListOf()
    private var createdDevices: ArrayList<BaseDevice> = arrayListOf()

    override fun onBind(intent: Intent?): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }

    override fun onCreate() {
        super.onCreate()

        val configFile = File(filesDir, "config.yaml")
        if (configFile.exists()) {
            val yaml = Yaml()
            val configData = configFile.readText()
            val configMap = yaml.load<Map<String, String>>(configData)
            mqttServer = configMap["mqtt_server"].orEmpty()
            mqttTopic = configMap["mqtt_topic"].orEmpty()
            mqttUser = configMap["mqtt_user"].orEmpty()
            mqttPassword = configMap["mqtt_password"].orEmpty().toCharArray()
        }
        else
        {
            Log.e(TAG, "config.yaml is missed")
            stopSelf()
            return
        }

        val channelId = "BluetoothToMQTTServiceChannel"
        val channelName = "Bluetooth to MQTT Service Channel"
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Bluetooth to MQTT Service")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_notification)
            .build()

        startForeground(1, notification)

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            ?: run {
                Log.e(TAG, "Bluetooth is not supported on this device")
                stopSelf()
                return
            }

        startBluetoothScan()

        connectToMqttBroker()
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        bluetoothLeScanner.startScan(listOf(), scanSettings, scanCallback)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        // Send a notification that service is started
        Log.d(TAG,"Service started.")

        return START_STICKY
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device

            if(deviceNameToClass.containsKey(device.name)) {
                if (!availableBTDevice.any { it.address == device.address }){
                    Log.d(TAG, "Found device: ${device.name} ${device.address} ${calculateDistance(result.rssi)} ")
                    availableBTDevice.add(BTDeviceInformation(device.name, device.address, calculateDistance(result.rssi)))
                }

//                if(chosenMac.contains(device.address) && !createdDevices.equals(device.address)) {
//                    //create new device using class from map and add it to createdDevices
//                    deviceNameToClass[device.name]?.let { deviceClass ->
//                        val deviceInstance = deviceClass.getConstructor(BluetoothDevice::class.java).newInstance(device) as BaseDevice
//                        createdDevices.add(deviceInstance)
//                        //connectToDevice(device)
//                    }
//
//
//                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Bluetooth LE scan failed with error code: $errorCode")
        }
    }

    /// function for colculating distance from rssi
    private fun calculateDistance(rssi: Int): Double {
        val txPower = -59 // Задана потужність передавача
        val ratio = rssi.toDouble() / txPower.toDouble()
        return if (ratio < 1.0) {
            ratio.pow(10.0)
        } else {
            val accuracy = (0.89976) * ratio.pow(7.7095) + 0.111
            10.0.pow((accuracy - 1) / 20)
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

            @SuppressLint("MissingPermission")
            private fun enableCharacteristicNotification(
                gatt: BluetoothGatt,
                service: BluetoothGattService,
                characteristicUUID: UUID
            ) {
                val characteristic = service.getCharacteristic(characteristicUUID)
                characteristic?.let {
                    val descriptor =
                        it.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    if (descriptor != null) {
                        gatt.setCharacteristicNotification(it, true)
                        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(
                                descriptor,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            )
                        }
                        else
                        {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
            }

            @SuppressLint("MissingPermission")
            private fun readCharacteristic(
                gatt: BluetoothGatt,
                service: BluetoothGattService,
                characteristicUUID: UUID
            ) {
                val characteristic = service.getCharacteristic(characteristicUUID)
                characteristic?.let {
                    gatt.readCharacteristic(it)
                }
            }

            private val operationStack = BluetoothOperationStack()

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val services: List<BluetoothGattService> = gatt.services
                for (service in services) {
                    if (service.uuid == UUID.fromString("ebe0ccb0-7a0a-4b0c-8a1a-6ff2997da3a6")) {
                        operationStack.addOperation(BluetoothOperation() { localGatt ->
                            enableCharacteristicNotification(localGatt, service, UUID.fromString("ebe0ccc1-7a0a-4b0c-8a1a-6ff2997da3a6"))
                        })
                        operationStack.addOperation(BluetoothOperation() { localGatt ->
                            readCharacteristic(localGatt, service, UUID.fromString("ebe0ccc4-7a0a-4b0c-8a1a-6ff2997da3a6"))
                        })

                        break
                    }
                }
                operationStack.performNextOperation(gatt)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                operationStack.performNextOperation(gatt)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (characteristic.uuid == UUID.fromString("ebe0ccc4-7a0a-4b0c-8a1a-6ff2997da3a6")) {
                    val value = characteristic.value
                    val battery = value[0].toInt()
                    Log.d(TAG, "Battery: $battery")

                    // Відправка даних на MQTT-брокер
                    val message = MqttMessage()
                    message.payload = "$battery%".toByteArray()
                    try {
                        mqttClient.publish("$mqttTopic/test", message)
                    } catch (e: MqttException) {
                        e.printStackTrace()
                    }
                }

                operationStack.performNextOperation(gatt)
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value:ByteArray, status: Int) {
                if (characteristic.uuid == UUID.fromString("ebe0ccc4-7a0a-4b0c-8a1a-6ff2997da3a6")) {
                    val battery = value[0].toInt()
                    Log.d(TAG, "Battery: $battery")

                    // Відправка даних на MQTT-брокер
                    val message = MqttMessage()
                    message.payload = "$battery%".toByteArray()
                    try {
                        mqttClient.publish("$mqttTopic/test", message)
                    } catch (e: MqttException) {
                        e.printStackTrace()
                    }
                }

                operationStack.performNextOperation(gatt)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == UUID.fromString("ebe0ccc1-7a0a-4b0c-8a1a-6ff2997da3a6")) {
                    val value = characteristic.value
                    val humidity = value[2].toInt()
                    val temperatureBytes = value.copyOfRange(0, 2)
                    val temperature =
                        ByteBuffer.wrap(temperatureBytes).order(ByteOrder.LITTLE_ENDIAN).short / 100.0f

                    Log.d(TAG, "Temperature: $temperature, Humidity: $humidity")

                    // Відправка даних на MQTT-брокер
                    val message = MqttMessage()
                    message.payload = "$temperature°C, $humidity%".toByteArray()
                    try {
                        mqttClient.publish("$mqttTopic/test", message)
                    } catch (e: MqttException) {
                        Log.e(TAG, "Failed to publish MQTT message: ${e.message}")
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value:ByteArray) {
                if (characteristic.uuid == UUID.fromString("ebe0ccc1-7a0a-4b0c-8a1a-6ff2997da3a6")) {
                    val humidity = value[2].toInt()
                    val temperatureBytes = value.copyOfRange(0, 2)
                    val temperature =
                        ByteBuffer.wrap(temperatureBytes).order(ByteOrder.LITTLE_ENDIAN).short / 100.0f

                    Log.d(TAG, "Temperature: $temperature, Humidity: $humidity")

                    // Відправка даних на MQTT-брокер
                    val message = MqttMessage()
                    message.payload = "$temperature°C, $humidity%".toByteArray()
                    try {
                        mqttClient.publish("$mqttTopic/test", message)
                    } catch (e: MqttException) {
                        Log.e(TAG, "Failed to publish MQTT message: ${e.message}")
                    }
                }
            }
        }

        device.connectGatt(this, false, gattCallback)
    }

    private fun connectToMqttBroker() {
        val clientId = MqttClient.generateClientId()
        mqttClient = MqttClient(mqttServer, clientId, MemoryPersistence())

        val options = MqttConnectOptions()
        options.userName = mqttUser
        options.password = mqttPassword

        try {
            mqttClient.connect(options)
            Log.d(TAG, "Connected to MQTT broker")
        } catch (e: MqttException) {
            Log.e(TAG, "Failed to connect to MQTT broker: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()

        bluetoothLeScanner.stopScan(scanCallback)
        if (mqttClient.isConnected) {
            mqttClient.disconnect()
        }
    }
}