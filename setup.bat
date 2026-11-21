@echo off
rem Setup for the EasyEDA to Freerouting bridge. Downloads the routing engine and, if needed, a Java runtime.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"
if errorlevel 1 (
  echo.
  echo Setup failed. See the message above.
)
pause
