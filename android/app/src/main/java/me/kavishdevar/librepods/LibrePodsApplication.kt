package me.kavishdevar.librepods

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.kavishdevar.librepods.data.workout.RoomWorkoutLocalStore
import me.kavishdevar.librepods.data.workout.WorkoutDatabase
import me.kavishdevar.librepods.data.workout.WorkoutPreferences
import me.kavishdevar.librepods.data.workout.WorkoutRepository
import me.kavishdevar.librepods.health.workout.AndroidWorkoutHealthConnectExporter
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.billing.BillingProviderFactory
import me.kavishdevar.librepods.utils.XposedServiceHolder
import me.kavishdevar.librepods.utils.XposedState

class LibrePodsApplication: Application(), XposedServiceHelper.OnServiceListener, DefaultLifecycleObserver {

    private val workoutScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val workoutPreferences: WorkoutPreferences by lazy {
        WorkoutPreferences(getSharedPreferences("settings", MODE_PRIVATE))
    }

    val workoutRepository: WorkoutRepository by lazy {
        val database = Room.databaseBuilder(
            applicationContext,
            WorkoutDatabase::class.java,
            "workouts.db",
        ).build()
        WorkoutRepository(
            localStore = RoomWorkoutLocalStore(database),
            healthConnectExporter = AndroidWorkoutHealthConnectExporter(this),
            maxHeartRateProvider = { workoutPreferences.maxHeartRateBpm },
            scope = workoutScope,
        )
    }

    override fun onCreate() {
        XposedServiceHelper.registerListener(this)
        BillingManager.provider = BillingProviderFactory.create(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        super<Application>.onCreate()
        workoutRepository.retryPendingHealthConnectExports()

    }

    override fun onResume(owner: LifecycleOwner) {
        BillingManager.provider.queryPurchases()
        XposedState.isAvailable = XposedServiceHolder.service != null
        XposedState.bluetoothScopeEnabled = XposedServiceHolder.service?.scope?.contains("com.google.android.bluetooth") == true || XposedServiceHolder.service?.scope?.contains("com.android.bluetooth") == true
    }

    override fun onServiceBind(service: XposedService) {
        XposedServiceHolder.service = service
        XposedState.isAvailable = true
        XposedState.bluetoothScopeEnabled = XposedServiceHolder.service?.scope?.contains("com.google.android.bluetooth") == true || XposedServiceHolder.service?.scope?.contains("com.android.bluetooth") == true
    }

    override fun onServiceDied(p0: XposedService) {
        XposedServiceHolder.service = null
        XposedState.isAvailable = false
    }
}
