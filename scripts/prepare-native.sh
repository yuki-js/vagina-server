#!/bin/bash
set -e

echo "=== Preparing Native Executable for Jib ==="

# Create the target directory
mkdir -p build/jib-native

# Find the native runner
NATIVE_RUNNER=$(find build -name '*-runner' -type f ! -path "*/quarkus-app/*" | head -n 1)

if [ -z "$NATIVE_RUNNER" ]; then
    echo "ERROR: Native runner not found!"
    echo "Please build the native executable first using:"
    echo "  ./gradlew build -Dquarkus.package.type=native"
    echo ""
    echo "Or with container build (if GraalVM is not installed):"
    echo "  ./gradlew build -Dquarkus.package.type=native -Dquarkus.native.container-build=true"
    exit 1
fi

echo "Found native runner: $NATIVE_RUNNER"

# Copy and set permissions
cp "$NATIVE_RUNNER" build/jib-native/quarkus-run
chmod +x build/jib-native/quarkus-run

# Show result
ls -lh build/jib-native/quarkus-run

echo ""
echo "✓ Native executable prepared successfully!"
echo ""
echo "You can now build the native container image with:"
echo "  ./gradlew jib -PnativeBuild -Djib.to.image=ghcr.io/yuki-js/quarkus-crud:<tag>-native"
