#!/bin/bash
set -e

# Only download and set up if the SDK directory doesn't exist.
if [ ! -d "$HOME/Android/sdk" ]; then
    echo "Android SDK not found. Downloading and setting up..."
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -O /tmp/tools.zip
    unzip /tmp/tools.zip -d /tmp/tools
    mkdir -p ~/Android/sdk/cmdline-tools/latest
    mv /tmp/tools/cmdline-tools/* ~/Android/sdk/cmdline-tools/latest
    rm -rf /tmp/tools
    rm /tmp/tools.zip

    export ANDROID_SDK_ROOT="$HOME/Android/sdk"
    export PATH="$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools"

    echo "Installing SDK components..."
    sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.1.0"
    yes | sdkmanager --licenses
else
    echo "Android SDK already exists. Skipping download and setup."
fi

# Set environment variables for the current session
export ANDROID_SDK_ROOT="$HOME/Android/sdk"
export PATH="$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools"
export ANDROID_HOME="$HOME/Android/sdk"


# Create local.properties if it doesn't exist
if [ ! -f "app/local.properties" ]; then
    echo "Creating app/local.properties..."
    echo "sdk.dir=$HOME/Android/sdk" > app/local.properties
fi

# Add properties to gradle.properties if they don't already exist
if ! grep -q "android.useAndroidX" gradle.properties; then
    echo "android.useAndroidX=true" >> gradle.properties
fi

if ! grep -q "android.enableJetifier" gradle.properties; then
    echo "android.enableJetifier=true" >> gradle.properties
fi

if ! grep -q "org.gradle.jvmargs" gradle.properties; then
    echo "org.gradle.jvmargs=-Xmx2048m" >> gradle.properties
fi

echo "Android SDK setup complete."
