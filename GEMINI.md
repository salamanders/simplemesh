# GEMINI Project Analysis: SimpleMesh

## Project Overview

This is an Android application named "SimpleMesh" that tests device-to-device communication using
Google Play Services Nearby Connections API.

## Building and Running

The project uses Gradle as its build system.

NOTE: This app **CAN NOT RUN in the emulator**, the emulator doesn't support the Bluetooth stack.

NOTE: Instead, strongly consider running `deploy_all.sh` which builds the app, gets the list of
devices, and deploys, clears logcat, and runs on all connected devices.

* **Build the project:**
  ```bash
  ./gradlew build
  ```
* **Install and run the app on a connected device or emulator:**
  ```bash
  ./gradlew installDebug
  ```

Then launch the app from the device.

* **Run unit tests:** (none yet)
  ```bash
  ./gradlew test
  ```
* **Run instrumented tests:** (none yet)
  ```bash
  ./gradlew connectedAndroidTest
  ```

## Development Conventions

* **Language:** The project is written entirely in Kotlin.
* **UI:** The user interface is built with Jetpack Compose, which promotes a declarative and
  reactive programming style.
* **Architecture:** The project follows the standard Android application structure with a single
  `app` module.
* **Dependencies:** Key dependencies include:
    * Jetpack Compose for the UI.
    * Google Play Services Nearby for peer-to-peer connections.
    * AndroidX libraries for core functionality.

## Modern Kotlin Development

* **Kotlin 2.x:** Leverage the latest features and syntax from Kotlin 2.x to write concise and
  efficient code.
* **Fluent and Expressive Code:** Strive for code that reads like natural language. Use higher-order
  functions and extension functions to create fluent and expressive APIs.
* **Safety and Immutability:** Prioritize null safety by using nullable types (`?`) and the `?.` and
  `?:` operators. Favor immutable data structures (`val`, `listOf`, `mapOf`) to prevent unintended
  side effects.
* **Structured Concurrency:** Use coroutines for all asynchronous operations. Follow structured
  concurrency principles to ensure that coroutines are launched in a specific scope and are
  automatically cancelled when the scope is cancelled. This prevents resource leaks and simplifies
  error handling.
* **Kotlin Time:** Use `kotlin.time.Duration` for all time-related values. It provides a type-safe
  and readable way to represent durations, avoiding ambiguity and potential errors from using raw
  numeric types.
* **2025 Coding Standards:** Adhere to modern coding standards, including:
    * Using the latest stable versions of libraries and tools.
    * Following the official Kotlin coding conventions.
    * Writing unit tests for all new code.
    * Using a linter to enforce code style and catch potential errors.

## Separation of Concerns

* **`MainActivity.kt`**: The entry point of the application. Its primary role is to request
  necessary permissions and set up the UI. It initializes and coordinates the `MainViewModel` and
  `NearbyConnectionsManager`.

* **`MainViewModel.kt`**: Acts as a bridge between the `NearbyConnectionsManager` and the UI. It
  holds the device list as a `StateFlow` observed by the UI, ensuring the screen always displays the
  latest device states.

* **`DevicesRegistry.kt`**: A singleton object that serves as the single source of truth for the
  state of all discovered devices. It maintains a map of `endpointId` to `DeviceState`.

* **`DeviceState.kt`**: A data class representing the state of a single remote device. It includes
  the device's name, endpoint ID, and current `ConnectionPhase`. Crucially, it manages its own
  lifecycle through a timeout mechanism. When a device enters a new phase, a coroutine is launched
  to transition it to the next phase (or remove it) if it remains in the current phase for too long.

* **`ConnectionPhase.kt`**: An enum that defines the possible states of a connection (e.g.,
  `DISCOVERED`, `CONNECTING`, `CONNECTED`, `REJECTED`). Each phase has an associated timeout and a
  designated next phase upon timeout, which drives the state machine.

* **`NearbyConnectionsManager.kt`**: The core of the application's logic. It handles all
  interactions with the Google Play Services Nearby Connections API, including:
    * "Successful" state transitions: transitions made when something positive happens. This is the
      flip side to the ConnectionPhase's timeout transitions, which are normally for error states.
    * Starting and stopping device discovery and advertising.
    * Initiating, accepting, and rejecting connections.
    * Sending and receiving data (payloads), specifically the `PING` and `PONG` messages for
      heartbeats.
    * Updating the `DevicesRegistry` based on connection lifecycle events.

## Example Flows

### Flow 1: Successful Connection and Heartbeat

This flow describes how two devices, A and B, discover each other and maintain a stable connection.

1. **Discovery**:
    * Device A (discoverer) finds Device B (advertiser).
    * **Code**: `NearbyConnectionsManager`'s `onEndpointFound` is called on Device A.
    * **State Change**: Device A adds Device B to its `DevicesRegistry` with the phase `DISCOVERED`.
    * **Action**: Device A immediately calls `requestConnection` to connect to Device B.

2. **Connection Initiation**:
    * Device B receives the connection request from A.
    * **Code**: `NearbyConnectionsManager`'s `onConnectionInitiated` is called on Device B.
    * **State Change**: Device B adds Device A to its `DevicesRegistry` with the phase `CONNECTING`.
    * **Action**: Device B immediately calls `acceptConnection`.

3. **Connection Established**:
    * Device A receives confirmation that the connection was successful.
    * **Code**: `NearbyConnectionsManager`'s `onConnectionResult` is called on Device A with
      `STATUS_OK`.
    * **State Change**: Device A updates Device B's phase to `CONNECTED`.
    * **Action**: Device A schedules and sends a `PING` message to Device B after a 15-second delay.

4. **Heartbeat (PING-PONG)**:
    * Device B receives the `PING`.
    * **Code**: `PayloadCallback`'s `onPayloadReceived` on Device B is triggered.
    * **Action**: Device B immediately sends a `PONG` message back to Device A.

5. **Heartbeat Received**:
    * Device A receives the `PONG`.
    * **Code**: `PayloadCallback`'s `onPayloadReceived` on Device A is triggered.
    * **State Change**: Device A updates Device B's phase back to `CONNECTED`. **This resets the
      timeout countdown.**
    * **Action**: Device A schedules and sends another `PING` after a 30-second delay, continuing
      the heartbeat loop.

### Flow 2: Rejected Connection and Timeout

This flow describes how a device is rejected and eventually removed from the registry.

1. **Discovery and Request**:
    * The flow begins identically to the successful connection, with Device A discovering Device B
      and requesting a connection.

2. **Rejection**:
    * For some reason, Device B rejects the connection (in the current implementation, it
      auto-accepts, but this could be because of too many connections or another reason).
    * **Code**: `NearbyConnectionsManager`'s `onConnectionResult` is called on Device A with
      `STATUS_CONNECTION_REJECTED`.
    * **State Change**: Device A updates Device B's phase to `REJECTED`. A 30-second timeout begins.

3. **Timeout to Error**:
    * Device B remains in the `REJECTED` phase for 30 seconds.
    * **Code**: The `startAutoTimeout` coroutine in `DeviceState` completes its delay.
    * **State Change**: The timeout function `phase.phaseOnTimeout()` for `REJECTED` returns
      `ERROR`. Device A updates Device B's phase to `ERROR`. A new 30-second timeout begins.

4. **Timeout and Removal**:
    * Device B remains in the `ERROR` phase for 30 seconds.
    * **Code**: The `startAutoTimeout` coroutine in `DeviceState` completes its delay again.
    * **State Change**: The timeout function `phase.phaseOnTimeout()` for `ERROR` returns `null`.
    * **Action**: Because the next phase is `null`, `DevicesRegistry.remove(endpointId)` is called,
      and Device B is removed from Device A's list.

