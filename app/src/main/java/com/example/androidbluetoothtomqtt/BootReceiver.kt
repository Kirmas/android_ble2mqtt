package com.example.androidbluetoothtomqtt

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
       if (intent.action == Intent.ACTION_BOOT_COMPLETED && !isServiceRunning(context)) {
           val serviceIntent = Intent(context, ServiceBluetoothToMQTT::class.java)
           context.startForegroundService(serviceIntent)
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val services = manager.getRunningServices(Integer.MAX_VALUE)
        return services.any { it.service.className == ServiceBluetoothToMQTT::class.java.name }
    }
}