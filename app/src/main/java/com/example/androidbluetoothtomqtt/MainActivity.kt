@file:Suppress("DEPRECATION")
package com.example.androidbluetoothtomqtt

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.androidbluetoothtomqtt.ui.theme.AndroidBluetoothToMqttTheme
import com.example.androidbluetoothtomqtt.yaml.ConfigHandler
import com.example.androidbluetoothtomqtt.yaml.DeviceHandler
import com.vmadalin.easypermissions.EasyPermissions
import com.vmadalin.easypermissions.annotations.AfterPermissionGranted

@SuppressLint("MutableCollectionMutableState")
class MainActivity : ComponentActivity() {
    enum class MenuItems {
        Settings,
        Devices
    }

    private val TAG = "MainActivity"
    private var selectedMenu by mutableStateOf(MenuItems.Devices)
    private lateinit var webServer: ConfigurationServer

    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.INTERNET,
            Manifest.permission.FOREGROUND_SERVICE
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET,
        )
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webServer = ConfigurationServer()
        webServer.start()

        setContent {
            AndroidBluetoothToMqttTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Greeting()
                }
            }
        }

        if (BluetoothAdapter.getDefaultAdapter() == null) //if bluetooth is turned off, then you need to ask to turn it on on the service, this will not affect the service, since the idea is that the user can pull the BT here and there (why do it on the status of the device?)
        {
            Log.e(TAG, "Bluetooth is not supported on this device")
            return
        }

        ConfigHandler.init(filesDir)
        ConfigHandler.loadFromYaml()
        ConfigHandler.bind(::onConfigUpdated)
        DeviceHandler.init(filesDir)
        DeviceHandler.loadFromYaml()
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (EasyPermissions.hasPermissions(this, *permissions)) {
            startBluetoothToMQTTService()
        } else {
            EasyPermissions.requestPermissions(
                this,
                "Для роботи програми потрібен доступ до блютуз та інтернет",
                PERMISSION_REQUEST_CODE,
                *permissions
            )
        }
    }

    @AfterPermissionGranted(PERMISSION_REQUEST_CODE)
    private fun permissionGranted() {
        startBluetoothToMQTTService()
    }

    private fun onConfigUpdated() {
        restartBluetoothToMQTTService()
    }

    private fun restartBluetoothToMQTTService() {
        if(isServiceRunning()) {
            stopService(Intent(this, ServiceBluetoothToMQTT::class.java))
        }
        startBluetoothToMQTTService()
    }

    private fun startBluetoothToMQTTService() {
        if(!ConfigHandler.isConfigLoaded()) {
            Log.i(TAG, "Config not loaded")
            return
        }

        if(!ConfigHandler.mqttServer.contains("tcp://"))
        {
            Log.e(TAG, "MQTT Server is not valid")
            return
        }

        if(!isServiceRunning()) {
            startForegroundService(Intent(this, ServiceBluetoothToMQTT::class.java))
        } else {
            Log.i(TAG, "Service already running")
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val services = manager.getRunningServices(Integer.MAX_VALUE)
        return services.any { it.service.className == ServiceBluetoothToMQTT::class.java.name }
    }

    override fun onDestroy() {
        super.onDestroy()
        ConfigHandler.unbind(::onConfigUpdated)
        webServer.stop()
    }

    @Composable
    fun Greeting() {
        val menuExpanded = remember { mutableStateOf(false) }
        MaterialTheme {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.TopStart)
                    ) {
                    Row {
                        IconButton(
                            onClick = { menuExpanded.value = !menuExpanded.value }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = menuExpanded.value,
                        onDismissRequest = { menuExpanded.value = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(text = "Налаштування")
                            },
                            onClick = { handleSettingsMenuItemClick(MenuItems.Settings) })
                        DropdownMenuItem(
                            text = {
                                Text(text = "Устройства")
                            },
                            onClick = { handleSettingsMenuItemClick(MenuItems.Devices) })
                    }
                }

                when (selectedMenu) {
                    MenuItems.Settings -> {
                        ShowSettings()
                    }
                    MenuItems.Devices -> {
                        ShowDevices()
                    }
                }
            }
        }
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun ShowSettings() {
        OutlinedTextField(
            value = ConfigHandler.mqttServer,
            onValueChange = { ConfigHandler.mqttServer = it },
            label = { Text("MQTT Server") },
        )
        OutlinedTextField(
            value = ConfigHandler.mqttTopic,
            onValueChange = { ConfigHandler.mqttTopic = it },
            label = { Text("MQTT Topic") }
        )
        OutlinedTextField(
            value = ConfigHandler.mqttUser,
            onValueChange = { ConfigHandler.mqttUser = it },
            label = { Text("MQTT User") }
        )
        OutlinedTextField(
            value = ConfigHandler.mqttPassword.joinToString(separator = ""),
            onValueChange = { newValue ->
                ConfigHandler.mqttPassword = newValue.toCharArray()
            },
            label = { Text("MQTT Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Button(
            onClick = {
                ConfigHandler.saveToYaml()
            }
        ) {
            Text(text = "Save and Restart Service")
        }
    }

    @Composable
    private fun ShowDevices() {
        val devices by DeviceHandler.availableBTDevice.collectAsState()
        val selectedDevices by DeviceHandler.selectedBTDevice.collectAsState()

        Column {
            devices.forEach { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Checkbox(
                        checked = selectedDevices.containsKey(device.address),
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                DeviceHandler.addSelectedDevice(device.address, device.name)
                            } else {
                                DeviceHandler.removeSelectedDevice(device.address)
                            }
                            DeviceHandler.saveToYaml()
                        }
                    )
                    Column(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f)
                    ) {
                        Text(text = device.name)
                        Text(text = device.address)
                        Text(text = "Distance: ${device.distance}")
                    }
                }
            }
        }
    }


    @Preview(showBackground = true)
    @Composable
    fun GreetingPreview() {
        AndroidBluetoothToMqttTheme {
            Greeting()
        }
    }

    private fun handleSettingsMenuItemClick(menuItem: MenuItems) {
        if (selectedMenu == menuItem) {
            return
        }

        selectedMenu = menuItem
    }
}
