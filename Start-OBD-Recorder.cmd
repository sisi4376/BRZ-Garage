@echo off
title OBD BLE Recorder
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\start_obd_recorder.ps1"
echo.
pause
