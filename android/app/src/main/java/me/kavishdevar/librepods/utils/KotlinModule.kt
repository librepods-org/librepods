package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import androidx.core.net.toUri
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

private const val TAG = "LibrePodsHook"

@SuppressLint("DiscouragedApi", "PrivateApi")
class KotlinModule: XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "module initialized at :: ${param.processName}")
        log(Log.INFO, TAG, "framework: $frameworkName($frameworkVersionCode) API $apiVersion")
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    override fun onPackageLoaded(param: PackageLoadedParam) {
        log(Log.INFO, TAG, "onPackageLoaded :: ${param.packageName}")

        if (param.packageName == "com.google.android.bluetooth" || param.packageName == "com.android.bluetooth") {
            log(Log.INFO, TAG, "Bluetooth app detected, hooking l2c_fcr_chk_chan_modes")
            try {
                if (param.isFirstPackage) {
                    val abi = android.os.Build.SUPPORTED_ABIS.first()
                    val soName = "libl2c_fcr_hook.so"

                    val candidates = buildList {
                        add("${moduleApplicationInfo.sourceDir}!/lib/$abi/$soName")

                        moduleApplicationInfo.splitSourceDirs?.forEach { split ->
                            add("$split!/lib/$abi/$soName")
                        }
                    }

                    var loaded = false

                    for (path in candidates) {
                        try {
                            log(Log.INFO, TAG, "Trying to load native lib from $path")
                            System.load(path)
                            log(Log.INFO, TAG, "Loaded native lib from $path")
                            loaded = true
                            break
                        } catch (e: Throwable) {
                            log(Log.WARN, TAG, "Failed to load from $path: ${e.message}")
                        }
                    }

                    if (!loaded) {
                        log(Log.ERROR, TAG, "Could not load $soName from base or splits")
                        return
                    }

                    val remotePrefValue = getRemotePreferences("me.kavishdevar.librepods").getBoolean("vendor_id_hook", false)
                    log(Log.INFO, TAG, "sdp hook enabled (remote pref): $remotePrefValue")
                    NativeBridge.setSdpHook(remotePrefValue)
                    log(Log.INFO, TAG, "Native library loaded successfully")
                }
            } catch (e: Exception) {
                log(Log.ERROR, TAG, "Failed to load native library: ${e.message}")
            }
        }

        if (param.packageName == "com.google.android.settings") {
            hookSettingsController(param, "com.google.android.settings.bluetooth.AdvancedBluetoothDetailsHeaderController")
        }

        if (param.packageName == "com.android.settings") {
            hookSettingsController(param, "com.android.settings.bluetooth.AdvancedBluetoothDetailsHeaderController")
        }
    }

    private fun hookSettingsController(param: PackageLoadedParam, className: String) {
        log(Log.INFO, TAG, "Settings app detected, hooking Bluetooth icon handling")
        try {
            val headerControllerClass = Class.forName(className, false, param.defaultClassLoader)
            val updateIconMethod = headerControllerClass.getDeclaredMethod(
                "updateIcon",
                ImageView::class.java,
                String::class.java
            )

            hook(updateIconMethod).intercept { chain ->
                try {
                    log(Log.INFO, TAG, "Bluetooth icon hook called with args: ${chain.args.joinToString(", ")}")
                    val imageView = chain.args[0] as? ImageView
                    val iconUri = chain.args[1] as? String

                    if (imageView == null || iconUri == null) {
                        return@intercept chain.proceed()
                    }

                    val uri = iconUri.toUri()
                    if (!uri.toString().startsWith("android.resource://me.kavishdevar.librepods")) {
                        return@intercept chain.proceed()
                    }

                    log(Log.INFO, TAG, "Handling AirPods icon URI: $uri")

                    Handler(Looper.getMainLooper()).post {
                        try {
                            val context = imageView.context
                            val packageName = uri.authority ?: return@post
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
                                    log(Log.INFO, TAG, "Successfully loaded icon from resource: $resourceName")
                                } else {
                                    log(Log.ERROR, TAG, "Resource not found: $resourceName")
                                }
                            }
                        } catch (e: Exception) {
                            log(Log.ERROR, TAG, "Error loading resource from URI $uri: ${e.message}")
                        }
                    }
                    null
                } catch (e: Exception) {
                    log(Log.ERROR, TAG, "Error in Bluetooth icon hook: ${e.message}")
                    chain.proceed()
                }
            }

            log(Log.INFO, TAG, "Successfully hooked updateIcon method in Bluetooth settings")
        } catch (e: Exception) {
            log(Log.ERROR, TAG, "Failed to hook Bluetooth icon handler: ${e.message}")
        }
    }
}


object NativeBridge {
    external fun setSdpHook(enabled: Boolean)
}
