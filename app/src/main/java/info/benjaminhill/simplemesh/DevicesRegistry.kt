package info.benjaminhill.simplemesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object DevicesRegistry {
    // For connection slot management.
    private val _potentialPeers = MutableStateFlow<Set<String>>(emptySet())
    val potentialPeers: StateFlow<Set<String>> = _potentialPeers

    // Internal, mutable list of devices, keyed by ephemeral endpointId.
    // Entire map gets cloned every time there is an update.
    private val _devices = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    // External, read-only list of devices for the UI.
    val devices: StateFlow<Map<String, DeviceState>> = _devices

    // For exponential backoff. Keyed by persistent device name.
    private val _retryCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    // For topology management. A map of device name to its set of neighbors' names.
    private val _networkGraph = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val networkGraph: StateFlow<Map<String, Set<String>>> = _networkGraph

    fun getLatestDeviceState(endpointId: String): DeviceState? = this.devices.value[endpointId]

    fun getRetryCount(name: String): Int = _retryCounts.value[name] ?: 0

    fun incrementRetryCount(name: String) {
        _retryCounts.value += name to (getRetryCount(name) + 1)
    }

    fun resetRetryCount(name: String) {
        _retryCounts.value -= name
    }

    fun addPotentialPeer(endpointId: String) {
        _potentialPeers.value += endpointId
    }

    fun updateNetworkGraph(newGraph: Map<String, Set<String>>) {
        _networkGraph.value = newGraph
    }

    fun updateLocalDeviceInGraph(localDeviceName: String, neighbors: Set<String>) {
        val currentGraph = _networkGraph.value.toMutableMap()
        currentGraph[localDeviceName] = neighbors
        _networkGraph.value = currentGraph
    }

    fun remove(endpointId: String) {
        val device = getLatestDeviceState(endpointId)
        if (device != null) {
            _retryCounts.value -= device.name
        }
        _devices.value -= endpointId
        _potentialPeers.value -= endpointId
    }

    fun updateDeviceStatus(
        endpointId: String,
        externalScope: CoroutineScope,
        newPhase: ConnectionPhase?,
        newName: String? = null,
    ) {
        val existingDevice = _devices.value[endpointId]
        val name = newName ?: existingDevice?.name ?: "Unknown"

        // Cancel any existing job
        existingDevice?.followUpAction?.cancel()

        if (newPhase != null) {
            _devices.value += endpointId to DeviceState(
                endpointId = endpointId,
                name = name,
                phase = newPhase,
            ).apply {
                startAutoTimeout(externalScope)
            }
        } else {
            remove(endpointId)
        }
    }
}