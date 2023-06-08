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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.androidbluetoothtomqtt.device.BaseDevice
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.math.pow

data class BTDeviceInformation(
    val name: String,
    val address: String,
    val distance: Double
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readDouble()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeString(address)
        parcel.writeDouble(distance)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<BTDeviceInformation> {
        override fun createFromParcel(parcel: Parcel): BTDeviceInformation {
            return BTDeviceInformation(parcel)
        }

        override fun newArray(size: Int): Array<BTDeviceInformation?> {
            return arrayOfNulls(size)
        }
    }
}

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
    private var isSendData: Boolean = false
    var availableBTDevice: ArrayList<BTDeviceInformation> = arrayListOf()
    private var selectedBTDevice: Map<String, String> = mapOf()
    private var createdDevices: ArrayList<BaseDevice> = arrayListOf()
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var broadcastReceiver: BroadcastReceiver

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

        loadSelectedBTDevice()

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

        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if(intent.hasExtra("SendData")) {
                    isSendData = intent.getBooleanExtra("SendData", false)
                    if (isSendData) {
                        sendAvailableBTDevise()
                    }
                }
                else if (intent.hasExtra("SelectedDevice")){
                    loadSelectedBTDevice()
                }
            }
        }
        val intentFilter = IntentFilter("com.example.mainActivity")
        localBroadcastManager.registerReceiver(broadcastReceiver, intentFilter)

        startBluetoothScan()

        connectToMqttBroker()
    }

    private fun loadSelectedBTDevice() {
        val deviceFile = File(filesDir, "device.yaml")
        if (deviceFile.exists()) {
            val yaml = Yaml()
            val configData = deviceFile.readText()
            selectedBTDevice = yaml.load<Map<String, String>>(configData)
        }
    }

    private fun sendAvailableBTDevise() {
        val intent = Intent("com.example.bluetoothToMQTT")
        intent.putExtra("AvailableBTDevice", availableBTDevice)
        localBroadcastManager.sendBroadcast(intent)
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
            val btDevice: BluetoothDevice = result.device

            if(deviceNameToClass.containsKey(btDevice.name)) {
                if (!availableBTDevice.any { it.address == btDevice.address }){
                    Log.d(TAG, "Found device: ${btDevice.name} ${btDevice.address} ${calculateDistance(result.rssi)} ")
                    availableBTDevice.add(BTDeviceInformation(btDevice.name, btDevice.address, calculateDistance(result.rssi)))
                    if (isSendData) {
                        sendAvailableBTDevise()
                    }
                }

                if(selectedBTDevice.containsKey(btDevice.address) && !createdDevices.any{ device ->
                        device.isMacAddressEqual(btDevice.address)
                    }) {
                    //create new device using class from map and add it to createdDevices
                    deviceNameToClass[btDevice.name]?.let { deviceClass ->
                        val deviceInstance = deviceClass.getConstructor(BluetoothDevice::class.java, ServiceBluetoothToMQTT::class.java).newInstance(btDevice, this@ServiceBluetoothToMQTT) as BaseDevice
                        createdDevices.add(deviceInstance)
                        deviceInstance.created()
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

    fun publish(subTopic: String, message: MqttMessage)
    {
        Log.d(TAG, "$mqttTopic/$subTopic $message")
        mqttClient.publish("$mqttTopic/$subTopic", message)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()

        localBroadcastManager.unregisterReceiver(broadcastReceiver)

        bluetoothLeScanner.stopScan(scanCallback)
        if (mqttClient.isConnected) {
            mqttClient.disconnect()
        }
    }
}