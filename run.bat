@echo off
rem Starts the EasyEDA to Freerouting bridge. Keep the window open while you use the auto router.
setlocal
cd /d "%~dp0"
if not exist "engine\freerouting-2.5.0.jar" (
  echo The routing engine is missing. Run setup.bat first.
  pause
  exit /b 1
)
set "JAVA=java"
if exist "runtime\bin\java.exe" set "JAVA=runtime\bin\java.exe"
"%JAVA%" -jar bridge.jar
echo.
echo The router has stopped.
pause
