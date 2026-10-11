@echo off
title Static OBD BLE Simulator
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\start_obd_ble_simulator.ps1" -VerboseLog
echo.
pause
