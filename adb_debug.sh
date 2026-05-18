#!/bin/bash
# Janus+ ADB debug helper
# Usage: ./adb_debug.sh <command> [args]
D="${JANUS_DEVICE:-TQHNU20622101583}"

case "$1" in
  restart)
    adb -s $D shell am force-stop com.janusplus
    adb -s $D logcat -c
    adb -s $D shell monkey -p com.janusplus -c android.intent.category.LAUNCHER 1 2>/dev/null
    echo "Restarted"
    ;;
  deploy)
    cd /home/bruno/CLProjects/Janus
    ./gradlew :janusplus:assembleDebug 2>&1 | tail -3
    adb -s $D install -r janusplus/build/outputs/apk/debug/janusplus-0.6-armeabi-v7a.apk
    echo "Deployed"
    ;;
  tap)
    adb -s $D shell input tap $2 $3
    ;;
  key)
    adb -s $D shell input keyevent $2
    ;;
  center)
    adb -s $D shell input keyevent KEYCODE_DPAD_CENTER
    ;;
  left)
    adb -s $D shell input keyevent KEYCODE_DPAD_LEFT
    ;;
  right)
    adb -s $D shell input keyevent KEYCODE_DPAD_RIGHT
    ;;
  back)
    adb -s $D shell input keyevent KEYCODE_BACK
    ;;
  seek)
    # Seek forward N times (each = 10s)
    for i in $(seq 1 ${2:-5}); do
      adb -s $D shell input keyevent KEYCODE_DPAD_RIGHT
      sleep 0.2
    done
    ;;
  pause)
    adb -s $D shell input tap 600 500
    ;;
  screen)
    adb -s $D shell screencap -p /sdcard/screen.png
    adb -s $D pull /sdcard/screen.png /tmp/tablet_screen.png 2>&1 | tail -1
    ;;
  perf)
    adb -s $D logcat -d -s PERF:D | grep "${2:-PLAYER}" | tail -${3:-10}
    ;;
  perfall)
    echo "=== HOME ===" && adb -s $D logcat -d -s PERF:D | grep HOME | tail -3
    echo "=== SERIES ===" && adb -s $D logcat -d -s PERF:D | grep SERIES | tail -3
    echo "=== PLAYER ===" && adb -s $D logcat -d -s PERF:D | grep PLAYER | tail -5
    ;;
  battery)
    adb -s $D shell dumpsys battery | grep level
    ;;
  *)
    echo "Commands: restart deploy tap key center left right back seek pause screen perf perfall battery"
    ;;
esac
