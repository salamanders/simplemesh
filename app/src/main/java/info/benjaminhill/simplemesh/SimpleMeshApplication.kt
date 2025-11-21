package info.benjaminhill.simplemesh

import android.app.Application
import info.benjaminhill.simplemesh.p2p.NearbyConnectionsManager
import info.benjaminhill.simplemesh.util.DeviceIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

class SimpleMeshApplication : Application() {
    // Scope that survives the entire application lifecycle.
    // SupervisorJob ensures that a failure in one child doesn't cancel the others.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var nearbyConnectionsManager: NearbyConnectionsManager
        private set

    override fun onCreate() {
        super.onCreate()
        DeviceIdentifier.get(applicationContext)
        Timber.plant(Timber.DebugTree())

        nearbyConnectionsManager = NearbyConnectionsManager(this, applicationScope)
    }
}
