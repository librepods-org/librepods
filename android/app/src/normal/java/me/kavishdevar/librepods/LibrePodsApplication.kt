package me.kavishdevar.librepods

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.billing.BillingProviderFactory

class LibrePodsApplication: Application(), DefaultLifecycleObserver {
    override fun onCreate() {
        BillingManager.provider = BillingProviderFactory.create(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        super<Application>.onCreate()
    }

    override fun onResume(owner: LifecycleOwner) {
        BillingManager.provider.queryPurchases()
    }
}
