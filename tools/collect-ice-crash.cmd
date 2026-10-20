@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0collect-ice-crash.ps1" -GameDirectory "%~dp0."
pause
