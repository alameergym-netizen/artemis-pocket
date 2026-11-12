#!/bin/bash
# Builds the preview seed (settings, Home items, art) for the emulator screenshots.
# $1: output folder; $2: file with the emulator's installed packages (one per line)
set -u
out=$1
pkgs=$2
mkdir -p "$out"
seed="$out/seed.txt"
: > "$seed"
T=$'\t'
pref() { echo "pref${T}$1${T}$2${T}$3" >> "$seed"; }
pref bool pocket_first_run_done_v1 true
pref int pocket_defaults_version 4
pref bool pocket_onboarding_pending false
for t in pcs games stream; do pref bool "pocket_tip_seen_$t" true; done
pref string pocket_music off
# The emulator's video decoding crashes it: trailers stay still
pref bool pocket_trailer_autoplay false
pref bool pocket_preview_no_boot true
if [ -n "${VIEW_MODE:-}" ]; then pref string pocket_view_mode "$VIEW_MODE"; fi

# Real games' library art (public Steam CDN), shown on emulator apps so every layout has art in it
games=("1145360:Hades" "413150:Stardew Valley" "1091500:Cyberpunk 2077" "367520:Hollow Knight" "1245620:ELDEN RING" "504230:Celeste")
cands=(com.android.settings com.android.camera2 com.android.contacts com.android.calendar com.android.deskclock com.android.gallery3d com.android.music com.android.messaging com.android.dialer com.android.documentsui com.android.chrome com.google.android.youtube)
avail=()
for p in "${cands[@]}"; do grep -qx "$p" "$pkgs" && avail+=("$p"); done
fetch() { # id file dest
  for base in "https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps" "https://cdn.cloudflare.steamstatic.com/steam/apps" "https://steamcdn-a.akamaihd.net/steam/apps"; do
    if curl -sfL --max-time 30 "$base/$1/$2" -o "$3" && [ -s "$3" ]; then return 0; fi
  done
  rm -f "$3"; return 1
}
i=0
for g in "${games[@]}"; do
  [ $i -lt ${#avail[@]} ] || break
  id=${g%%:*}; name=${g#*:}; pkg=${avail[$i]}
  echo "pin${T}android${T}${name}${T}${pkg}${T}${T}${T}0" >> "$seed"
  fetch "$id" library_600x900.jpg "$out/$id-cover.jpg" && echo "art${T}${pkg}${T}0${T}$id-cover.jpg" >> "$seed"
  fetch "$id" library_hero.jpg "$out/$id-hero.jpg" && echo "art${T}${pkg}${T}1${T}$id-hero.jpg" >> "$seed"
  fetch "$id" logo.png "$out/$id-logo.png" && echo "art${T}${pkg}${T}2${T}$id-logo.png" >> "$seed"
  fetch "$id" header.jpg "$out/$id-wide.jpg" && echo "art${T}${pkg}${T}3${T}$id-wide.jpg" >> "$seed"
  # A square tile for every other game (the rest show their app icon)
  if [ $((i % 2)) -eq 0 ] && [ -f "$out/$id-cover.jpg" ]; then
    convert "$out/$id-cover.jpg" -gravity center -crop 600x600+0+0 +repage "$out/$id-square.jpg" 2>/dev/null \
      && echo "art${T}${pkg}${T}5${T}$id-square.jpg" >> "$seed"
  fi
  i=$((i+1))
done
# One more app with no art at all
[ $i -lt ${#avail[@]} ] && echo "pin${T}android${T}Phone${T}${avail[$i]}${T}${T}${T}0" >> "$seed"
# The Android section holds the same apps
used=$(grep -P "^pin\tandroid\t" "$seed" | cut -f4 | paste -sd, -)
[ -n "$used" ] && { pref set pocket_android_library "$used"; pref bool pocket_android_library_set true; }
cat "$seed"
ls -la "$out"
