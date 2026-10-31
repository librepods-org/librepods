package me.kavishdevar.librepods.privileged

import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

object PrivilegedClient {
    private const val TAG = "PrivilegedClient"
    private val serviceRef = AtomicReference<ILibrePodsPrivilegedService?>(null)

    val isConnected: Boolean
        get() = serviceRef.get()?.isAlive ?: false

    fun getService(): ILibrePodsPrivilegedService? {
        serviceRef.get()?.let { return it }

        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getMethod = smClass.getMethod("getService", String::class.java)
            val binder = getMethod.invoke(null, "me.kavishdevar.librepods.privileged") as? IBinder
            if (binder != null) {
                val stub = ILibrePodsPrivilegedService.Stub.asInterface(binder)
                serviceRef.set(stub)
                Log.i(TAG, "Connected to privileged service via ServiceManager")
                return stub
            }
        } catch (e: Exception) {
            Log.d(TAG, "Standalone service not reachable", e)
        }
        return null
    }

    fun setMetadata(address: String, key: Int, value: ByteArray?): Boolean {
        val s = getService() ?: return false
        return try {
            s.setMetadata(address, key, value ?: ByteArray(0))
        } catch (e: Exception) {
            Log.e(TAG, "Error invoking setMetadata", e)
            false
        }
    }

    fun setConnectionPolicy(address: String, profile: Int, policy: Int): Boolean {
        val s = getService() ?: return false
        return try {
            s.setConnectionPolicy(address, profile, policy)
        } catch (e: Exception) {
            Log.e(TAG, "Error invoking setConnectionPolicy", e)
            false
        }
    }
}
