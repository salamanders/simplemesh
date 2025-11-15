This is an Android application named "SimpleMesh" that tests device-to-device communication using Google Play Services Nearby Connections API.

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
