package com.example.androidbluetoothtomqtt.yaml;
import android.os.Parcel
import android.os.Parcelable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

object DeviceHandler : YamlHandler("device.yaml") {
    private val _selectedBTDevice = MutableStateFlow<Map<String, String>>(mapOf())
    val selectedBTDevice: StateFlow<Map<String, String>> = _selectedBTDevice

    private val _availableBTDevice = MutableStateFlow<ArrayList<BTDeviceInformation>>(arrayListOf())
    val availableBTDevice: StateFlow<List<BTDeviceInformation>> = _availableBTDevice

    fun addSelectedDevice(address: String, name: String) {
        // Create a new map with the current items plus the new device
        val updatedMap = HashMap(_selectedBTDevice.value).apply {
            put(address, name)
        }
        // Update the StateFlow with the new map, triggering state emission
        _selectedBTDevice.value = updatedMap
    }

    fun removeSelectedDevice(address: String) {
        // Create a new map with the current items minus the device with the given address
        val updatedMap = HashMap(_selectedBTDevice.value).apply {
            remove(address)
        }
        // Update the StateFlow with the new map, triggering state emission
        _selectedBTDevice.value = updatedMap
    }

    fun getSelectedDevice(): Map<String, String> {
        return _selectedBTDevice.value
    }

    fun addAvailableDevice(device: BTDeviceInformation) {
        // Create a new list with the current items plus the new device
        val updatedList = ArrayList(_availableBTDevice.value).apply {
            add(device)
        }
        // Update the StateFlow with the new list, triggering state emission
        _availableBTDevice.value = updatedList
    }

    fun getAvailableDevice(): List<BTDeviceInformation> {
        return _availableBTDevice.value
    }

    override fun setDataTo(newData: Map<String, String>) {
        _selectedBTDevice.value = newData
    }

    override fun getDataFrom(): Map<String, String> {
        return _selectedBTDevice.value
    }
}
