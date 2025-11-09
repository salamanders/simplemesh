package info.benjaminhill.simplemesh

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val nearbyConnectionsManager = NearbyConnectionsManager(application)

    val devices: StateFlow<Map<String, DeviceState>> = nearbyConnectionsManager.devices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun startAdvertising() {
        nearbyConnectionsManager.startAdvertising()
    }

    fun startDiscovery() {
        nearbyConnectionsManager.startDiscovery()
    }

    fun stopAll() {
        nearbyConnectionsManager.stopAll()
    }
}
