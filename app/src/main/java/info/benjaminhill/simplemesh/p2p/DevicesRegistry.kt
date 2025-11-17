package info.benjaminhill.simplemesh.p2p

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A centralized, singleton registry for managing the state of all discovered and connected devices.
 *
 * This object serves as the single source of truth for the state of all devices in the network.
 * It maintains a map of `endpointId` to `DeviceState`, which is exposed as a `StateFlow` to the UI.
 * It also manages a separate map for exponential backoff retry counts, which is keyed by the
 * persistent device name.
 */
object DevicesRegistry {
    // Discovered peers that are qualified connection candidates.
    private val _potentialPeers = MutableStateFlow<Set<EndpointId>>(emptySet())
    val potentialPeers get() = _potentialPeers.asStateFlow()

    // Devices keyed by ephemeral endpointId.
    private val _devices = MutableStateFlow<Map<EndpointId, DeviceState>>(emptyMap())
    val devices get() = _devices.asStateFlow()

    // A map of device name to its state.
    private val _deviceNameStates = MutableStateFlow<Map<EndpointName, DeviceNameState>>(emptyMap())

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    // For topology management. A map of device name to its set of neighbors' names.
    val networkGraph: StateFlow<Map<EndpointName, Set<EndpointName>>> = _deviceNameStates.map {
        it.mapValues { (_, state) -> state.neighbors }
    }.stateIn(
        scope = coroutineScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyMap()
    )

    fun getLatestDeviceState(endpointId: EndpointId): DeviceState? = this.devices.value[endpointId]

    fun getRetryCount(name: EndpointName): Int = _deviceNameStates.value[name]?.retryCount ?: 0

    fun incrementRetryCount(name: EndpointName) {
        val currentState = _deviceNameStates.value[name] ?: DeviceNameState()
        _deviceNameStates.value += name to currentState.copy(retryCount = currentState.retryCount + 1)
    }

    fun resetRetryCount(name: EndpointName) {
        val currentState = _deviceNameStates.value[name]
        if (currentState != null) {
            _deviceNameStates.value += name to currentState.copy(retryCount = 0)
        }
    }

    fun addPotentialPeer(endpointId: EndpointId) {
        _potentialPeers.value += endpointId
    }

    // TODO: Is the Set<String> need adjusting?
    fun updateNetworkGraph(newGraph: Map<EndpointName, Set<EndpointName>>) {
        val currentStates = _deviceNameStates.value.toMutableMap()
        newGraph.forEach { (name, neighbors) ->
            val currentState = currentStates[name] ?: DeviceNameState()
            currentStates[name] = currentState.copy(neighbors = neighbors)
        }
        _deviceNameStates.value = currentStates
    }

    fun updateLocalDeviceInGraph(localDeviceName: EndpointName, neighbors: Set<EndpointName>) {
        val currentState = _deviceNameStates.value[localDeviceName] ?: DeviceNameState()
        _deviceNameStates.value += localDeviceName to currentState.copy(neighbors = neighbors)
    }

    fun removeDeviceFromGraph(deviceName: EndpointName) {
        val currentStates = _deviceNameStates.value.toMutableMap()
        currentStates.remove(deviceName)
        currentStates.forEach { (name, state) ->
            if (state.neighbors.contains(deviceName)) {
                currentStates[name] = state.copy(neighbors = state.neighbors - deviceName)
            }
        }
        _deviceNameStates.value = currentStates
    }

    fun remove(endpointId: EndpointId) {
        val device = getLatestDeviceState(endpointId)
        if (device != null) {
            _deviceNameStates.value -= device.name
        }
        _devices.value -= endpointId
        _potentialPeers.value -= endpointId
    }

    fun updateDeviceStatus(
        endpointId: EndpointId,
        externalScope: CoroutineScope,
        newPhase: ConnectionPhase?,
        newName: EndpointName? = null,
    ) {
        // Cancel any pending follow-up action for the existing device
        _devices.value[endpointId]?.followUpAction?.cancel()

        val name: EndpointName =
            newName ?: _devices.value[endpointId]?.name ?: EndpointName("Unknown")

        if (newPhase != null) {
            _devices.value += (endpointId to DeviceState(
                endpointId = endpointId,
                name = name,
                phase = newPhase,
            ).also { it.startAutoTimeout(externalScope) })
        } else {
            remove(endpointId)
        }
    }

    /**
     * A new data class to hold state keyed by the persistent device name.
     */
    data class DeviceNameState(
        val retryCount: Int = 0,
        val neighbors: Set<EndpointName> = emptySet(),
    )
}