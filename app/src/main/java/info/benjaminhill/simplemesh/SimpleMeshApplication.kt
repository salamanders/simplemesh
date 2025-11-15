package info.benjaminhill.simplemesh

import android.app.Application
import info.benjaminhill.simplemesh.p2p.DeviceIdentifier
import timber.log.Timber

class SimpleMeshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceIdentifier.get(applicationContext)
        Timber.plant(Timber.DebugTree())
    }
}
