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

findstr /I /C:"-pl control-plane/bootstrap,execution-worker -am -DskipTests install" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must refresh reactor-built backend modules before launching services
  exit /b 1
)

findstr /I /C:"npm-dev-always-runs" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must run npm dev even when node_modules already exists
  exit /b 1
)

findstr /I /C:"call :freePort 8091" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must free Worker port 8091 before startup
  exit /b 1
)

findstr /I /C:"call :freePort 8080" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must free Control Plane port 8080 before startup
  exit /b 1
)

findstr /I /C:"taskkill /PID" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must terminate fixed-port listeners instead of switching ports
  exit /b 1
)

findstr /I /C:"8081" "%START_SCRIPT%" >nul && (
  echo start-demo.cmd must not use an alternate Control Plane port
  exit /b 1
)

findstr /I /C:"8092" "%START_SCRIPT%" >nul && (
  echo start-demo.cmd must not use an alternate Worker port
  exit /b 1
)

findstr /I /C:"taskkill /PID" "%STOP_SCRIPT%" >nul || (
  echo stop-demo.cmd must stop recorded PIDs
  exit /b 1
)

echo Demo script checks passed.
