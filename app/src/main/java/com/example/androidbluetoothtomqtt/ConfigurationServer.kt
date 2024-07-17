package com.example.androidbluetoothtomqtt

import com.example.androidbluetoothtomqtt.yaml.ConfigHandler
import com.example.androidbluetoothtomqtt.yaml.DeviceHandler
import com.google.gson.Gson
import io.ktor.http.ContentType.Text.Html
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.gson.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.server.request.*
import kotlinx.html.*
import kotlinx.html.stream.createHTML

fun Application.module() {
    install(ContentNegotiation) {
        gson()
    }

    routing {
        get("/") {
            val htmlContent = createHTML().html {
                head {
                    script {
                        unsafe {
                            +"""
                    function submitForm() {
                        const form = document.getElementById('configForm');
                        const data = {
                            mqttServer: form.mqttServer.value,
                            mqttTopic: form.mqttTopic.value,
                            mqttUser: form.mqttUser.value,
                            mqttPassword: form.mqttPassword.value
                        };

                        fetch('/api/config', {
                            method: 'POST',
                            headers: {
                                'Content-Type': 'application/json'
                            },
                            body: JSON.stringify(data)
                        })
                        .then(response => response.json())
                        .then(data => {
                            console.log('Success:', data);
                        })
                        .catch((error) => {
                            console.error('Error:', error);
                        });
                    }
                    function toggleDevice(address, isChecked) {
                        fetch('/api/device/toggle', {
                            method: 'POST',
                            headers: {
                                'Content-Type': 'application/json'
                            },
                            body: JSON.stringify({ address: address, isChecked: isChecked })
                        })
                        .then(response => response.json())
                        .then(data => {
                            console.log('Success:', data);
                        })
                        .catch((error) => {
                            console.error('Error:', error);
                        });
                    }
                    """.trimIndent()
                        }
                    }
                }
                body {
                    form {
                        id = "configForm"
                        p {
                            +"mqttServer: "
                            textInput(name = "mqttServer") {
                                id = "mqttServer"
                                value = ConfigHandler.mqttServer
                            }
                        }
                        p {
                            +"mqttTopic: "
                            textInput(name = "mqttTopic") {
                                id = "mqttTopic"
                                value = ConfigHandler.mqttTopic
                            }
                        }
                        p {
                            +"mqttUser: "
                            textInput(name = "mqttUser") {
                                id = "mqttUser"
                                value = ConfigHandler.mqttUser
                            }
                        }
                        p {
                            +"mqttPassword: "
                            passwordInput(name = "mqttPassword") {
                                id = "mqttPassword"
                                value = ConfigHandler.mqttPassword.joinToString(separator = "")
                            }
                        }
                        p {
                            button {
                                type = ButtonType.button
                                onClick = "submitForm()"
                                +"Login"
                            }
                        }
                    }
                    table {
                        tr {
                            th { +"Name" }
                            th { +"Address" }
                            th { +"Distance" }
                            th { +"Selected" }
                        }
                        DeviceHandler.getAvailableDevice().forEach { device ->
                            tr {
                                td { +device.name }
                                td { +device.address }
                                td { +device.distance.toString() }
                                td {
                                    input(type = InputType.checkBox) {
                                        id = device.address
                                        checked =
                                            DeviceHandler.getSelectedDevice().containsKey(device.address)
                                        onClick = "toggleDevice('${device.address}', this.checked)"
                                    }
                                }
                            }
                        }
                    }
                }
            }
            call.respondText(htmlContent, Html)
        }
        route("/api/config"){
            post {
                val data = call.receive<Map<String, String>>()
                ConfigHandler.mqttServer = data["mqttServer"].orEmpty()
                ConfigHandler.mqttTopic = data["mqttTopic"].orEmpty()
                ConfigHandler.mqttUser = data["mqttUser"].orEmpty()
                ConfigHandler.mqttPassword = data["mqttPassword"].orEmpty().toCharArray()
                ConfigHandler.saveToYaml()
                call.respond(mapOf("message" to "Config updated"))
            }
            get {
                call.respond (mapOf(
                    "mqttServer" to ConfigHandler.mqttServer,
                    "mqttTopic" to ConfigHandler.mqttTopic,
                    "mqttUser" to ConfigHandler.mqttUser,
                    "mqttPassword" to ConfigHandler.mqttPassword
                ))
            }
        }
        post("/api/device/toggle"){
            val data = call.receive<Map<String, String>>()
            if(data["isChecked"] == "true"){
                val deviceName = DeviceHandler.getAvailableDevice().find { it.address == data["address"] }?.name.orEmpty()
                DeviceHandler.addSelectedDevice(data["address"].orEmpty(), deviceName)
            }else{
                DeviceHandler.removeSelectedDevice(data["address"].orEmpty())
            }
            DeviceHandler.saveToYaml()

            call.respond(mapOf("message" to "Device toggled"))
        }
        get("/api/device/selected"){
            call.respond(DeviceHandler.selectedBTDevice.toString())
        }
        get("/api/device/available"){
            val gson = Gson()
            val jsonString = gson.toJson(DeviceHandler.getAvailableDevice())

            call.respond(jsonString)
        }
    }
}

class ConfigurationServer{
    private lateinit var server: JettyApplicationEngine

    fun start()
    {
        server = embeddedServer(Jetty, port = 8181, module = Application::module)
        server.start()
    }

    fun stop()
    {
        server.stop(0, 0)
    }
}