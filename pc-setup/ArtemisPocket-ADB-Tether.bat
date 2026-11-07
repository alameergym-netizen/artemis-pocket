@echo off
setlocal EnableDelayedExpansion
title Artemis Pocket - turn on USB networking with ADB
cd /d "%~dp0"

rem ---- find adb ------------------------------------------------------------
set "ADB=adb"
if exist "%~dp0adb.exe" set "ADB=%~dp0adb.exe"
if exist "%~dp0platform-tools\adb.exe" set "ADB=%~dp0platform-tools\adb.exe"
"%ADB%" version >nul 2>&1
if errorlevel 1 (
  echo.
  echo  adb was not found.
  echo  1. Download "SDK Platform-Tools for Windows" from:
  echo     https://developer.android.com/tools/releases/platform-tools
  echo  2. Extract it and put the "platform-tools" folder next to this file.
  echo.
  pause
  exit /b 1
)

echo.
echo  On the Retroid: Developer options ^> USB debugging = ON
echo  Plug the cable in and press "Allow" on the handheld (tick "Always allow").
echo.
echo  Waiting for the handheld...
"%ADB%" wait-for-device
for /f "delims=" %%m in ('"%ADB%" shell getprop ro.product.model') do set MODEL=%%m
echo  Connected: !MODEL!

rem ---- try RNDIS first, then NCM --------------------------------------------
for %%F in (rndis ncm) do (
  echo.
  echo  Switching the USB port to network mode: %%F
  "%ADB%" shell svc usb setFunctions %%F
  timeout /t 4 /nobreak >nul
  "%ADB%" wait-for-device
  timeout /t 4 /nobreak >nul
  set "IP="
  for /f "tokens=4" %%a in ('"%ADB%" shell ip -4 -o addr show %%F0 2^>nul') do set "IP=%%a"
  if defined IP (
    echo  [OK] The handheld has a cable network address: !IP!
    echo.
    echo  Now open Artemis Pocket - it will find this PC on the cable.
    echo  Run this again every time you plug the cable in.
    echo.
    pause
    exit /b 0
  )
  echo  [..] %%F did not get an address
)

echo.
echo  [X] The handheld switched modes but Android did not start USB networking.
echo      This ROM has USB tethering disabled. Tell Claude - the USB bridge mode is the next step.
echo.
pause
exit /b 2
