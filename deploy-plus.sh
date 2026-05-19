#!/bin/bash
# Build, version bump, and deploy Janus+ APK to the remote Go server
set -e

VPS="root@195.201.91.211"
REMOTE_DB="/data/janus/janus.db"
REMOTE_UPDATES="/data/janus/updates"
APP_DIR="$(dirname "$0")"
GRADLE="janusplus/build.gradle.kts"

cd "$APP_DIR"

# ── Read current version ──
CURRENT_CODE=$(grep 'versionCode' "$GRADLE" | head -1 | grep -o '[0-9]*')
CURRENT_NAME=$(grep 'versionName' "$GRADLE" | head -1 | grep -o '"[^"]*"' | tr -d '"')
echo "Current: v${CURRENT_NAME} (code ${CURRENT_CODE})"

# ── Bump version ──
NEW_CODE=$((CURRENT_CODE + 1))
# Increment minor: 0.7 → 0.8, 0.9 → 0.10
MAJOR=$(echo "$CURRENT_NAME" | cut -d. -f1)
MINOR=$(echo "$CURRENT_NAME" | cut -d. -f2)
NEW_NAME="${MAJOR}.$((MINOR + 1))"

sed -i "s/versionCode = ${CURRENT_CODE}/versionCode = ${NEW_CODE}/" "$GRADLE"
sed -i "s/versionName = \"${CURRENT_NAME}\"/versionName = \"${NEW_NAME}\"/" "$GRADLE"
echo "Bumped:  v${NEW_NAME} (code ${NEW_CODE})"

# ── Build ──
echo "Building..."
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :janusplus:assembleDebug -q

APK=$(find janusplus/build/outputs/apk/debug -name '*arm64-v8a*.apk' | head -1)
if [ -z "$APK" ]; then
    APK=$(find janusplus/build/outputs/apk/debug -name '*.apk' | head -1)
fi
if [ -z "$APK" ]; then
    echo "ERROR: No APK found"
    exit 1
fi

APK_SIZE=$(wc -c < "$APK")
APK_SHA256=$(sha256sum "$APK" | cut -d' ' -f1)

echo "APK:     $APK"
echo "Size:    ${APK_SIZE} bytes ($(( APK_SIZE / 1024 / 1024 ))MB)"
echo "SHA-256: ${APK_SHA256}"

# ── Push to VPS ──
echo "Uploading to VPS..."
scp "$APK" "${VPS}:${REMOTE_UPDATES}/janusplus.apk"

# ── Update remote DB ──
echo "Updating remote DB..."
ssh "$VPS" "sqlite3 ${REMOTE_DB} \"
    INSERT OR REPLACE INTO meta (key, value, updated_at) VALUES ('plus_version_code', '${NEW_CODE}', strftime('%s','now'));
    INSERT OR REPLACE INTO meta (key, value, updated_at) VALUES ('plus_version_name', '${NEW_NAME}', strftime('%s','now'));
    INSERT OR REPLACE INTO meta (key, value, updated_at) VALUES ('plus_size', '${APK_SIZE}', strftime('%s','now'));
    INSERT OR REPLACE INTO meta (key, value, updated_at) VALUES ('plus_sha256', '${APK_SHA256}', strftime('%s','now'));
\""

echo ""
echo "Deployed Janus+ v${NEW_NAME} (code ${NEW_CODE})"
echo "Devices will see the update on next launch."
