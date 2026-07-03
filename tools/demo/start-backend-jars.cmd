@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "REPO_ROOT=%%~fI"
set "BACKEND_DIR=%REPO_ROOT%\backend"
set "DEMO_DIR=%REPO_ROOT%\.demo"
set "LOG_DIR=%DEMO_DIR%\logs"
set "PID_DIR=%DEMO_DIR%\pids"

set "WORKER_TITLE=OpsAgent Demo Worker"
set "CONTROL_TITLE=OpsAgent Demo Control Plane"

set "WORKER_LOG=%LOG_DIR%\worker.log"
set "CONTROL_LOG=%LOG_DIR%\control-plane.log"
set "LAUNCHER_LOG=%LOG_DIR%\backend-jars-launcher.log"

set "CONTROL_PLANE_JAR_GLOB=control-plane\bootstrap\target\control-plane-bootstrap-*.jar"
set "WORKER_JAR_GLOB=execution-worker\target\execution-worker-*.jar"

echo ============================================================
echo Ops Agent Backend JAR Launcher
echo ============================================================
echo Usage: start-backend-jars.cmd [JDK21_BIN_PATH]
echo.

cd /d "%REPO_ROOT%" || (
  echo Cannot enter repository root: %REPO_ROOT%
  pause
  exit /b 1
)

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%PID_DIR%" mkdir "%PID_DIR%"

echo [%date% %time%] Starting backend JAR launcher > "%LAUNCHER_LOG%"

if not "%~1"=="" set "DEMO_JDK21_BIN=%~1"

if defined DEMO_JDK21_BIN (
  call :configureJdk21Bin "%DEMO_JDK21_BIN%" || goto :fail
) else (
  call :requireCommand java.exe "Java 21 is required. Install Java 21 and make sure java.exe is on PATH, or run start-backend-jars.cmd with the JDK 21 bin path." || goto :fail
)

call :findSingleJar "%BACKEND_DIR%\control-plane\bootstrap\target" "control-plane-bootstrap-*.jar" CONTROL_PLANE_JAR "control plane" || goto :fail
call :findSingleJar "%BACKEND_DIR%\execution-worker\target" "execution-worker-*.jar" WORKER_JAR "execution worker" || goto :fail

call :freePort 8091 "Worker" || goto :fail
call :freePort 8080 "Control Plane" || goto :fail

echo Using JARs:
echo   Control Plane: %CONTROL_PLANE_JAR%
echo   Worker:        %WORKER_JAR%
echo.
echo Logs:
echo   Worker:        %WORKER_LOG%
echo   Control Plane: %CONTROL_LOG%
echo.

echo Starting Worker on 127.0.0.1:8091 from built JAR...
start "%WORKER_TITLE%" /D "%BACKEND_DIR%" cmd /k "title %WORKER_TITLE% && java %OPS_AGENT_JAVA_OPTS% -jar ^"%WORKER_JAR%^" ^>^> ^"%WORKER_LOG%^" 2^>^&1"
timeout /t 2 /nobreak >nul
call :recordWindowPid worker "%WORKER_TITLE%"

echo Starting Control Plane on 127.0.0.1:8080 with demo profile from built JAR...
start "%CONTROL_TITLE%" /D "%BACKEND_DIR%" cmd /k "title %CONTROL_TITLE% && java %OPS_AGENT_JAVA_OPTS% -jar ^"%CONTROL_PLANE_JAR%^" --spring.profiles.active=demo ^>^> ^"%CONTROL_LOG%^" 2^>^&1"
timeout /t 2 /nobreak >nul
call :recordWindowPid control-plane "%CONTROL_TITLE%"

echo.
call :waitForUrl "http://127.0.0.1:8080/actuator/health" "Control Plane" 60
set "CONTROL_READY=%ERRORLEVEL%"

echo.
echo ============================================================
if "%CONTROL_READY%"=="0" (
  echo Backend services are ready.
) else (
  echo Backend services were started, but health checks did not fully pass.
  echo Review logs before presenting the demo.
)
echo ============================================================
echo URL:      http://127.0.0.1:8080
echo Username: admin
echo Password: Admin#2026Demo
echo.
echo To stop the backend demo, double-click:
echo   tools\demo\stop-demo.cmd
echo.

start "" "http://127.0.0.1:8080"
pause
exit /b 0

:configureJdk21Bin
set "DEMO_JDK21_BIN=%~1"
if not exist "%DEMO_JDK21_BIN%\java.exe" (
  echo JDK 21 bin path is invalid: %DEMO_JDK21_BIN%
  echo Expected java.exe at: %DEMO_JDK21_BIN%\java.exe
  echo Usage: start-backend-jars.cmd "C:\path\to\jdk-21\bin"
  exit /b 1
)
for %%I in ("%DEMO_JDK21_BIN%\..") do set "DEMO_JDK21_HOME=%%~fI"
set "JAVA_HOME=%DEMO_JDK21_HOME%"
set "PATH=%DEMO_JDK21_BIN%;%PATH%"
echo Using JDK 21 bin: %DEMO_JDK21_BIN%
echo JAVA_HOME: %JAVA_HOME%
exit /b 0

:requireCommand
where %~1 >nul 2>nul
if errorlevel 1 (
  echo %~2
  exit /b 1
)
exit /b 0

:findSingleJar
set "JAR_DIR=%~1"
set "JAR_PATTERN=%~2"
set "OUT_VAR=%~3"
set "LABEL=%~4"
set "MATCH_COUNT=0"
set "MATCH_PATH="

if not exist "%JAR_DIR%" (
  echo Missing %LABEL% target directory: %JAR_DIR%
  echo Build the backend JARs first, then rerun this script.
  exit /b 1
)

for /f "delims=" %%J in ('dir /b "%JAR_DIR%\%JAR_PATTERN%" 2^>nul') do (
  set /a MATCH_COUNT+=1
  set "MATCH_PATH=%JAR_DIR%\%%J"
)

if "!MATCH_COUNT!"=="0" (
  echo Missing %LABEL% JAR matching %JAR_PATTERN% in %JAR_DIR%
  echo Build the backend JARs first, then rerun this script.
  exit /b 1
)

if not "!MATCH_COUNT!"=="1" (
  echo Expected exactly one %LABEL% JAR matching %JAR_PATTERN% in %JAR_DIR%, found !MATCH_COUNT!.
  echo Clean old target JARs or rebuild, then rerun this script.
  exit /b 1
)

set "%OUT_VAR%=!MATCH_PATH!"
exit /b 0

:freePort
set "PORT=%~1"
set "NAME=%~2"
set "FOUND_LISTENER="
for /f "tokens=5" %%P in ('netstat -ano -p tcp ^| findstr /R /C:":%PORT% .*LISTENING"') do (
  set "FOUND_LISTENER=1"
  echo Port %PORT% for %NAME% is already in use by PID %%P.
  echo Terminating PID %%P before restarting %NAME% on fixed port %PORT%...
  taskkill /PID %%P /T /F >nul 2>nul
  if errorlevel 1 (
    echo PID %%P was already stopped or could not be stopped.
  )
)
if defined FOUND_LISTENER (
  timeout /t 2 /nobreak >nul
  netstat -ano -p tcp | findstr /R /C:":%PORT% .*LISTENING" >nul
  if not errorlevel 1 (
    echo Port %PORT% for %NAME% is still in use after termination.
    exit /b 1
  )
)
exit /b 0

:recordWindowPid
set "SERVICE=%~1"
set "TITLE=%~2"
set "PID_FILE=%PID_DIR%\%SERVICE%.pid"
if exist "%PID_FILE%" del "%PID_FILE%"
for /f "tokens=2 delims=," %%P in ('tasklist /v /fo csv ^| findstr /C:"%TITLE%"') do (
  set "PID=%%~P"
  if defined PID echo !PID!>>"%PID_FILE%"
)
if not exist "%PID_FILE%" (
  echo Could not record PID for %SERVICE%. stop-demo.cmd will use the fixed window title fallback.
)
exit /b 0

:waitForUrl
set "URL=%~1"
set "NAME=%~2"
set "MAX_TRIES=%~3"
set /a "TRIES=0"

where curl.exe >nul 2>nul
if errorlevel 1 (
  echo curl.exe was not found. Waiting 30 seconds for %NAME%; verify manually if needed.
  timeout /t 30 /nobreak >nul
  exit /b 0
)

:waitLoop
curl.exe -fsS "%URL%" >nul 2>nul
if not errorlevel 1 (
  echo %NAME% is reachable.
  exit /b 0
)
set /a "TRIES+=1"
if !TRIES! geq %MAX_TRIES% (
  echo Timed out waiting for %NAME% at %URL%.
  exit /b 1
)
timeout /t 2 /nobreak >nul
goto :waitLoop

:fail
echo.
echo Backend JAR startup failed. Review %LAUNCHER_LOG% and .demo\logs for details.
pause
exit /b 1
