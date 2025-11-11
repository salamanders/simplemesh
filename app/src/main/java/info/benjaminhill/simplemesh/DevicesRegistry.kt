package info.benjaminhill.simplemesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object DevicesRegistry {
    // Internal, mutable list of devices. Entire map gets cloned every time there is an update.
    private val _devices = MutableStateFlow<Map<String, DeviceState>>(emptyMap())

    // External, read-only list of devices for the UI.
    val devices: StateFlow<Map<String, DeviceState>> = _devices

    fun getLatestDeviceState(endpointId: String): DeviceState? = this.devices.value[endpointId]

    fun remove(endpointId: String) {
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