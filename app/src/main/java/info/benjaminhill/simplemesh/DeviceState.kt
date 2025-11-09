package info.benjaminhill.simplemesh

data class DeviceState(
    val endpointId: String,
    val name: String,
    val status: ConnectionStatus
)