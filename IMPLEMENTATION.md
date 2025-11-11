# P2P Mesh Networking Implementation Details

This document outlines the networking features implemented in the SimpleMesh Android application.

## State Management

*   **Centralized Device Registry:** A singleton object, `DevicesRegistry`, is used to maintain the state of all discovered and connected devices.
*   **Immutable State:** The `devices` map in `DevicesRegistry` is a `StateFlow`, and the underlying `_devices` is a `MutableStateFlow`. Each update to the map creates a new, immutable map, which is a key principle of state management in modern Android development.
*   **Ephemeral and Persistent Device Information:** The registry stores devices keyed by their ephemeral `endpointId`. It also manages a separate map for exponential backoff retry counts, which is keyed by the persistent device name.
*   **Device State Representation:** The `DeviceState` data class encapsulates the state of a device, including its `endpointId`, `name`, and `phase` (connection status).
*   **Automatic Timeouts:** The `DeviceState` class includes a mechanism to automatically transition to a new phase if a device remains in a specific phase for too long. This is implemented using a `CoroutineScope` and a `delay`.
*   **Timeout-Driven State Transitions:** Each `ConnectionPhase` has a defined timeout and a corresponding next phase to transition to upon timeout. This ensures that the application does not get stuck in an inconsistent state.
*   **ViewModel Integration:** The `MainViewModel` exposes the `devices` `StateFlow` from the `DevicesRegistry` to the UI, ensuring that the UI always displays the latest device states.

## Discovery

*   **Continuous Discovery:** The `NearbyConnectionsManager` can be started to continuously discover nearby devices.
*   **P2P_CLUSTER Strategy:** The application uses the `Strategy.P2P_CLUSTER` for both advertising and discovery, which is a Google Nearby Connections API strategy suitable for creating a mesh network.
*   **Endpoint Discovery Callback:** The `EndpointDiscoveryCallback` is used to handle the discovery of new endpoints and the loss of previously discovered endpoints.
*   **Automatic Connection Requests:** Upon discovering a new endpoint, the application automatically requests a connection to it.
*   **Device Name Resolution:** The `discoveredEndpointInfo.endpointName` is used to get the persistent name of the discovered device, which is then used to update the device's state in the `DevicesRegistry`.

## Connection Management

*   **Automatic Connection Acceptance:** The `ConnectionLifecycleCallback` is configured to automatically accept all incoming connection requests.
*   **Connection Lifecycle Handling:** The `onConnectionInitiated`, `onConnectionResult`, and `onDisconnected` callbacks are implemented to manage the various stages of the connection lifecycle.
*   **Health Checks with Ping/Pong:** The application implements a simple health check mechanism using PING and PONG messages. When a connection is established, the initiator sends a PING message, and the receiver responds with a PONG message. This is used to confirm that the connection is still active.
*   **Periodic Pings:** After a successful connection, the application schedules a PING to be sent after a delay to ensure the connection remains active.
*   **Exponential Backoff for Reconnection:** When a connection is lost or results in an error, the application attempts to reconnect using an exponential backoff strategy. The retry count is tracked in the `DevicesRegistry`.
*   **Reconnection Logic:** The `reconnectWithBackoff` function in `NearbyConnectionsManager` handles the reconnection logic. It stops all existing endpoints, advertising, and discovery, and then restarts them after a calculated delay.
*   **Reconnection Cancellation:** The reconnection attempt is aborted if the application is already connected to other devices, preventing unnecessary reconnection attempts in a mesh network.
*   **Persistent Device Identifier:** The `DeviceIdentifier` object creates and stores a persistent, unique identifier for the device using `SharedPreferences`. This identifier is used as the device's advertising name to ensure stable identification across connections.
