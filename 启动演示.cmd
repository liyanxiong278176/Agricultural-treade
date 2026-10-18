@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-Demo.ps1"
if errorlevel 1 (
  echo Startup failed. See .local logs and README.md.
  pause
  exit /b 1
)
start "" "http://localhost:4173"
