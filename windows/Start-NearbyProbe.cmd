@echo off
setlocal
cd /d "%~dp0"
title FloatClip Bluetooth P0
"FloatClip.NearbyProbe.exe" --interactive
set "probe_exit=%ERRORLEVEL%"
if not "%probe_exit%"=="0" (
  echo.
  echo FloatClip could not start or exited with an error: %probe_exit%
  echo Keep this window open for diagnosis.
  pause
)
exit /b %probe_exit%
