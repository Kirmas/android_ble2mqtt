@file:Suppress("DEPRECATION")

package com.example.androidbluetoothtomqtt

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import com.example.androidbluetoothtomqtt.device.BaseDevice
import com.example.androidbluetoothtomqtt.device.LYWSD02Device
import com.example.androidbluetoothtomqtt.yaml.BTDeviceInformation
import com.example.androidbluetoothtomqtt.yaml.ConfigHandler
import com.example.androidbluetoothtomqtt.yaml.DeviceHandler
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import kotlin.math.pow

class ServiceBluetoothToMQTT : Service(), MqttCallbackExtended {

    private val TAG = "ServiceBluetooth"
    private val deviceNameToClass: Map<String, Pair<Class<LYWSD02Device>, UUID>> = mapOf(
        "LYWSD02" to Pair(LYWSD02Device::class.java, UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb"))
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
    private val defaultStatusTopic = "homeassistant/status"
    private val statusTopic = "hass/status"

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var mqttClient: MqttClient
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private var isSendData: Boolean = false
    private var createdDevices: ArrayList<BaseDevice> = arrayListOf()
    private val scanHandler = Handler()
    private var isScanning: Boolean = false

    override fun onBind(intent: Intent?): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()

        ConfigHandler.init(filesDir)
        ConfigHandler.loadFromYaml()
        if(!ConfigHandler.isConfigLoaded()) {
            stopSelf()
            return
        }
        DeviceHandler.init(filesDir)
        DeviceHandler.loadFromYaml()

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

        startScan()
        connectToMqttBroker()
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothScan() {
        if (isScanning) {
            Log.d(TAG,"Bluetooth Scan Stoped.")
            bluetoothLeScanner.stopScan(scanCallback)
            isScanning = false
        }
    }

    private fun startScan() {
        stopBluetoothScan()
        startBluetoothScan()

        scanHandler.postDelayed({ startScan() }, 10 * 60 * 1000)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        val scanFilters = arrayListOf<ScanFilter>()
        for (deviceName in deviceNameToClass.keys) {
            val scanFilter = ScanFilter.Builder()
                .setDeviceName(deviceName)
                .build()
            scanFilters.add(scanFilter)
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        bluetoothLeScanner.startScan(scanFilters, scanSettings, scanCallback)

        Log.d(TAG,"Bluetooth Scan Started.")
        isScanning = true
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        // Send a notification that service is started
        Log.d(TAG,"Service started.")

        return START_STICKY
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val btDevice: BluetoothDevice = result.device

            if(deviceNameToClass.containsKey(btDevice.name)) {
                if (!DeviceHandler.getAvailableDevice().any { it.address == btDevice.address }){
                    Log.d(TAG, "Found device: ${btDevice.name} ${btDevice.address} ${calculateDistance(result.rssi)} ")
                    DeviceHandler.addAvailableDevice(BTDeviceInformation(btDevice.name, btDevice.address, calculateDistance(result.rssi)))
                }

                if(DeviceHandler.getSelectedDevice().containsKey(btDevice.address)) {
                    val createdDevice = createdDevices.find { device ->
                        device.isMacAddressEqual(btDevice.address)
                    }
                    deviceNameToClass[btDevice.name]?.let { deviceClassPair ->
                        val deviceClass = deviceClassPair.first
                        val serviceUuid = deviceClassPair.second
                        if(createdDevice == null) {
                            val deviceInstance = deviceClass.getConstructor(
                                BluetoothDevice::class.java,
                                ServiceBluetoothToMQTT::class.java
                            ).newInstance(btDevice, this@ServiceBluetoothToMQTT) as BaseDevice
                            val serviceData = result.scanRecord?.getServiceData(ParcelUuid(serviceUuid))
                            createdDevices.add(deviceInstance)
                            deviceInstance.created()
                            if (serviceData != null) {
                                deviceInstance.newPassiveBLEData(serviceData)
                            }
                        } else {
                            val serviceData = result.scanRecord?.getServiceData(ParcelUuid(serviceUuid))
                            if (serviceData != null) {
                                createdDevice.newPassiveBLEData(serviceData)
                            }
                        }
                    }
                }
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

    private fun connectToMqttBroker() {
        val clientId = MqttClient.generateClientId()
        mqttClient = MqttClient(ConfigHandler.mqttServer, clientId, MemoryPersistence())
        mqttClient.setCallback(this)

        val options = MqttConnectOptions()
        options.userName = ConfigHandler.mqttUser
        options.password = ConfigHandler.mqttPassword
        options.isAutomaticReconnect = true

        try {
            mqttClient.connect(options)
            Log.d(TAG, "Connected to MQTT broker")

            mqttClient.subscribe(defaultStatusTopic)
            mqttClient.subscribe(statusTopic)
            Log.d(TAG, "Subscribed to topic: $defaultStatusTopic")
        } catch (e: MqttException) {
            Log.e(TAG, "Failed to connect to MQTT broker: ${e.message}")
        }
    }

    fun publish(subTopic: String, message: MqttMessage)
    {
        Log.d(TAG, "${ConfigHandler.mqttTopic}/$subTopic $message")
        mqttClient.publish("${ConfigHandler.mqttTopic}/$subTopic", message)
    }

    fun registerPublish(type: String, subTopic: String, devClass: String, message: MqttMessage)
    {
        Log.d(TAG, "homeassistant/$type/$subTopic/$devClass/config $message")
        mqttClient.publish("homeassistant/$type/$subTopic/$devClass/config", message)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed.")

        bluetoothLeScanner.stopScan(scanCallback)
        if (mqttClient.isConnected) {
            mqttClient.disconnect()
        }
    }

    override fun connectionLost(cause: Throwable?) {
        Log.d(TAG, "Connection lost ${cause.toString()}")
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        Log.d(TAG, "Receive message: ${message.toString()} from topic: $topic")

        if((topic == statusTopic || topic == defaultStatusTopic) && message.toString() == "online") {
            createdDevices.forEach { device ->
                device.mqttReconnected()
            }
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) {
        for (topic in token!!.topics) {
            Log.d(TAG, "Delivery complete $topic")
        }
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        if (reconnect) {
            createdDevices.forEach { device ->
                device.mqttReconnected()
            }

            Log.d(TAG, "Reconnected to : $serverURI")
        } else {
            Log.d(TAG, "Connected to: $serverURI")
        }
    }
}