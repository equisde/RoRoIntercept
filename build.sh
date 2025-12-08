#!/bin/bash

echo "🚀 HTTP Interceptor - Build Script"
echo "===================================="
echo ""

# Check if Android SDK is available
if [ -z "$ANDROID_HOME" ]; then
    echo "⚠️  ANDROID_HOME not set"
    echo "Please set ANDROID_HOME environment variable"
    exit 1
fi

echo "✓ Android SDK found at: $ANDROID_HOME"
echo ""

# Clean previous build
echo "🧹 Cleaning previous build..."
./gradlew clean

echo ""
echo "🔨 Building APK..."
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Build successful!"
    echo ""
    echo "📦 APK location:"
    echo "   app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "📱 Install with:"
    echo "   adb install app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "🌐 After installation:"
    echo "   1. Open the app"
    echo "   2. Tap 'Start Proxy'"
    echo "   3. Configure proxy on 0.0.0.0:2580"
    echo "   4. Export and install CA certificate"
    echo "   5. Access Web UI on http://[device-ip]:8080"
    echo ""
else
    echo ""
    echo "❌ Build failed!"
    echo "Check the errors above"
    exit 1
fi
