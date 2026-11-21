@echo off
rem Routes an EasyEDA Standard PCB file. Drop the file on this script, or run: route.bat board.json
rem The result is board.routed.json next to the input file. The input file does not change.
setlocal
set "HERE=%~dp0"
if "%~1"=="" (
  echo Use: route.bat board.json [result.json] [--time seconds] [--rules rules.json]
  echo You can also drop the board file on route.bat.
  pause
  exit /b 64
)
if not exist "%HERE%engine\freerouting-2.5.0.jar" (
  echo The routing engine is missing. Run setup.bat first.
  pause
  exit /b 1
)
set "JAVA=java"
if exist "%HERE%runtime\bin\java.exe" set "JAVA=%HERE%runtime\bin\java.exe"
"%JAVA%" -jar "%HERE%bridge.jar" route %*
echo.
pause
