@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
set "START_SCRIPT=%SCRIPT_DIR%start-demo.cmd"
set "STOP_SCRIPT=%SCRIPT_DIR%stop-demo.cmd"

if not exist "%START_SCRIPT%" (
  echo Missing start-demo.cmd
  exit /b 1
)

if not exist "%STOP_SCRIPT%" (
  echo Missing stop-demo.cmd
  exit /b 1
)

findstr /I /C:"powershell" "%START_SCRIPT%" >nul && (
  echo start-demo.cmd must not call PowerShell
  exit /b 1
)

findstr /I /C:"powershell" "%STOP_SCRIPT%" >nul && (
  echo stop-demo.cmd must not call PowerShell
  exit /b 1
)

findstr /I /C:"spring-boot.run.profiles=demo" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must enable the demo profile
  exit /b 1
)

findstr /I /C:"Admin#2026Demo" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must show the fixed demo password
  exit /b 1
)

findstr /I /C:"npm-dev-always-runs" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must run npm dev even when node_modules already exists
  exit /b 1
)

findstr /I /C:"DEMO_JDK21_BIN" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must allow an explicit JDK 21 bin path
  exit /b 1
)

findstr /I /C:"set \"JAVA_HOME=%%DEMO_JDK21_HOME%%\"" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must derive JAVA_HOME from the explicit JDK 21 bin path
  exit /b 1
)

findstr /I /C:"Usage: start-demo.cmd" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must print usage for the optional JDK 21 bin path
  exit /b 1
)

findstr /I /C:"taskkill /PID" "%STOP_SCRIPT%" >nul || (
  echo stop-demo.cmd must stop recorded PIDs
  exit /b 1
)

echo Demo script checks passed.
