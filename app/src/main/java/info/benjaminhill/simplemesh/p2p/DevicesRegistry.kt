package info.benjaminhill.simplemesh.p2p

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * A centralized, singleton registry for managing the state of all discovered and connected devices.
 *
 * This object serves as the single source of truth for the state of all devices in the network.
 * It maintains a map of `endpointId` to `DeviceState`, which is exposed as a `StateFlow` to the UI.
 * It also manages a separate map for exponential backoff retry counts, keyed by the persistent device name.
 */
object DevicesRegistry {
    private val mutex = Mutex()

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
        _deviceNameStates.update { currentMap ->
            val currentState = currentMap[name] ?: DeviceNameState()
            currentMap + (name to currentState.copy(retryCount = currentState.retryCount + 1))
        }
    }

    fun resetRetryCount(name: EndpointName) {
        _deviceNameStates.update { currentMap ->
            if (currentMap.containsKey(name)) {
                currentMap + (name to currentMap[name]!!.copy(retryCount = 0))
            } else {
                currentMap
            }
        }
    }

    fun addPotentialPeer(endpointId: EndpointId) {
        _potentialPeers.update { it + endpointId }
    }

    /**
     * Updates the device status in the registry.
     * If `newPhase` is null, the device is removed.
     * Handles lifecycle management (cancelling old jobs, starting new timeouts).
     * Thread-safe via Mutex.
     */
    suspend fun updateDeviceStatus(
        endpointId: EndpointId,
        externalScope: CoroutineScope,
        newPhase: ConnectionPhase?,
        newName: EndpointName? = null,
    ) {
        mutex.withLock {
            val currentDevice = _devices.value[endpointId]

            // Prevent regression from CONNECTED to CONNECTING due to stale events or race conditions.
            if (currentDevice?.phase == ConnectionPhase.CONNECTED && newPhase == ConnectionPhase.CONNECTING) {
                Timber.tag("P2P_STATE")
                    .w("Ignoring state regression: Attempt to move from CONNECTED to CONNECTING for $endpointId. Current state is valid.")
                return
            }

            // Prevent regression from Busy (CONNECTED/CONNECTING) to DISCOVERED.
            // This stops "Discovery Loops" where an active peer is re-discovered and treated as new.
            if ((currentDevice?.phase == ConnectionPhase.CONNECTED || currentDevice?.phase == ConnectionPhase.CONNECTING) &&
                newPhase == ConnectionPhase.DISCOVERED
            ) {
                Timber.tag("P2P_STATE")
                    .w("Ignoring state regression: Attempt to move from ${currentDevice.phase} to DISCOVERED for $endpointId.")
                return
            }

            if (newPhase == null) {
                removeInternal(endpointId)
                return
            }

            // Optimization: If state is effectively unchanged, just return.
            // Note: We allow re-entry for CONNECTED to refresh the timeout.
            if (currentDevice?.phase == newPhase && newPhase != ConnectionPhase.CONNECTED) {
                Timber.tag("P2P_STATE")
                    .v("Ignoring redundant state update: $endpointId is already $newPhase")
                return
            }

            if (currentDevice != null && currentDevice.phase != newPhase) {
                Timber.tag("P2P_STATE")
                    .i("State Transition: ${currentDevice.name} ($endpointId) ${currentDevice.phase} -> $newPhase")
            }

            // Cancel any pending follow-up action (timeout) for the existing device state
            currentDevice?.followUpAction?.cancel()

            val name = newName ?: currentDevice?.name ?: EndpointName("Unknown")
            val newState = DeviceState(endpointId, name, newPhase).apply {
                startAutoTimeout(externalScope)
            }
            _devices.update { it + (endpointId to newState) }
        }
    }

    /**
     * Removes a device from all registries (devices, potential peers, and ephemeral state).
     * Logs a warning if the device was not found in the active devices map.
     * Thread-safe via Mutex.
     */
    suspend fun remove(endpointId: EndpointId) {
        mutex.withLock {
            removeInternal(endpointId)
        }
    }

    private fun removeInternal(endpointId: EndpointId) {
        val device = _devices.value[endpointId]
        device?.followUpAction?.cancel()

        if (device == null) {
            Timber.tag("P2P_REGISTRY").w("Attempted to remove unknown device: $endpointId")
        } else {
            // We don't remove the persistent name state (retries), only the ephemeral connection state
            // This allows us to remember backoff counts even if a device disconnects temporarily.
            Timber.tag("P2P_REGISTRY").d("Removing device: ${device.name} ($endpointId)")
        }
        _devices.update { it - endpointId }
        _potentialPeers.update { it - endpointId }
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