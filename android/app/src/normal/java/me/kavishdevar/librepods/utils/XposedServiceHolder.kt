package me.kavishdevar.librepods.utils

import android.content.Context
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

object XposedServiceHolder {
    var service: XposedService? = null
}


object XposedInitializer: XposedServiceHelper.OnServiceListener {
    private var initialized = false

    fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        XposedServiceHolder.service = service
    }

    override fun onServiceDied(service: XposedService) {
        XposedServiceHolder.service = null
    }
}
