#!/bin/bash
# Runs on the emulator: installs the APK, seeds it, walks through the screens and takes screenshots.
set -u
APK=$(ls "$GITHUB_WORKSPACE"/out/*.apk | head -n 1)
PKG=com.artemispocket
SHOTS="$GITHUB_WORKSPACE/shots"
mkdir -p "$SHOTS"
log() { echo "== $*"; }
shot() { adb exec-out screencap -p > "$SHOTS/$1.png"; log "shot $1"; }
key() { adb shell input "${2:-gamepad}" keyevent "$1"; sleep "${3:-1.2}"; }

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 1
adb shell wm size 1920x1080
adb shell wm density 400
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell getprop ro.product.cpu.abilist
log "install"
adb install -r -g "$APK" 2>&1 | tail -3

adb shell pm list packages | sed 's/^package://' | sort > /tmp/pkgs.txt
bash "$GITHUB_WORKSPACE/.github/shots/make_seed.sh" /tmp/seed /tmp/pkgs.txt > /tmp/seed.log 2>&1
cat /tmp/seed.log

push_seed() {
  adb shell am force-stop $PKG
  adb shell mkdir -p /sdcard/Android/data/$PKG/files/preview
  adb push "$1"/. /sdcard/Android/data/$PKG/files/preview/ > /dev/null
}
# First start creates the app's folders, then the seed goes in and it starts again
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/com.limelight.PcView > /dev/null; sleep 6
push_seed /tmp/seed
adb logcat -c
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/com.limelight.PcView; sleep 14
shot 01_home
key KEYCODE_DPAD_RIGHT dpad 2.5; shot 02_home_next
key KEYCODE_BUTTON_Y gamepad 2; shot 03_home_options
key KEYCODE_BUTTON_B gamepad 1.5
key KEYCODE_BUTTON_X gamepad 5; shot 04_game_page
key KEYCODE_BUTTON_B gamepad 2.5
for n in 1 2 3 4 5 6 7 8 9; do key KEYCODE_DPAD_RIGHT dpad 0.5; done
sleep 1.5; shot 05_home_last
key KEYCODE_BUTTON_A gamepad 2.5; shot 06_home_add
key KEYCODE_BUTTON_B gamepad 1.5
key KEYCODE_BUTTON_R1 gamepad 4; shot 07_streaming
key KEYCODE_BUTTON_START gamepad 3; shot 08_settings
key KEYCODE_BUTTON_B gamepad 2.5
key KEYCODE_BUTTON_R1 gamepad 4; shot 09_cloud
key KEYCODE_BUTTON_R1 gamepad 5; shot 10_android
key KEYCODE_DPAD_RIGHT dpad 2; shot 11_android_next
key KEYCODE_BUTTON_R1 gamepad 4; shot 12_retro

# The Android section in the grid and list views
for mode in grid xmb; do
  rm -rf /tmp/seed2; mkdir -p /tmp/seed2
  printf 'pref\tstring\tpocket_view_mode\t%s\n' "$mode" > /tmp/seed2/seed.txt
  push_seed /tmp/seed2
  adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/com.limelight.PcView > /dev/null; sleep 8
  key KEYCODE_BUTTON_R1 gamepad 3
  key KEYCODE_BUTTON_R1 gamepad 3
  key KEYCODE_BUTTON_R1 gamepad 5; shot "13_android_$mode"
  key KEYCODE_DPAD_RIGHT dpad 2; shot "14_android_${mode}_next"
done

adb logcat -d -b crash > "$SHOTS/crash.txt" 2>&1 || true
adb logcat -d -v brief | grep -E "AndroidRuntime|$PKG|W/View|E/" | tail -n 400 > "$SHOTS/logcat.txt" 2>&1 || true
ls -la "$SHOTS"
