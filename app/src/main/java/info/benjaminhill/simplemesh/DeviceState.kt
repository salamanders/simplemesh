package info.benjaminhill.simplemesh

import kotlinx.coroutines.Job

data class DeviceState(
    val endpointId: String,
    val name: String,
    val status: ConnectionState,
    @Transient
    val stateJob: Job? = null
) {
    override fun toString() = "DeviceState(endpointId='$endpointId', name='$name', status=$status)"
}