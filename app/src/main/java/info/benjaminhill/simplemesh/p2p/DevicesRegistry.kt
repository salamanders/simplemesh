package info.benjaminhill.simplemesh.p2p

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * A centralized, singleton registry for managing the state of all discovered and connected devices.
 *
 * This object serves as the single source of truth for the state of all devices in the network.
 * It maintains a map of `endpointId` to `DeviceState`, which is exposed as a `StateFlow` to the UI.
 * It also manages a separate map for exponential backoff retry counts, keyed by the persistent device name.
 */
object DevicesRegistry {
    // Discovered peers that are qualified connection candidates.
    private val _potentialPeers = MutableStateFlow<Set<EndpointId>>(emptySet())
    val potentialPeers = _potentialPeers.asStateFlow()

    // Devices keyed by ephemeral endpointId.
    private val _devices = MutableStateFlow<Map<EndpointId, DeviceState>>(emptyMap())
    val devices = _devices.asStateFlow()

    // A map of persistent device name to its state (retries, neighbors).
    private val _deviceNameStates = MutableStateFlow<Map<EndpointName, DeviceNameState>>(emptyMap())

    fun getLatestDeviceState(endpointId: EndpointId): DeviceState? = _devices.value[endpointId]

    fun getRetryCount(name: EndpointName): Int = _deviceNameStates.value[name]?.retryCount ?: 0

    fun incrementRetryCount(name: EndpointName) {
        _deviceNameStates.value += name to (_deviceNameStates.value[name]
            ?: DeviceNameState()).let {
            it.copy(retryCount = it.retryCount + 1)
        }
    }

    fun resetRetryCount(name: EndpointName) {
        if (_deviceNameStates.value.containsKey(name)) {
            _deviceNameStates.value += name to (_deviceNameStates.value[name]!!.copy(retryCount = 0))
        }
    }

    fun addPotentialPeer(endpointId: EndpointId) {
        _potentialPeers.value += endpointId
    }

    /**
     * Updates the device status in the registry.
     * If `newPhase` is null, the device is removed.
     * Handles lifecycle management (cancelling old jobs, starting new timeouts).
     */
    fun updateDeviceStatus(
        endpointId: EndpointId,
        externalScope: CoroutineScope,
        newPhase: ConnectionPhase?,
        newName: EndpointName? = null,
    ) {
        val currentDevice = _devices.value[endpointId]
        // Cancel any pending follow-up action (timeout) for the existing device state
        currentDevice?.followUpAction?.cancel()

        if (newPhase == null) {
            remove(endpointId)
            return
        }

        val name = newName ?: currentDevice?.name ?: EndpointName("Unknown")
        val newState = DeviceState(endpointId, name, newPhase).apply {
            startAutoTimeout(externalScope)
        }
        _devices.value += (endpointId to newState)
    }

    /**
     * Removes a device from all registries (devices, potential peers, and ephemeral state).
     * Logs a warning if the device was not found in the active devices map.
     */
    fun remove(endpointId: EndpointId) {
        val device = _devices.value[endpointId]
        if (device == null) {
            Timber.tag("P2P_REGISTRY").w("Attempted to remove unknown device: $endpointId")
        } else {
            // We don't remove the persistent name state (retries), only the ephemeral connection state
            // This allows us to remember backoff counts even if a device disconnects temporarily.
            Timber.tag("P2P_REGISTRY").d("Removing device: ${device.name} ($endpointId)")
        }
        _devices.value -= endpointId
        _potentialPeers.value -= endpointId
    }

    // --- Graph Topology Management ---

    /**
     * A new data class to hold state keyed by the persistent device name.
     */
    data class DeviceNameState(
        val retryCount: Int = 0,
        val neighbors: Set<EndpointName> = emptySet(),
    )
}