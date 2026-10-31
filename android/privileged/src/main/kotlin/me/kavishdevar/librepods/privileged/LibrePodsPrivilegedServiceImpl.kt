package me.kavishdevar.librepods.privileged

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
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
    private var a2dpProfile: BluetoothA2dp? = null

    init {
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                when (profile) {
                    BluetoothProfile.HEADSET -> headsetProfile = proxy as? BluetoothHeadset
                    BluetoothProfile.A2DP -> a2dpProfile = proxy as? BluetoothA2dp
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                when (profile) {
                    BluetoothProfile.HEADSET -> headsetProfile = null
                    BluetoothProfile.A2DP -> a2dpProfile = null
                }
            }
        }
        adapter?.getProfileProxy(context, listener, BluetoothProfile.HEADSET)
        adapter?.getProfileProxy(context, listener, BluetoothProfile.A2DP)
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
            val proxy: Any? = when (profile) {
                BluetoothProfile.HEADSET -> headsetProfile
                BluetoothProfile.A2DP -> a2dpProfile
                else -> null
            }
            if (proxy != null) {
                val method = proxy.javaClass.getMethod(
                    "setConnectionPolicy",
                    BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType
                )
                val result = method.invoke(proxy, device, policy) as? Boolean ?: false
                Log.d("LibrePodsPrivileged", "setConnectionPolicy profile=$profile on $address: $result")
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
        headsetProfile?.let { proxy -> adapter?.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
        a2dpProfile?.let { proxy -> adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
        exitProcess(0)
    }
}
