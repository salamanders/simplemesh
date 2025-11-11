package info.benjaminhill.simplemesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object DevicesRegistry {
    // Internal, mutable list of devices, keyed by ephemeral endpointId.
    // Entire map gets cloned every time there is an update.
    private val _devices = MutableStateFlow<Map<String, DeviceState>>(emptyMap())

    // For exponential backoff. Keyed by persistent device name.
    private val _retryCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    // External, read-only list of devices for the UI.
    val devices: StateFlow<Map<String, DeviceState>> = _devices

    fun getLatestDeviceState(endpointId: String): DeviceState? = this.devices.value[endpointId]

    fun getRetryCount(name: String): Int = _retryCounts.value[name] ?: 0

    fun incrementRetryCount(name: String) {
        _retryCounts.value += name to (getRetryCount(name) + 1)
    }

    fun resetRetryCount(name: String) {
        _retryCounts.value -= name
    }

    fun remove(endpointId: String) {
        val device = getLatestDeviceState(endpointId)
        if (device != null) {
            _retryCounts.value -= device.name
        }
        _devices.value -= endpointId
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