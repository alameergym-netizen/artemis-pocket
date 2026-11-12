#!/bin/bash
# Runs on the emulator: installs the APK, seeds it, walks through the screens and takes screenshots.
# Each scene starts the app fresh; if the emulator dies (its software GPU can), it is started
# again and the next scene goes on.
set -u
APK=$(ls "$GITHUB_WORKSPACE"/out/*.apk | head -n 1)
PKG=com.artemispocket
SHOTS="$GITHUB_WORKSPACE/shots"
EMU_OPTS=${EMU_OPTS:-"-no-window -no-snapshot -gpu guest -feature -Vulkan -noaudio -no-boot-anim -camera-back none -camera-front none"}
mkdir -p "$SHOTS"
log() { echo "== $*"; }
alive() { timeout 10 adb shell echo ok > /dev/null 2>&1; }
shot() {
  alive || { log "emulator gone before $1"; return 1; }
  timeout 30 adb exec-out screencap -p > "$SHOTS/$1.png"; log "shot $1"
}
key() { timeout 15 adb shell input "${2:-gamepad}" keyevent "$1"; sleep "${3:-1.2}"; }

# Every adb call is bounded: with the emulator gone, adb would wait for a device forever
adbt() { timeout "${T:-20}" adb "$@"; }

setup_device() {
  adbt shell settings put global window_animation_scale 0
  adbt shell settings put global transition_animation_scale 0
  adbt shell settings put global animator_duration_scale 1
  adbt shell wm size 1920x1080
  adbt shell wm density 400
  adbt shell settings put system screen_off_timeout 1800000
  adbt shell svc power stayon true
  adbt shell input keyevent KEYCODE_WAKEUP
}

restarts=0
ensure_emulator() {
  alive && return 0
  [ $restarts -gt 0 ] && { log "emulator log ($restarts):"; tail -n 25 /tmp/emu-$restarts.log; }
  log "last words ($restarts):"; grep -vE "AudioFlinger|throttle" /tmp/live-logcat-$restarts.txt 2>/dev/null | tail -n 60 | cut -c1-220
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
  alive || { log "emulator log:"; tail -n 25 /tmp/emu-$restarts.log; return 1; }
  # A restarted emulator can come back without the app (and its seed)
  timeout 20 adb shell pm path $PKG > /dev/null 2>&1 || prepare_app
  return 0
}

prepare_app() {
  log "install"
  timeout 180 adb install -r -g "$APK" 2>&1 | tail -3
  # (The seed can only go in once the app made its folder: a folder made from here, the app can't read)
  # Everything the handheld logs, kept on this side: if the emulator dies, its last words
  (timeout 900 adb logcat -v brief > /tmp/live-logcat-$restarts.txt 2>&1 &)
  # First start creates the app's folders, then the seed goes in
  timeout 60 adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/com.limelight.PcView > /dev/null; sleep 6
  push_seed /tmp/seed
  # ...and is read by the next start
  timeout 60 adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/com.limelight.PcView > /dev/null; sleep 6
  adbt shell am force-stop $PKG
}

push_seed() {
  adbt shell am force-stop $PKG
  adbt shell mkdir -p /sdcard/Android/data/$PKG/files/preview
  T=60 adbt push "$1"/. /sdcard/Android/data/$PKG/files/preview/ > /dev/null
}

# A fresh start of the app (a button press first: a handheld is driven with buttons)
launch() {
  adbt shell am force-stop $PKG
  adbt shell input dpad keyevent KEYCODE_DPAD_DOWN
  timeout 60 adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/com.limelight.PcView > /dev/null
  sleep 8
}

scene() { # name
  ensure_emulator || { log "no emulator for $1"; return 1; }
  log "scene $1"
  # This scene's log, kept on this side (the last lines show if the emulator dies)
  pkill -f "adb logcat -v brief" 2>/dev/null
  (timeout 600 adb logcat -v brief -T 1 > /tmp/live-logcat-$restarts.txt 2>&1 &)
  launch
}

setup_device
adbt shell getprop ro.product.cpu.abilist
adbt shell pm list packages | sed 's/^package://' | sort > /tmp/pkgs.txt
bash "$GITHUB_WORKSPACE/.github/shots/make_seed.sh" /tmp/seed /tmp/pkgs.txt > /tmp/seed.log 2>&1
cat /tmp/seed.log
prepare_app
# A ROM folder (empty files) and file access, so the Retro section has systems to show
for sys in nes snes n64 psx gba megadrive; do adbt shell mkdir -p /sdcard/ROMs/$sys; done
adbt shell 'touch "/sdcard/ROMs/nes/Super Mario Bros. (World).nes" "/sdcard/ROMs/nes/The Legend of Zelda (USA).nes" "/sdcard/ROMs/snes/Super Metroid (Japan, USA).sfc" "/sdcard/ROMs/n64/Super Mario 64 (USA).z64" "/sdcard/ROMs/psx/Castlevania - Symphony of the Night (USA).chd" "/sdcard/ROMs/gba/Metroid Fusion (USA).gba" "/sdcard/ROMs/megadrive/Sonic The Hedgehog (USA, Europe).md"'
adbt shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
adbt logcat -c

if scene home; then
  shot 01_home
  key KEYCODE_DPAD_RIGHT dpad 2.5; shot 02_home_next
  key KEYCODE_BUTTON_Y gamepad 2.5; shot 03_home_options
fi

# Home's layouts, one SELECT press each
if scene home_layouts; then
  key KEYCODE_DPAD_RIGHT dpad 1.5
  key KEYCODE_BUTTON_SELECT gamepad 2.5; shot 20_home_covers
  key KEYCODE_BUTTON_SELECT gamepad 2.5; shot 21_home_wide
  key KEYCODE_BUTTON_SELECT gamepad 2.5; shot 22_home_grid_covers
  key KEYCODE_BUTTON_SELECT gamepad 2.5; shot 23_home_grid_tiles
  key KEYCODE_BUTTON_SELECT gamepad 2.5
  key KEYCODE_BUTTON_SELECT gamepad 2.5; shot 24_home_list
  key KEYCODE_BUTTON_SELECT gamepad 2.5; shot 25_home_tiles_again
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
  key KEYCODE_BUTTON_R1 gamepad 6; shot 09_cloud
  key KEYCODE_DPAD_RIGHT dpad 3; shot 09_cloud_next
  key KEYCODE_BUTTON_R1 gamepad 5; shot 10_android
  key KEYCODE_DPAD_RIGHT dpad 2; shot 11_android_next
  key KEYCODE_BUTTON_R1 gamepad 9; shot 12_retro
  key KEYCODE_DPAD_RIGHT dpad 6; shot 12_retro_next
  key KEYCODE_DPAD_RIGHT dpad 6; shot 12_retro_next2
fi

# Every app (the app drawer): the last card of the Android section's row
if scene drawer; then
  key KEYCODE_BUTTON_R1 gamepad 3
  key KEYCODE_BUTTON_R1 gamepad 3
  key KEYCODE_BUTTON_R1 gamepad 5
  for n in 1 2 3 4 5 6 7 8; do key KEYCODE_DPAD_RIGHT dpad 0.6; done
  sleep 1.5; shot 17_android_all_apps_card
  key KEYCODE_BUTTON_A gamepad 4; shot 17_drawer
  key KEYCODE_DPAD_RIGHT dpad 1; key KEYCODE_DPAD_DOWN dpad 2; shot 17_drawer_moved
  key KEYCODE_BUTTON_R1 gamepad 2.5; shot 18_drawer_games
  key KEYCODE_BUTTON_L1 gamepad 1.5
  key KEYCODE_BUTTON_R2 gamepad 1; shot 18_drawer_letter
  key KEYCODE_BUTTON_Y gamepad 2.5; shot 19_drawer_options
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
    # Where every view is (to check layouts)
    timeout 40 adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && timeout 20 adb pull /sdcard/ui.xml "$SHOTS/ui_android_$mode.xml" > /dev/null 2>&1
  fi
done

# The game page last: its videos can bring the emulator down
if scene game_page; then
  key KEYCODE_DPAD_RIGHT dpad 1.5
  key KEYCODE_DPAD_RIGHT dpad 2
  key KEYCODE_BUTTON_X gamepad 6; shot 04_game_page
  key KEYCODE_DPAD_DOWN dpad 3; shot 04_game_page_more
fi

# The real frosted glass (live blur), last: the emulator may not survive it
rm -rf /tmp/seedf; mkdir -p /tmp/seedf
printf 'pref\tbool\tpocket_live_glass\ttrue\n' > /tmp/seedf/seed.txt
if ensure_emulator; then
  push_seed /tmp/seedf
  if scene frost; then
    key KEYCODE_DPAD_RIGHT dpad 2
    shot 15_frost_home
    key KEYCODE_BUTTON_Y gamepad 3; shot 16_frost_options
  fi
fi

# The boot sequence (skipped everywhere else), recorded on video
rm -rf /tmp/seedb; mkdir -p /tmp/seedb
printf 'pref\tbool\tpocket_preview_boot\ttrue\npref\tbool\tpocket_live_glass\tfalse\n' > /tmp/seedb/seed.txt
if ensure_emulator; then
  push_seed /tmp/seedb
  adbt shell am force-stop $PKG
  adbt shell input keyevent KEYCODE_WAKEUP
  (timeout 20 adb shell screenrecord --time-limit 9 --bit-rate 12000000 /sdcard/boot.mp4 > /dev/null 2>&1 &)
  sleep 1
  timeout 30 adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/com.limelight.PcView > /dev/null
  sleep 11
  timeout 60 adb pull /sdcard/boot.mp4 "$SHOTS/boot.mp4" > /dev/null 2>&1 && log "boot video"
  shot 30_after_boot
fi

ensure_emulator && {
  T=30 adbt logcat -d -b crash > "$SHOTS/crash.txt" 2>&1 || true
  T=30 adbt logcat -d -v brief | grep -E "AndroidRuntime|$PKG|E/" | tail -n 400 > "$SHOTS/logcat.txt" 2>&1 || true
}
ls -la "$SHOTS"
exit 0
