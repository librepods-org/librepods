package me.kavishdevar.librepods.privileged

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlin.system.exitProcess

@SuppressLint("MissingPermission")
class LibrePodsPrivilegedServiceImpl(
    private val context: Context
) : ILibrePodsPrivilegedService.Stub() {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private var headsetProfile: BluetoothHeadset? = null

    init {
        adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    headsetProfile = proxy as BluetoothHeadset
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) {
                    headsetProfile = null
                }
            }
        }, BluetoothProfile.HEADSET)
    }

    override fun setMetadata(address: String, key: Int, value: ByteArray): Boolean {
        val bAdapter = adapter ?: return false
        return try {
            val device = bAdapter.getRemoteDevice(address)
            val method = device.javaClass.getMethod(
                "setMetadata",
                Int::class.javaPrimitiveType,
                ByteArray::class.java
            )
            val result = method.invoke(device, key, value) as? Boolean ?: false
            Log.d("LibrePodsPrivileged", "setMetadata key=$key on $address: $result")
            result
        } catch (e: Exception) {
            Log.e("LibrePodsPrivileged", "Failed to setMetadata on $address", e)
            false
        }
    }

    override fun setConnectionPolicy(address: String, profile: Int, policy: Int): Boolean {
        val bAdapter = adapter ?: return false
        return try {
            val device = bAdapter.getRemoteDevice(address)
            if (profile == BluetoothProfile.HEADSET && headsetProfile != null) {
                val method = headsetProfile!!.javaClass.getMethod(
                    "setConnectionPolicy",
                    BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType
                )
                val result = method.invoke(headsetProfile, device, policy) as? Boolean ?: false
                Log.d("LibrePodsPrivileged", "setConnectionPolicy on $address: $result")
                result
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("LibrePodsPrivileged", "Failed to setConnectionPolicy on $address", e)
            false
        }
    }

    override fun isAlive(): Boolean = true

    override fun destroy() {
        headsetProfile?.let { proxy ->
            adapter?.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
        }
        exitProcess(0)
    }
}
