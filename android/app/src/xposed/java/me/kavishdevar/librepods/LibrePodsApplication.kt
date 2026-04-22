package me.kavishdevar.librepods

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import me.kavishdevar.librepods.utils.XposedServiceHolder

class LibrePodsApplication: Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(p0: XposedService) {
        XposedServiceHolder.service = p0
    }

    override fun onServiceDied(p0: XposedService) {
        XposedServiceHolder.service = null
    }
}
