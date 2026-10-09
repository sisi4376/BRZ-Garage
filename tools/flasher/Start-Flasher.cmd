@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -STA -WindowStyle Hidden -ExecutionPolicy Bypass -File "%~dp0Flasher.ps1"
if errorlevel 1 (
  echo Unable to start BRZ Garage Flasher. Extract the entire ZIP before running.
  pause
)
