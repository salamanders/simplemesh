package info.benjaminhill.simplemesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A new data class to hold state keyed by the persistent device name.
 */
data class DeviceNameState(
    val retryCount: Int = 0,
    val neighbors: Set<String> = emptySet(),
)

object DevicesRegistry {
    // For connection slot management.
    private val _potentialPeers = MutableStateFlow<Set<String>>(emptySet())
    val potentialPeers: StateFlow<Set<String>> = _potentialPeers

    // Internal, mutable list of devices, keyed by ephemeral endpointId.
    // Entire map gets cloned every time there is an update.
    private val _devices = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    // External, read-only list of devices for the UI.
    val devices: StateFlow<Map<String, DeviceState>> = _devices

    // A map of device name to its state.
    private val _deviceNameStates = MutableStateFlow<Map<String, DeviceNameState>>(emptyMap())

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    // For topology management. A map of device name to its set of neighbors' names.
    val networkGraph: StateFlow<Map<String, Set<String>>> = _deviceNameStates.map {
        it.mapValues { (_, state) -> state.neighbors }
    }.stateIn(
        scope = coroutineScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyMap()
    )

    fun getLatestDeviceState(endpointId: String): DeviceState? = this.devices.value[endpointId]

    fun getRetryCount(name: String): Int = _deviceNameStates.value[name]?.retryCount ?: 0

    fun incrementRetryCount(name: String) {
        val currentState = _deviceNameStates.value[name] ?: DeviceNameState()
        _deviceNameStates.value += name to currentState.copy(retryCount = currentState.retryCount + 1)
    }

    fun resetRetryCount(name: String) {
        val currentState = _deviceNameStates.value[name]
        if (currentState != null) {
            _deviceNameStates.value += name to currentState.copy(retryCount = 0)
        }
    }

    fun addPotentialPeer(endpointId: String) {
        _potentialPeers.value += endpointId
    }

    fun updateNetworkGraph(newGraph: Map<String, Set<String>>) {
        val currentStates = _deviceNameStates.value.toMutableMap()
        newGraph.forEach { (name, neighbors) ->
            val currentState = currentStates[name] ?: DeviceNameState()
            currentStates[name] = currentState.copy(neighbors = neighbors)
        }
        _deviceNameStates.value = currentStates
    }

    fun updateLocalDeviceInGraph(localDeviceName: String, neighbors: Set<String>) {
        val currentState = _deviceNameStates.value[localDeviceName] ?: DeviceNameState()
        _deviceNameStates.value += localDeviceName to currentState.copy(neighbors = neighbors)
    }

    fun remove(endpointId: String) {
        val device = getLatestDeviceState(endpointId)
        if (device != null) {
            _deviceNameStates.value -= device.name
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