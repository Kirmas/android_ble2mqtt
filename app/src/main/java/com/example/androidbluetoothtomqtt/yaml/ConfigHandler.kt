package com.example.androidbluetoothtomqtt.yaml;
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

object ConfigHandler : YamlHandler( "config.yaml") {
    var mqttServer by mutableStateOf<String>("tcp://localhost:1883")
    var mqttTopic by mutableStateOf<String>("bluetooth2mqtt")
    var mqttUser by mutableStateOf<String>("")
    var mqttPassword by mutableStateOf<CharArray>(CharArray(0) { ' ' })

    override fun setDataTo(newData: Map<String, String>) {
        mqttServer = newData["mqtt_server"].orEmpty()
        mqttTopic = newData["mqtt_topic"].orEmpty()
        mqttUser = newData["mqtt_user"].orEmpty()
        mqttPassword = newData["mqtt_password"].orEmpty().toCharArray()
    }

    override fun getDataFrom(): Map<String, String> {
        return mapOf(
            "mqtt_server" to mqttServer,
            "mqtt_topic" to mqttTopic,
            "mqtt_user" to mqttUser,
            "mqtt_password" to String(mqttPassword)
        )
    }
}
