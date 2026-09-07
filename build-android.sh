#!/usr/bin/env bash
set -e

echo "=========================================="
echo "   Building Be On It - Android App        "
echo "=========================================="

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/thongdee/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

REPO_DIR=/home/thongdee/for-android
DOWNLOADS_DIR=/home/thongdee/downloads-site

cd "$REPO_DIR"

# Ensure configs exist
if [ ! -f "stoatbuild.properties" ]; then
    cp stoatbuild.properties.example stoatbuild.properties
fi
if [ ! -f "sentry.properties" ]; then
    cp sentry.properties.example sentry.properties
fi
if [ ! -f "app/google-services.json" ]; then
    cp app/google-services.json.example app/google-services.json
fi

chmod +x gradlew

echo "Accepting licenses..."
yes | sdkmanager --licenses >/dev/null 2>&1 || true

echo "Starting Gradle build (assembleDebug)..."
./gradlew assembleDebug --no-daemon --stacktrace

# Locate generated APK
APK_PATH=$(find app/build/outputs/apk -name "*.apk" | head -n 1)

if [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ]; then
    echo "Build successful! Found APK at $APK_PATH"
    
    mkdir -p "$DOWNLOADS_DIR"
    cp "$APK_PATH" "$DOWNLOADS_DIR/BeOnIt.apk"
    echo "Copied APK to $DOWNLOADS_DIR/BeOnIt.apk"
    
    # Update index.html to add Android download button if not already present
    if ! grep -q "BeOnIt.apk" "$DOWNLOADS_DIR/index.html"; then
        sed -i '/StoatSetup.exe.*Windows/a \    <div style="margin-top: 12px;"><a class="btn" style="background: #10b981;" href="BeOnIt.apk" download>ดาวน์โหลดสำหรับ Android (.apk)</a></div>\n    <div class="meta">BeOnIt.apk</div>' "$DOWNLOADS_DIR/index.html"
    fi
    
    echo "=========================================="
    echo " Android App deployed to downloads site!"
    echo " Direct download: https://stoat.178.104.95.94.nip.io/BeOnIt.apk (or downloads page)"
    echo "=========================================="
else
    echo "Error: APK not found after build!"
    exit 1
fi
