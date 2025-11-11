package info.benjaminhill.simplemesh

import android.app.Application
import timber.log.Timber

class SimpleMeshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceIdentifier.get(applicationContext)
        Timber.plant(Timber.DebugTree())
    }
}
