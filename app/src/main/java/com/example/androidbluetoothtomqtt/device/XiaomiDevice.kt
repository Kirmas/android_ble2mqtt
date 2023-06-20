package com.example.androidbluetoothtomqtt.device

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.example.androidbluetoothtomqtt.ServiceBluetoothToMQTT
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class EventTypes(val value: Int){
    Temperature(4100),
    Humidity(4102),
    Illuminance(4103),
    Moisture(4104),
    Fertility(4105),
    Battery(4106),
    TemperatureAndHumidity(4109)
}

abstract class XiaomiDevice(connectedBTDevice: BluetoothDevice, serviceContext: ServiceBluetoothToMQTT) : BaseDevice(connectedBTDevice, serviceContext) {
    override val manufacturer: String
        get() = "Xiaomi"

    private val TAG = "XiaomiDevice"
    private val baseByteLength = 5
    private val frameControlFlags = mapOf(
        "isFactoryNew" to (1 shl 0),
        "isConnected" to (1 shl 1),
        "isCentral" to (1 shl 2),
        "isEncrypted" to (1 shl 3),
        "hasMacAddress" to (1 shl 4),
        "hasCapabilities" to (1 shl 5),
        "hasEvent" to (1 shl 6),
        "hasCustomData" to (1 shl 7),
        "hasSubtitle" to (1 shl 8),
        "hasBinding" to (1 shl 9)
    )
    private val capabilityFlags = mapOf(
        "connectable" to (1 shl 0),
        "central" to (1 shl 1),
        "secure" to (1 shl 2),
        "io" to ((1 shl 3) or (1 shl 4))
    )

    final override fun newPassiveBLEData(newData: ByteArray)
    {
        val frameControl = parseFrameControl(newData)
        val capabilityOffset = if (frameControl["hasMacAddress"] == true) 11 else baseByteLength
        val eventOffset = if (frameControl["hasCapabilities"] == true) capabilityOffset + 1 else capabilityOffset
        val version = parseVersion(newData)
        val productId = parseProductId(newData)
        val frameCounter = parseFrameCounter(newData)
        val macAddress = if(frameControl["hasMacAddress"] == true) { parseMacAddress(newData) } else { "" }
        val capabilities = if(frameControl["hasCapabilities"] == true) { parseCapabilities(newData, capabilityOffset) } else { mapOf<String, Boolean>() }
        Log.d(TAG, "newPassiveBLEData: version: $version, productId: $productId, frameCounter: $frameCounter, macAddress: $macAddress, capabilities: $capabilities")
        if(frameControl["isEncrypted"] == true) {
            throw NotImplementedError("decryptPayload not implemented yet. look to https://github.com/hannseman/homebridge-mi-hygrothermograph/blob/23dc5738718db78bc40ca9682f150926cf8b1cd4/lib/parser.js#L153")
        }

        if(frameControl["hasEvent"] == true) {
            val eventType = parseEventType(newData, eventOffset)
            val eventLength = parseEventLength(newData, eventOffset)
            val eventData = parseEventData(newData, eventOffset, eventType)
            Log.d(TAG, "newPassiveBLEData: eventType: $eventType, eventLength: $eventLength eventData: $eventData")
            newSensorData(eventData)
        }
    }

    abstract fun newSensorData(data: Map<String, Any>)

    private fun parseFrameControl(buffer: ByteArray): Map<String, Boolean> {
        //val frameControl = buffer[0].toInt() and 0xFF
        val frameControlBytes = buffer.copyOfRange(0, 2)
        val frameControl = ByteBuffer.wrap(frameControlBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

        return frameControlFlags.keys.fold(mutableMapOf<String, Boolean>()) {map, flag->
            map[flag] = (frameControl and frameControlFlags[flag]!!) != 0
            map
        }
    }

    private fun parseVersion(buffer: ByteArray): Int {
        return (buffer[1].toInt() and 0xFF) shr 4
    }

    private fun parseProductId(buffer: ByteArray): Int {
        val productIdBytes = buffer.copyOfRange(2, 4)
        return ByteBuffer.wrap(productIdBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
    }

    private fun parseFrameCounter(buffer: ByteArray): Int {
        return buffer[4].toInt() and 0xFF
    }

    private fun parseMacAddress(buffer: ByteArray): String {
        val macBuffer = buffer.copyOfRange(baseByteLength, baseByteLength + 6)
        val reversedBuffer = macBuffer.reversedArray()
        return reversedBuffer.joinToString("") { "%02X".format(it) }
    }

    private fun parseCapabilities(buffer: ByteArray, capabilityOffset: Int): Map<String, Boolean> {
        val capabilities = buffer[capabilityOffset].toInt() and 0xFF
        return capabilityFlags.keys.fold(mutableMapOf<String, Boolean>()) {map, flag->
            map[flag] = (capabilities and capabilityFlags[flag]!!) != 0
            map
        }
    }

    private fun parseEventType(buffer: ByteArray, eventOffset: Int): EventTypes? {
        val productIdBytes = buffer.copyOfRange(eventOffset, eventOffset+2)
        return enumValues<EventTypes>().find { it.value == ByteBuffer.wrap(productIdBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() }
    }

    private fun parseEventLength(buffer: ByteArray, eventOffset: Int): Int {
        return buffer[eventOffset + 2].toInt() and 0xFF
    }

    private fun parseEventData(buffer: ByteArray, eventOffset: Int, enentType: EventTypes?): Map<String, Any> {
        if (enentType == null) throw Error("Unknown event type")

        return when (enentType) {
            EventTypes.Temperature -> parseTemperatureEvent(buffer, eventOffset)
            EventTypes.Humidity -> parseHumidityEvent(buffer, eventOffset)
            EventTypes.Battery -> parseBatteryEvent(buffer, eventOffset)
            EventTypes.TemperatureAndHumidity -> parseTemperatureAndHumidityEvent(buffer, eventOffset)
            EventTypes.Illuminance -> parseIlluminanceEvent(buffer, eventOffset)
            EventTypes.Fertility -> parseFertilityEvent(buffer, eventOffset)
            EventTypes.Moisture -> parseMoistureEvent(buffer, eventOffset)
        }
    }

    private fun parseTemperatureEvent(buffer: ByteArray, eventOffset: Int): Map<String, Any>  {
        val temperatureIdBytes = buffer.copyOfRange(eventOffset + 3, eventOffset + 5)
        return mapOf("temperature" to ByteBuffer.wrap(temperatureIdBytes).order(ByteOrder.LITTLE_ENDIAN).short.toFloat() / 10)
    }

    private fun parseHumidityEvent(buffer: ByteArray, eventOffset: Int): Map<String, Any> {
        val humidityIdBytes = buffer.copyOfRange(eventOffset + 3, eventOffset + 5)
        return mapOf("humidity" to ByteBuffer.wrap(humidityIdBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() / 10)
    }

    private fun parseBatteryEvent(buffer: ByteArray, eventOffset: Int): Map<String, Any> {
        return mapOf("battery" to  (buffer[eventOffset + 3].toInt() and 0xFF))
    }

    private fun parseTemperatureAndHumidityEvent(
        buffer: ByteArray,
        eventOffset: Int
    ): Map<String, Any> {
        val temperatureIdBytes = buffer.copyOfRange(eventOffset + 3, eventOffset + 5)
        val humidityIdBytes = buffer.copyOfRange(eventOffset + 5, eventOffset + 7)
        return mapOf(
            "temperature" to ByteBuffer.wrap(temperatureIdBytes).order(ByteOrder.LITTLE_ENDIAN).short.toFloat() / 10,
            "humidity" to ByteBuffer.wrap(humidityIdBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() / 10
        )
    }

    private fun parseIlluminanceEvent(buffer: ByteArray, eventOffset: Int): Map<String, Any> {
        val illuminanceIdBytes = buffer.copyOfRange(eventOffset + 3, eventOffset + 6)
        return mapOf("illuminance" to ByteBuffer.wrap(illuminanceIdBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
    }

    private fun parseFertilityEvent(buffer: ByteArray, eventOffset: Int): Map<String, Any> {
        val fertilityIdBytes = buffer.copyOfRange(eventOffset + 3, eventOffset + 5)
        return mapOf("fertility" to ByteBuffer.wrap(fertilityIdBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
    }

    private fun parseMoistureEvent(buffer: ByteArray, eventOffset: Int): Map<String, Any> {
        return mapOf("moisture" to  (buffer[eventOffset + 3].toInt() and 0xFF))
    }
}