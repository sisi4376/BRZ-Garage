@echo off
title OBD Recording Replay
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\start_obd_recorder.ps1" -Mode replay -Recording "%~1"
echo.
pause
