#!/bin/bash
# Build and deploy Janus APK to Hetzner for auto-update
set -e

HETZNER_HOST="root@195.201.91.211"
HETZNER_KEY="$HOME/.ssh/id_ed25519"
HETZNER_PATH="/var/www/kanji/janus"
SSH="ssh -i $HETZNER_KEY $HETZNER_HOST"
SCP="scp -i $HETZNER_KEY"
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
echo "Deploying v${VERSION_NAME} (code ${VERSION_CODE}) to ${HETZNER_HOST}"

cat > /tmp/janus-version.json << EOF
{"version_code": ${VERSION_CODE}, "version_name": "${VERSION_NAME}", "apk": "janus.apk"}
EOF

$SSH "mkdir -p ${HETZNER_PATH}"
$SCP "$APK" "${HETZNER_HOST}:${HETZNER_PATH}/janus.apk"
$SCP /tmp/janus-version.json "${HETZNER_HOST}:${HETZNER_PATH}/version.json"

echo "Deployed:"
echo "  APK:     https://canneji.duckdns.org/janus/janus.apk"
echo "  Version: https://canneji.duckdns.org/janus/version.json"
$SSH "cat ${HETZNER_PATH}/version.json"
