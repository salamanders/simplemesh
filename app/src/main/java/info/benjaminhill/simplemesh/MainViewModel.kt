package info.benjaminhill.simplemesh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import info.benjaminhill.simplemesh.p2p.DeviceState
import info.benjaminhill.simplemesh.p2p.DevicesRegistry
import info.benjaminhill.simplemesh.p2p.NearbyConnectionsManager
import info.benjaminhill.simplemesh.strategy.GossipManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.seconds

// Holds the list of devices to be displayed on the screen.
class MainViewModel(
    private val nearbyConnectionsManager: NearbyConnectionsManager,
    myDeviceName: String
) : ViewModel() {

    private val gossipManager = GossipManager(viewModelScope, nearbyConnectionsManager)
    private val routingEngine = RoutingEngine(nearbyConnectionsManager, myDeviceName)

    init {
        nearbyConnectionsManager.setGossipManager(gossipManager)
        nearbyConnectionsManager.setRoutingEngine(routingEngine)
        gossipManager.start()
    }

    // A stream of device states that the UI can observe for live updates.
    // stateIn converts the cold flow from the manager into a hot StateFlow that is shared
    // among all collectors (UI components).
    // SharingStarted.WhileSubscribed(5000) keeps the upstream flow active for 5 seconds
    // after the last collector disappears. This is useful to keep data across screen rotations
    // without having to re-fetch everything.
    val devices: StateFlow<Map<String, DeviceState>> = DevicesRegistry.devices
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5.seconds.inWholeMilliseconds),
            emptyMap()
        )

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

// Creates the MainViewModel, allowing us to pass in dependencies from the Activity.
class MainViewModelFactory(
    private val nearbyConnectionsManager: NearbyConnectionsManager,
    private val myDeviceName: String
) :
    ViewModelProvider.Factory {
    // Called by the Android system to create a MainViewModel instance.
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(nearbyConnectionsManager, myDeviceName) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
