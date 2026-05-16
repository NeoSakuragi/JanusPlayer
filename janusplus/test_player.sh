#!/bin/bash
# Fast build → deploy → play video → test seekbar → screenshot
T=TQHNU20622101583
ADB="$HOME/Android/Sdk/platform-tools/adb -s $T"

./gradlew :janusplus:assembleDebug 2>&1 | tail -1
$ADB install -r janusplus/build/outputs/apk/debug/janusplus-0.6-universal.apk | tail -1
$ADB shell am force-stop com.janusplus
sleep 1
$ADB shell am start -n com.janusplus/.JanusPlusActivity

# Wait for library
for i in $(seq 1 20); do
  PID=$($ADB shell pidof com.janusplus 2>/dev/null | tr -d '\r')
  [ -n "$PID" ] && $ADB logcat -d --pid=$PID 2>/dev/null | grep -q "Cover atlas" && break
  sleep 1
done
sleep 1

# Navigate: home → first series → first episode → play
$ADB shell input keyevent KEYCODE_DPAD_CENTER; sleep 3
$ADB shell input keyevent KEYCODE_DPAD_DOWN; sleep 0.3
$ADB shell input keyevent KEYCODE_DPAD_CENTER; sleep 5

echo "=== PLAYING ==="
$ADB shell screencap -p /sdcard/t1.png

# Tap to show controls
$ADB shell input tap 600 400; sleep 0.5
$ADB shell screencap -p /sdcard/t2.png

# Tap seekbar at 75%
$ADB shell input tap 1400 720; sleep 1
$ADB shell screencap -p /sdcard/t3.png

# Pull all
$ADB pull /sdcard/t1.png /tmp/t1.png 2>/dev/null
$ADB pull /sdcard/t2.png /tmp/t2.png 2>/dev/null
$ADB pull /sdcard/t3.png /tmp/t3.png 2>/dev/null

echo "=== DONE ==="
echo "t1: video playing"
echo "t2: controls shown"
echo "t3: after seekbar tap at 75%"
