#!/bin/bash
# Build and deploy Janus APK to the local Go server for auto-update
set -e

DATA_DIR="/data/janus"
UPDATES_DIR="$DATA_DIR/updates"
APP_DIR="$(dirname "$0")"

cd "$APP_DIR"

echo "Building APK..."
./gradlew assembleDebug -q

APK=$(find app/build/outputs/apk/debug -name '*universal*.apk' | head -1)
if [ -z "$APK" ]; then
    APK=$(find app/build/outputs/apk/debug -name '*.apk' | head -1)
fi

if [ -z "$APK" ]; then
    echo "ERROR: No APK found"
    exit 1
fi

# Read version from build.gradle.kts
VERSION_CODE=$(grep 'versionCode' app/build.gradle.kts | head -1 | grep -o '[0-9]*')
VERSION_NAME=$(grep 'versionName' app/build.gradle.kts | head -1 | grep -o '"[^"]*"' | tr -d '"')

echo "Deploying v${VERSION_NAME} (code ${VERSION_CODE})"

# Copy APK to updates dir
mkdir -p "$UPDATES_DIR"
cp "$APK" "$UPDATES_DIR/janus.apk"

# Update version in DB
sqlite3 "$DATA_DIR/janus.db" "
    INSERT OR REPLACE INTO meta (key, value, updated_at) VALUES ('app_version_code', '${VERSION_CODE}', strftime('%s','now'));
    INSERT OR REPLACE INTO meta (key, value, updated_at) VALUES ('app_version_name', '${VERSION_NAME}', strftime('%s','now'));
"

echo "Deployed:"
echo "  APK:     $UPDATES_DIR/janus.apk"
echo "  Version: v${VERSION_NAME} (code ${VERSION_CODE})"
echo "  Server:  http://localhost:8900/api/version"
curl -s http://localhost:8900/api/version 2>/dev/null || echo "(server not running)"
