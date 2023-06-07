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
import com.example.androidbluetoothtomqtt.R
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class ServiceBluetoothToMQTT : Service() {

    private val TAG = "ServiceBluetooth"
    private val MQTT_SERVER = "tcp://192.168.0.3:1883"
    private val MQTT_TOPIC = "bluetooth2mqtt"
    private val MQTT_USER = "MQTTUser"
    private val MQTT_PASSWORD = "2505"

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var mqttClient: MqttClient
    private lateinit var bluetoothLeScanner: BluetoothLeScanner

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            ?: run {
                Log.e(TAG, "Bluetooth is not supported on this device")
                stopSelf()
                return
            }

        startBluetoothScan()

        connectToMqttBroker()
    }

    private fun startBluetoothScan() {
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        bluetoothLeScanner.startScan(listOf(), scanSettings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device

            if (device.name == "LYWSD02") {
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Bluetooth LE scan failed with error code: $errorCode")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        val gattCallback = object : BluetoothGattCallback() {
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
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }

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

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val services: List<BluetoothGattService> = gatt.services
                for (service in services) {
                    if (service.uuid.toString() == "ebe0ccb0-7a0a-4b0c-8a1a-6ff2997da3a6") {
                        //enableCharacteristicNotification(gatt, service, UUID.fromString("ebe0ccc1-7a0a-4b0c-8a1a-6ff2997da3a6"))
                        readCharacteristic(
                            gatt,
                            service,
                            UUID.fromString("ebe0ccc4-7a0a-4b0c-8a1a-6ff2997da3a6")
                        )
                        break
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val data = characteristic.value

                if (data != null) {
                    if (characteristic.uuid == UUID.fromString("ebe0ccc1-7a0a-4b0c-8a1a-6ff2997da3a6")) {
                        val humidity = data[2].toInt()
                        val temperatureBytes = data.copyOfRange(0, 2)
                        val temperature =
                            ByteBuffer.wrap(temperatureBytes).order(ByteOrder.LITTLE_ENDIAN).short / 100.0f

                        Log.d(TAG, "Temperature: $temperature, Humidity: $humidity")

                        // Відправка даних на MQTT-брокер
                        val message = MqttMessage()
                        message.payload = "$temperature°C, $humidity%".toByteArray()
                        try {
                            mqttClient.publish("$MQTT_TOPIC/test", message)
                        } catch (e: MqttException) {
                            Log.e(TAG, "Failed to publish MQTT message: ${e.message}")
                        }
                    }
                }
            }
        }

        device.connectGatt(this, false, gattCallback)
    }

    private fun connectToMqttBroker() {
        val clientId = MqttClient.generateClientId()
        mqttClient = MqttClient(MQTT_SERVER, clientId, MemoryPersistence())

        val options = MqttConnectOptions()
        options.userName = MQTT_USER
        options.password = MQTT_PASSWORD.toCharArray()

        try {
            mqttClient.connect(options)
            Log.d(TAG, "Connected to MQTT broker")
        } catch (e: MqttException) {
            Log.e(TAG, "Failed to connect to MQTT broker: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        bluetoothLeScanner.stopScan(scanCallback)
        mqttClient.disconnect()
    }
}