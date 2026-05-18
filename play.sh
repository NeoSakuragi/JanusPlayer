#!/bin/bash
# Quick launch Janus+ to a video. Usage: ./play.sh [device]
# Navigates: home → first series → scroll to episodes → tap first episode
DEVICE="${1:-emulator-5554}"
adb -s $DEVICE shell am force-stop com.janusplus
adb -s $DEVICE shell am start -n com.janusplus/.v2.MainActivity
sleep 5
adb -s $DEVICE shell input tap 400 350   # tap first series
sleep 4
adb -s $DEVICE shell input swipe 400 600 400 200 300  # scroll to episodes
sleep 2
adb -s $DEVICE shell input tap 250 200   # tap first episode
echo "Playing. Use: adb -s $DEVICE exec-out screencap -p > /tmp/screen.png"
