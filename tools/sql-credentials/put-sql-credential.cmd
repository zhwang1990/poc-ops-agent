@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
set "TOOL_SOURCE=%SCRIPT_DIR%SqlCredentialKeyStoreCli.java"

if defined OPS_AGENT_JDK_BIN (
  if not exist "%OPS_AGENT_JDK_BIN%\java.exe" (
    echo OPS_AGENT_JDK_BIN is set but java.exe was not found: %OPS_AGENT_JDK_BIN%
    exit /b 1
  )
  set "PATH=%OPS_AGENT_JDK_BIN%;%PATH%"
)

where java.exe >nul 2>nul
if errorlevel 1 (
  echo java.exe was not found. Install JDK 21 or set OPS_AGENT_JDK_BIN to the JDK bin directory.
  exit /b 1
)

if not exist "%TOOL_SOURCE%" (
  echo Missing SQL credential tool source: %TOOL_SOURCE%
  exit /b 1
)

java.exe "%TOOL_SOURCE%" %*
exit /b %ERRORLEVEL%
