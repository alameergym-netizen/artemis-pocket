POCKET NEXUS - PC TOOLS
=======================

Double-click  PocketNexus-PC.bat  and leave the window open (you can minimise it).
It can start with Windows (it asks once). It replaces "Artemis Pocket PC": close the old
window first (its Windows startup shortcut is swapped for the new one by itself).

What it does
  - USB-C streaming: plug the Retroid in with USB debugging on. Downloads ADB by itself.
  - PC power from the handheld: sleep, hibernate, restart, shut down, lock, sign out,
    turn the display off (in-stream menu > PC power, or the PC card's options).
  - PC game library: the handheld lists the games installed on this PC (Steam, Epic, GOG),
    starts them for you (through Apollo's Desktop when Apollo has no entry for them) and
    asks Steam to install games you own. Steam's install window is confirmed by itself
    when Windows lets it take the focus; otherwise confirm it once on the PC.
  - Microphone: the handheld's mic on this PC. Install VB-Audio Virtual Cable (free,
    https://vb-audio.com/Cable/) and pick "CABLE Output" as the microphone in your game
    or chat app. Turn it on in the in-stream menu > Microphone to PC.

Over Wi-Fi: stream once over the cable and the handheld links itself, or use the PC card's
options > "PC helper PIN" and type the PIN shown in this window.

Wi-Fi port: Pocket Nexus PC listens on port 47110 over Wi-Fi. To use another one (that port is
busy, or your network needs another), right-click its icon next to the clock > "Wi-Fi port",
or run  PocketNexus-PC.bat -WifiPort 47120 . On the handheld, set the same port in the PC's
options > "Pocket Nexus PC Wi-Fi port" (or stream once over the USB cable: it picks it up).

If you used the old "ArtemisPocket-USB-Bridge" window: close it, this one replaces it.

Handhelds that DO have USB tethering:
  -> other-handhelds\ArtemisPocket-USB-Setup.bat  (run once)
