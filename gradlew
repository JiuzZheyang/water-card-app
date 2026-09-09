#!/bin/sh
# Gradle wrapper script

WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
WRAPPER_URL="https://services.gradle.org/distributions/gradle-8.7-bin.zip"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Downloading Gradle wrapper..."
    mkdir -p gradle/wrapper
    # Try to download gradle directly as a fallback
    if command -v gradle >/dev/null 2>&1; then
        exec gradle wrapper --gradle-version=8.7
    else
        echo "ERROR: gradle not found. Please install Android Studio or Gradle."
        exit 1
    fi
fi

exec gradle "$@"
