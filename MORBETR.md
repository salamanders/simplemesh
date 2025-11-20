# SimpleMesh Refactoring & Hardening Plan ("The 25% Better")

This document outlines a focused effort to improve the stability, architecture, and testability of the SimpleMesh application without increasing feature scope.

## 1. Robustness & Lifecycle (Critical Fixes)

### 1.1 Fix Activity Lifecycle Leak
**Severity:** Critical
**Problem:**
`NearbyConnectionsManager` is currently initialized in `MainActivity` using `lifecycleScope`. When the screen rotates or the Activity is recreated:
1. `lifecycleScope` is cancelled.
2. The `externalScope` inside `NearbyConnectionsManager` becomes cancelled.
3. All background P2P operations (discovery, advertising, heartbeats) silently stop, even though the `MainViewModel` (which holds the manager) survives.

**Proposed Solution:**
*   Decouple `NearbyConnectionsManager` scope from `MainActivity`.
*   Inject a scope that survives configuration changes, such as `applicationScope` (created in the Application class) or use `viewModelScope` if the manager's lifecycle is strictly tied to the ViewModel. Given the "always-on" nature of P2P, an application-level scope is preferred to prevent accidental cancellations.

### 1.2 Fix Packet Cache Memory Leak
**Severity:** High
**Problem:**
`seenPackets` is implemented as a `ConcurrentHashMap.newKeySet<String>()`. It grows indefinitely as new packets are received. In a long-running mesh, this will eventually cause an `OutOfMemoryError`.

**Proposed Solution:**
*   Refactor `seenPackets` to a `Map<String, Long>` (PacketID -> Timestamp).
*   Implement a `cleanupJob` that runs periodically (e.g., every 1 minute).
*   **Logic:** Remove entries older than **10 minutes** (TTL).

## 2. Architecture (Separation of Concerns)

### 2.1 Extract `PacketRouter`
**Severity:** Medium
**Problem:**
`NearbyConnectionsManager` violates the Single Responsibility Principle. It currently handles:
1.  Google Nearby Connections API (Hardware/Transport).
2.  Packet Serialization (CBOR).
3.  Deduplication Logic (`seenPackets`).
4.  Flooding Logic (TTL check, rebroadcast).

**Proposed Solution:**
Extract a pure Kotlin class `PacketRouter`.
*   **Responsibilities:** Packet validation, deduplication, TTL management, and deciding *what* to broadcast.
*   **Interface:** `NearbyConnectionsManager` passes raw byte arrays to `PacketRouter`. `PacketRouter` returns a result (e.g., `ShouldForward(bytes)` or `Drop`).
*   **Benefit:** This isolates the complex routing logic from the Android API, making it unit-testable (see Section 3).

## 3. Confidence (Testing)

### 3.1 Logic Unit Tests
**Severity:** Medium
**Problem:**
The project currently relies on manual testing because instrumented tests cannot run on the emulator (missing Bluetooth). Logic bugs are hard to catch.

**Proposed Solution:**
Add JUnit tests for components that *do not* require the Android Bluetooth stack.
*   **`PacketRouterTest`:**
    *   Verify a packet is marked as seen.
    *   Verify duplicate packets are rejected.
    *   Verify TTL is decremented.
    *   Verify packets with TTL=0 are dropped.
    *   Verify cache cleanup removes old entries.
*   **`MeshPacketTest`:**
    *   Verify CBOR encoding/decoding correctness.
    *   Verify handling of large payloads.

## 4. Documentation

### 4.1 Cleanup `BUGS.md`
**Problem:**
The bug tracker contains outdated information.
*   "Broadcast Flood (No Loop Prevention)" is listed, but the code already implements `MeshPacket` with UUID and TTL.
*   "Activity Leak" is listed and will be addressed by Step 1.1.

**Proposed Solution:**
*   Audit `BUGS.md` and remove fixed or obsolete issues.
*   Update `README.md` to reflect the new `PacketRouter` architecture.
