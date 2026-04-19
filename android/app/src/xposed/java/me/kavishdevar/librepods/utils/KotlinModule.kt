package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.widget.ImageView
import androidx.core.net.toUri
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.XposedHooker
import kotlin.jvm.java

private const val TAG = "AirPodsHook"
private lateinit var module: KotlinModule
@SuppressLint("DiscouragedApi", "PrivateApi")
class KotlinModule(base: XposedInterface, param: ModuleLoadedParam): XposedModule(base, param) {
    init {
        Log.i(TAG, "AirPodsHook module initialized at :: ${param.processName}")
        module = this
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        Log.i(TAG, "onPackageLoaded :: ${param.packageName}")

        if (param.packageName == "com.google.android.bluetooth" || param.packageName == "com.android.bluetooth") {
            Log.i(TAG, "Bluetooth app detected, hooking l2c_fcr_chk_chan_modes")

            try {
                if (param.isFirstPackage) {
                    Log.i(TAG, "Loading native library for Bluetooth hook")
                    System.loadLibrary("l2c_fcr_hook")
                    Log.i(TAG, "Native library loaded successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library: ${e.message}", e)
            }
        }

        if (param.packageName == "com.google.android.settings") {
            Log.i(TAG, "Settings app detected, hooking Bluetooth icon handling")
            try {
                val headerControllerClass = param.classLoader.loadClass(
                    "com.google.android.settings.bluetooth.AdvancedBluetoothDetailsHeaderController")

                val updateIconMethod = headerControllerClass.getDeclaredMethod(
                    "updateIcon",
                    ImageView::class.java,
                    String::class.java)

                hook(updateIconMethod, BluetoothIconHooker::class.java)
                Log.i(TAG, "Successfully hooked updateIcon method in Bluetooth settings")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hook Bluetooth icon handler: ${e.message}", e)
            }
        }

        if (param.packageName == "com.android.settings") {
            Log.i(TAG, "Settings app detected, hooking Bluetooth icon handling")
            try {
                val headerControllerClass = param.classLoader.loadClass(
                    "com.android.settings.bluetooth.AdvancedBluetoothDetailsHeaderController")

                val updateIconMethod = headerControllerClass.getDeclaredMethod(
                    "updateIcon",
                    ImageView::class.java,
                    String::class.java)

                hook(updateIconMethod, BluetoothIconHooker::class.java)
                Log.i(TAG, "Successfully hooked updateIcon method in Bluetooth settings")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hook Bluetooth icon handler: ${e.message}", e)
            }
        }
    }

    @XposedHooker
    class BluetoothIconHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @AfterInvocation
            fun afterUpdateIcon(callback: AfterHookCallback) {
                Log.i(TAG, "BluetoothIconHooker called with args: ${callback.args.joinToString(", ")}")
                try {
                    val imageView = callback.args[0] as ImageView
                    val iconUri = callback.args[1] as String

                    val uri = iconUri.toUri()
                    if (uri.toString().startsWith("android.resource://me.kavishdevar.librepods")) {
                        Log.i(TAG, "Handling AirPods icon URI: $uri")

                        try {
                            val context = imageView.context

                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try {
                                    val packageName = uri.authority
                                    val packageContext = context.createPackageContext(
                                        packageName,
                                        Context.CONTEXT_IGNORE_SECURITY
                                    )

                                    val resPath = uri.pathSegments
                                    if (resPath.size >= 2 && resPath[0] == "drawable") {
                                        val resourceName = resPath[1]
                                        val resourceId = packageContext.resources.getIdentifier(
                                            resourceName, "drawable", packageName
                                        )

                                        if (resourceId != 0) {
                                            val drawable = packageContext.resources.getDrawable(
                                                resourceId, packageContext.theme
                                            )

                                            imageView.setImageDrawable(drawable)
                                            imageView.alpha = 1.0f

                                            callback.result = null

                                            Log.i(TAG, "Successfully loaded icon from resource: $resourceName")
                                        } else {
                                            Log.e(TAG, "Resource not found: $resourceName")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error loading resource from URI $uri: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error accessing context: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in BluetoothIconHooker: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    override fun getApplicationInfo(): ApplicationInfo {
        return super.applicationInfo
    }
}
