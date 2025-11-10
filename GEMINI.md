# GEMINI Project Analysis: SimpleMesh

## Project Overview

This is an Android application named "SimpleMesh" that tests device-to-device communication using Google Play Services Nearby Connections API. 

## Building and Running

The project uses Gradle as its build system.

NOTE: This app can NOT RUN in the emulator, the emulator doesn't support the Bluetooth stack.

* **Build the project:**
  ```bash
  ./gradlew build
  ```
* **Install and run the app on a connected device or emulator:**
  ```bash
  ./gradlew installDebug
  ```
  Then launch the app from the device.
* **Run unit tests:**
  ```bash
  ./gradlew test
  ```
* **Run instrumented tests:**
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
* **2025 Coding Standards:** Adhere to modern coding standards, including:
    * Using the latest stable versions of libraries and tools.
    * Following the official Kotlin coding conventions.
    * Writing unit tests for all new code.
    * Using a linter to enforce code style and catch potential errors.
