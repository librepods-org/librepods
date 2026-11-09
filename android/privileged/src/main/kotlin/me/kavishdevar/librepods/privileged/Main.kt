package me.kavishdevar.librepods.privileged

import android.annotation.SuppressLint
import android.app.ActivityThread
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.IBinder
import android.os.Looper
import android.util.Log

object Main {
    @SuppressLint("MissingPermission")
    @JvmStatic
    fun main(args: Array<String>) {
        Log.i("LibrePodsPrivileged", "Starting Privileged Daemon")

        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()

        ActivityThread.initializeMainlineModules()

        val context = ActivityThread.systemMain().systemContext as Context
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter

        check(adapter != null) { "Bluetooth adapter unavailable" }
        check(adapter.isEnabled) { "Bluetooth adapter not enabled" }

        val service = LibrePodsPrivilegedServiceImpl(context)
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val addMethod = smClass.getMethod("addService", String::class.java, IBinder::class.java)
            addMethod.invoke(null, "me.kavishdevar.librepods.privileged", service)
            Log.i("LibrePodsPrivileged", "Registered service into ServiceManager")
        } catch (e: Exception) {
            Log.w("LibrePodsPrivileged", "Failed to addService, using binder fallback", e)
        }
        Log.i("LibrePodsPrivileged", "Privileged service ready and looping")
        Looper.loop()
    }
}
