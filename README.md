# Artemis Pocket

A console-style fork of [Artemis](https://github.com/ClassicOldSong/moonlight-android) (Moonlight for Apollo/Sunshine),
built for the **Retroid Pocket 6** and other Android handhelds.

## What's different

- **Console UI, fully controller-driven**: animated home screen with your PCs, a game library with box-art carousel
  and blurred hero art, button hints everywhere (A / B / X / Y / START / L1 / R1).
- **Streaming over the USB-C cable** — no Wi-Fi, no router, no internet. Plug the cable into the PC and the stream starts.
- **Menu button**: assign any button (e.g. M1/M2 on the back) to open settings, or the quick menu while streaming.
- **Back (and optionally Home) acts as the Xbox Guide button** while streaming.
- **Simple settings + Advanced**: the options you actually use, laid out for a controller; every Artemis option is still under *Advanced*.
- **Retroid Pocket 6 defaults**: 1080p / 120 FPS, HEVC, balanced frame pacing, 80 Mbps over USB-C, Back as Guide.
- English and Arabic UI.
- Removed: AI 3D (OpenCV/LiteRT + 17 MB model), on-screen controller settings, PiP/portrait options. arm64 only.

## Install

Download the APK from **[Releases → latest](../../releases/tag/latest)** on the handheld and install it.
It installs next to the official Artemis (`com.artemispocket`).

## USB-C streaming setup (once)

**Retroid Pocket (USB tethering is turned off in its Android) — USB bridge**
1. Handheld: Settings → About device → tap *Build number* 7 times, then Developer options → **USB debugging** on.
2. PC: install [Apollo](https://github.com/ClassicOldSong/Apollo/releases), download the [`pc-setup`](pc-setup) folder and
   double-click **`ArtemisPocket-USB-Bridge.bat`**. It downloads ADB if needed, can start with Windows, and needs no admin rights.
3. Plug the cable in, allow USB debugging, open Artemis Pocket. First time only, pair with the PIN in Apollo's web page.

The bridge uses `adb reverse` for Apollo's TCP ports and relays the stream's UDP packets (video/control/audio)
through a single ADB tunnel. Roughly 60 Mbps is comfortable.

**Handhelds with USB tethering**: turn on USB tethering and run `ArtemisPocket-USB-Setup.bat` on the PC once.

## Home button as Guide in every app

Android hands the Home key to the launcher before any app sees it, so this needs a system-level remapper:

- **Inside Artemis Pocket (streaming)**: Settings → Controls → *Home acts as Xbox Guide*, then enable the
  "Artemis Pocket: Home button and keyboard capture" accessibility service.
- **Everywhere (no root)**: [Key Mapper](https://github.com/keymapperorg/KeyMapper) with *Expert mode* — remap Home → key code `BUTTON_MODE`.
  Expert mode needs Wireless debugging to start (Wi-Fi on once after each reboot).
- **Everywhere (root)**: a Magisk module that maps the Home key to `BUTTON_MODE` in the controller's keylayout file.

## Building

Every push builds the APK with GitHub Actions (`.github/workflows/build.yml`) and publishes it as the `latest` release.
Locally: Android Studio + NDK 27, `git submodule update --init --recursive`, then `./gradlew assembleNonRoot_gameRelease`.

Based on Artemis by ClassicOldSong and Moonlight by the Moonlight team (GPLv3). Original README: [README.upstream.md](README.upstream.md).
