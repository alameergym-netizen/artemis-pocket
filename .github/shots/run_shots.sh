#!/bin/bash
# Runs on the emulator: installs the APK, seeds it, walks through the screens and takes screenshots.
# Each scene starts the app fresh; if the emulator dies (its software GPU can), it is started
# again and the next scene goes on.
set -u
APK=$(ls "$GITHUB_WORKSPACE"/out/*.apk | head -n 1)
PKG=com.artemispocket
SHOTS="$GITHUB_WORKSPACE/shots"
EMU_OPTS=${EMU_OPTS:-"-no-window -no-snapshot -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none -camera-front none"}
mkdir -p "$SHOTS"
log() { echo "== $*"; }
alive() { timeout 10 adb shell echo ok > /dev/null 2>&1; }
shot() {
  alive || { log "emulator gone before $1"; return 1; }
  timeout 30 adb exec-out screencap -p > "$SHOTS/$1.png"; log "shot $1"
}
key() { timeout 15 adb shell input "${2:-gamepad}" keyevent "$1"; sleep "${3:-1.2}"; }

setup_device() {
  adb shell settings put global window_animation_scale 0
  adb shell settings put global transition_animation_scale 0
  adb shell settings put global animator_duration_scale 1
  adb shell wm size 1920x1080
  adb shell wm density 400
  adb shell settings put system screen_off_timeout 1800000
  adb shell svc power stayon true
  adb shell input keyevent KEYCODE_WAKEUP
}

restarts=0
ensure_emulator() {
  alive && return 0
  [ $restarts -ge 3 ] && return 1
  restarts=$((restarts+1))
  log "restarting the emulator ($restarts)"
  pkill -f qemu-system 2>/dev/null; sleep 3
  adb kill-server > /dev/null 2>&1; adb start-server > /dev/null 2>&1
  nohup "$ANDROID_HOME/emulator/emulator" -avd test $EMU_OPTS > /tmp/emu-$restarts.log 2>&1 &
  timeout 60 adb wait-for-device
  for i in $(seq 1 90); do
    [ "$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 3
  done
  sleep 5
  setup_device
  alive
}

push_seed() {
  adb shell am force-stop $PKG
  adb shell mkdir -p /sdcard/Android/data/$PKG/files/preview
  adb push "$1"/. /sdcard/Android/data/$PKG/files/preview/ > /dev/null
}

# A fresh start of the app (a button press first: a handheld is driven with buttons)
launch() {
  adb shell am force-stop $PKG
  adb shell input dpad keyevent KEYCODE_DPAD_DOWN
  timeout 60 adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/com.limelight.PcView > /dev/null
  sleep 8
}

scene() { # name
  ensure_emulator || { log "no emulator for $1"; return 1; }
  log "scene $1"
  launch
}

setup_device
adb shell getprop ro.product.cpu.abilist
log "install"
adb install -r -g "$APK" 2>&1 | tail -3

adb shell pm list packages | sed 's/^package://' | sort > /tmp/pkgs.txt
bash "$GITHUB_WORKSPACE/.github/shots/make_seed.sh" /tmp/seed /tmp/pkgs.txt > /tmp/seed.log 2>&1
cat /tmp/seed.log

# First start creates the app's folders, then the seed goes in
timeout 60 adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/com.limelight.PcView > /dev/null; sleep 6
push_seed /tmp/seed
adb logcat -c

if scene home; then
  shot 01_home
  key KEYCODE_DPAD_RIGHT dpad 2.5; shot 02_home_next
  key KEYCODE_BUTTON_Y gamepad 2.5; shot 03_home_options
fi

if scene add; then
  for n in 1 2 3 4 5 6 7 8 9; do key KEYCODE_DPAD_RIGHT dpad 0.6; done
  sleep 1.5; shot 05_home_last
  key KEYCODE_BUTTON_A gamepad 3; shot 06_home_add
fi

if scene streaming; then
  key KEYCODE_BUTTON_R1 gamepad 4; shot 07_streaming
  key KEYCODE_BUTTON_START gamepad 3; shot 08_settings
fi

if scene sections; then
  key KEYCODE_BUTTON_R1 gamepad 3
  key KEYCODE_BUTTON_R1 gamepad 4; shot 09_cloud
  key KEYCODE_BUTTON_R1 gamepad 5; shot 10_android
  key KEYCODE_DPAD_RIGHT dpad 2; shot 11_android_next
  key KEYCODE_BUTTON_R1 gamepad 4; shot 12_retro
fi

# The Android section in the grid and list views
for mode in grid xmb carousel; do
  ensure_emulator || break
  rm -rf /tmp/seedm; mkdir -p /tmp/seedm
  printf 'pref\tstring\tpocket_view_mode\t%s\n' "$mode" > /tmp/seedm/seed.txt
  push_seed /tmp/seedm
  [ "$mode" = carousel ] && break
  if scene "android_$mode"; then
    key KEYCODE_BUTTON_R1 gamepad 3
    key KEYCODE_BUTTON_R1 gamepad 3
    key KEYCODE_BUTTON_R1 gamepad 5; shot "13_android_$mode"
    key KEYCODE_DPAD_RIGHT dpad 2; shot "14_android_${mode}_next"
  fi
done

# The game page last: its videos can bring the emulator down
if scene game_page; then
  key KEYCODE_DPAD_RIGHT dpad 1.5
  key KEYCODE_DPAD_RIGHT dpad 2
  key KEYCODE_BUTTON_X gamepad 6; shot 04_game_page
  key KEYCODE_DPAD_DOWN dpad 3; shot 04_game_page_more
fi

ensure_emulator && {
  adb logcat -d -b crash > "$SHOTS/crash.txt" 2>&1 || true
  adb logcat -d -v brief | grep -E "AndroidRuntime|$PKG|E/" | tail -n 400 > "$SHOTS/logcat.txt" 2>&1 || true
}
ls -la "$SHOTS"
exit 0
