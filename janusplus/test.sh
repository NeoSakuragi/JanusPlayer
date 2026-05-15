#!/bin/bash
# Fast build-deploy-test cycle for Janus+ on tablet
T=TQHNU20622101583
ADB="$HOME/Android/Sdk/platform-tools/adb -s $T"

echo "=== BUILD ==="
./gradlew :janusplus:assembleDebug 2>&1 | tail -1
if [ $? -ne 0 ]; then echo "BUILD FAILED"; exit 1; fi

echo "=== INSTALL ==="
$ADB install -r janusplus/build/outputs/apk/debug/janusplus-0.3-universal.apk | tail -1
$ADB shell am force-stop com.janusplus
sleep 1
$ADB shell am start -n com.janusplus/.JanusPlusActivity
cp janusplus/build/outputs/apk/debug/janusplus-0.3-universal.apk janusplus/web/janusplus-0.3-universal.apk

echo "=== WAIT FOR LIBRARY ==="
PID=""
for i in $(seq 1 20); do
  PID=$($ADB shell pidof com.janusplus 2>/dev/null | tr -d '\r')
  [ -n "$PID" ] && break; sleep 0.5
done
for i in $(seq 1 30); do
  $ADB logcat -d --pid=$PID 2>/dev/null | grep -q "Cover atlas" && break; sleep 1
done
sleep 1

echo "=== NAVIGATE TO SLAM DUNK ==="
$ADB shell input keyevent KEYCODE_DPAD_RIGHT
$ADB shell input keyevent KEYCODE_DPAD_RIGHT
$ADB shell input keyevent KEYCODE_DPAD_RIGHT
sleep 0.3
$ADB shell input keyevent KEYCODE_DPAD_CENTER

echo "=== WAIT FOR DETAIL DATA ==="
for i in $(seq 1 30); do
  $ADB logcat -d --pid=$PID 2>/dev/null | grep -q "Thumbs:" && break; sleep 1
done
sleep 3

echo "=== LOGS ==="
$ADB logcat -d --pid=$PID 2>/dev/null | grep -iE "(ThumbAtlas|JanusPlus|PERF.*SERIES)" | tail -10

echo "=== SCREENSHOT ==="
$ADB shell screencap -p /sdcard/test.png
$ADB pull /sdcard/test.png /tmp/jplus_test.png 2>/dev/null
xdg-open /tmp/jplus_test.png &

echo "=== DONE ==="
