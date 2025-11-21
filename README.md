# SimpleMesh

**SimpleMesh** is an Android application designed to test and demonstrate device-to-device mesh networking using the **Google Play Services Nearby Connections API**.

It implements a multi-hop mesh network capable of managing 30+ nodes by building a sophisticated application-layer protocol on top of the standard `P2P_CLUSTER` strategy.

## Key Features

*   **Sparse Graph Topology**: Limits concurrent connections to 4 per device to prevent connection floods and maintain network stability.
*   **Application-Layer Routing**: Implements a custom packet structure with headers for network-wide flooding (broadcast) and source-routing.
*   **Partition Recovery**: Uses a PING/PONG heartbeat mechanism to detect "zombie" connections and actively manages connection slots to merge network islands.
*   **Persistent Identity**: Devices are identified by a persistent UUID rather than the ephemeral `endpointId` provided by the API.
*   **Bandwidth Conservation**: Optimized for small, infrequent commands and status updates, avoiding heavy `STREAM` payloads.

## Building and Running

The project uses Gradle as its build system.

**Important Notes:**
*   **No Emulator Support:** This app **CANNOT** run on the standard Android emulator because it requires the Bluetooth stack.
*   **Deploy Script:** Use the provided `deploy_all.sh` script to build, deploy, and run the app on all connected physical devices simultaneously.

### Standard Build Commands

*   **Build the project:**
    ```bash
    ./gradlew build
    ```

*   **Install and run on a connected device:**
    ```bash
    ./gradlew installDebug
    ```

## Development Conventions

*   **Language:** 100% Kotlin (v2.x).
*   **UI:** Jetpack Compose (Declarative UI).
*   **Architecture:**
    *   **MVVM**: Uses `MainViewModel` to bridge logic and UI.
    *   **Coroutines**: Uses structured concurrency for all async operations.
    *   **Single Activity**: `MainActivity` acts as the container.
*   **Dependencies:**
    *   Google Play Services Nearby
    *   Jetpack Compose
    *   AndroidX Core

## License

See [LICENSE](LICENSE) file for details.