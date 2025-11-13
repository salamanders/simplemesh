# Jules' Mesh Network Verification Report

This document outlines the verification of the Android application against the "minimal reliable solution" for a network of 30 Pixel 4 devices.

## Phase 1: Architectural Topology Verification

### Verify "Sparse Graph" Topology Logic

*   **Constraint:** Pixel 4 supports max ~4 active Bluetooth connections.
*   **Check:** Does the code limit `acceptedConnections` + `outgoingConnections` to <= 4 per device?
    *   **Result:** PASSED. The `NearbyConnectionsManager.kt` contains a `MAX_CONNECTIONS` constant set to 4. The `manageConnections` function uses this constant to limit the number of outgoing connection attempts.
*   **Check:** Does the app reject new incoming connections (or disconnect the oldest/weaker) when the slot limit is reached?
    *   **Result:** FAILED. The `onConnectionInitiated` callback in `NearbyConnectionsManager.kt` accepts all incoming connections automatically, without checking the current number of active connections. This could lead to exceeding the `MAX_CONNECTIONS` limit.

### Verify Application-Layer Routing (The "Overlay")

*   **Fact:** `P2P_CLUSTER` does not forward packets.
*   **Check:** Does the codebase contain a `RouteManager` or `PacketForwarder` class?
    *   **Result:** PASSED. The file `RoutingEngine.kt` is responsible for handling and forwarding payloads.
*   **Check:** Are payloads wrapped in a custom header containing `sourceId`, `destinationId`, `hopCount` (TTL), and `messageId` (for de-duplication)?
    *   **Result:** PASSED. The `MeshPayload` data class in `RoutingEngine.kt` includes `messageId`, `sourceEndpointId`, `destEndpointId`, and `ttl`.
*   **Check:** Is there a flooding or routing mechanism (e.g., "If I am not the destination, and TTL > 0, re-broadcast to all *other* connected endpoints")?
    *   **Result:** PASSED. The `RoutingEngine.handlePayload` function checks the `ttl`, decrements it, and re-broadcasts the payload to all connected peers, which is a form of flooding.

### Verify "Island" Prevention (Partition Recovery)

*   **Check:** Does the app implement a "Heartbeat" or "Keep-Alive" payload sent every ~5-10 seconds?
    *   **Result:** PASSED. `NearbyConnectionsManager.kt` implements a PING/PONG mechanism. The `connectedSendPing` function sends a PING every 15 seconds.
*   **Check:** Is there logic to detect "Isolation" (0 connections) and restart `startDiscovery()` immediately?
    *   **Result:** PASSED. The `reconnectWithBackoff` function in `NearbyConnectionsManager.kt` is triggered on disconnection. It checks if the device is isolated (0 connections) and, if so, restarts advertising and discovery.
*   **Check:** (Advanced) Does the app implement "Connection Rotation"?
    *   **Result:** FAILED. The app does not implement connection rotation. The `manageConnections` function prioritizes connecting to new peers but does not disconnect from existing ones to prevent network partitions.

## Phase 2: Implementation & API Usage

### Verify Strategy Configuration

*   **Check:** Ensure `startAdvertising` and `startDiscovery` *both* use `Strategy.P2P_CLUSTER`.
    *   **Result:** PASSED. In `NearbyConnectionsManager.kt`, both `startAdvertising` and `startDiscovery` are explicitly configured with `Strategy.P2P_CLUSTER`.
*   **Check:** Ensure `ServiceId` is identical across all 30 devices.
    *   **Result:** PASSED. The `serviceId` is set to `activity.packageName`, which will be the same for all instances of the application.

### Verify Identity Persistence (The `endpointId` Trap)

*   **Fact:** `endpointId` changes every time a device reconnects.
*   **Check:** Verify the app **does not** use `endpointId` as the primary user key.
    *   **Result:** PASSED. `DevicesRegistry.kt` uses the persistent `name` of the device as the key for its `_deviceNameStates` map, which stores persistent state. The `endpointId` is used as a key for the ephemeral `_devices` map.
*   **Check:** Verify the app generates a persistent `UUID` (or loads one from storage) at launch.
    *   **Result:** PASSED. `DeviceIdentifier.kt` creates a unique string ID and saves it to `SharedPreferences` on the first run.
*   **Check:** Is this persistent ID exchanged immediately after connection OR encoded into the `endpointName`?
    *   **Result:** PASSED. The persistent ID is used as the `endpointName` when calling `startAdvertising` in `NearbyConnectionsManager.kt`.

### Verify Payload Handling

*   **Check:** Are "Control Messages" prioritized over "Data Messages"?
    *   **Result:** FAILED. There is no explicit prioritization mechanism. Payloads are processed in the order they are received.
*   **Check:** Does the payload handler catch `PayloadCallback.onPayloadReceived` and immediately check if `destinationId == myPersistentId`?
    *   **Result:** FAILED. The `RoutingEngine.handlePayload` only checks for "BROADCAST" messages and does not compare the `destEndpointId` with the device's own persistent ID.

## Phase 3: Pixel 4 Specific Resilience (The "Legacy" Checklist)

### Verify Bandwidth Conservation

*   **Check:** Are payloads kept small (< 32KB for `BYTES` type)?
    *   **Result:** FAILED. There is no code that enforces a size limit on `BYTES` payloads.
*   **Check:** Does the app avoid sending `STREAM` payloads unless directly connected?
    *   **Result:** PASSED. The `onPayloadReceived` callback in `NearbyConnectionsManager.kt` only processes `Payload.Type.BYTES` and ignores other types, including `STREAM`.

### Verify Connection Flux Handling

*   **Check:** Does the `ConnectionLifecycleCallback` handle `onDisconnected` by:
    1.  Removing the node from the routing table.
    2.  Triggering an immediate re-scan (`startDiscovery`) if the active connection count drops below a threshold (e.g., 2).
    *   **Result:** PARTIALLY PASSED.
    1. FAILED: The disconnected node is not removed from the global `networkGraph`. The local device's neighbor list is updated, but the disconnected device's entry persists in the graph.
    2. PASSED: A re-scan is triggered via the `reconnectWithBackoff` function, but only if the active connection count drops to 0, not below a threshold of 2.
