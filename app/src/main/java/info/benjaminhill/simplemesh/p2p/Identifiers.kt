package info.benjaminhill.simplemesh.p2p

import kotlinx.serialization.Serializable


@Serializable
@JvmInline
value class EndpointId(val value: String)

@Serializable
@JvmInline
value class EndpointName(val value: String)
