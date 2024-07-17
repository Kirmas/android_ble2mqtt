package com.example.androidbluetoothtomqtt.yaml

import android.util.Log
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

abstract class YamlHandler(private val fileName: String) {

    private val TAG = "YamlHandler"
    private lateinit var filesDir: File
    private var bConfigLoaded = false
    private var bWasInitialized = false
    private val observers = mutableListOf<() -> Unit>()

    fun init(filesDir: File) {
        this.filesDir = filesDir
        bWasInitialized = true
    }

    fun bind(observer: () -> Unit) {
        observers.add(observer)
    }

    fun unbind(observer: () -> Unit) {
        observers.remove(observer)
    }

    protected fun notifyObservers() {
        observers.forEach { it() }
    }

    fun isConfigLoaded(): Boolean {
        return bConfigLoaded
    }

    fun loadFromYaml() {
        if(!bWasInitialized)
        {
            Log.e(TAG, "Init was not called")
            return
        }
        val configFile = File(filesDir, fileName)
        if (configFile.exists()) {
            val yaml = Yaml()
            val configData = configFile.readText()
            val configMap = yaml.load<Map<String, String>>(configData)
            setDataTo(configMap)
            bConfigLoaded = true
            Log.i(TAG, "$fileName loaded")
        } else {
            Log.i(TAG, "$fileName is missed")
        }
    }

    abstract fun setDataTo(newData: Map<String, String>)

    fun saveToYaml() {
        if(!bWasInitialized)
        {
            Log.e(TAG, "Init was not called")
            return
        }
        val configData = getDataFrom()
        val dumperOptions = DumperOptions()
        dumperOptions.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        dumperOptions.isPrettyFlow = true
        val yaml = Yaml(dumperOptions)
        val configFile = File(filesDir, fileName)
        configFile.writeText(yaml.dump(configData))
        bConfigLoaded = true
        notifyObservers()
        Log.i(TAG, "Config saved")
    }

    abstract fun getDataFrom(): Map<String, String>
}