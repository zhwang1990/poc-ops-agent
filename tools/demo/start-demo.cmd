@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "REPO_ROOT=%%~fI"
set "BACKEND_DIR=%REPO_ROOT%\backend"
set "FRONTEND_DIR=%REPO_ROOT%\frontend\operator-console"
set "DEMO_DIR=%REPO_ROOT%\.demo"
set "LOG_DIR=%DEMO_DIR%\logs"
set "PID_DIR=%DEMO_DIR%\pids"

set "WORKER_TITLE=OpsAgent Demo Worker"
set "CONTROL_TITLE=OpsAgent Demo Control Plane"
set "CONSOLE_TITLE=OpsAgent Demo Console"

set "WORKER_LOG=%LOG_DIR%\worker.log"
set "CONTROL_LOG=%LOG_DIR%\control-plane.log"
set "CONSOLE_LOG=%LOG_DIR%\operator-console.log"
set "LAUNCHER_LOG=%LOG_DIR%\launcher.log"

echo ============================================================
echo Ops Agent Windows Demo Launcher
echo ============================================================
echo Usage: start-demo.cmd [JDK21_BIN_PATH]
echo.

cd /d "%REPO_ROOT%" || (
  echo Cannot enter repository root: %REPO_ROOT%
  pause
  exit /b 1
)

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%PID_DIR%" mkdir "%PID_DIR%"

echo [%date% %time%] Starting demo launcher > "%LAUNCHER_LOG%"

if not "%~1"=="" set "DEMO_JDK21_BIN=%~1"

if defined DEMO_JDK21_BIN (
  call :configureJdk21Bin "%DEMO_JDK21_BIN%" || goto :fail
) else (
  call :requireCommand java.exe "Java 21 is required. Install Java 21 and make sure java.exe is on PATH, or run start-demo.cmd with the JDK 21 bin path." || goto :fail
)
call :requireCommand npm.cmd "Node.js 20+ and npm are required. Install Node.js and make sure npm.cmd is on PATH." || goto :fail

if not exist "%BACKEND_DIR%\mvnw.cmd" (
  echo Missing Maven Wrapper: %BACKEND_DIR%\mvnw.cmd
  goto :fail
)

call :checkPort 8091 "Worker" || goto :fail
call :checkPort 8080 "Control Plane" || goto :fail
call :checkPort 5173 "Operator Console" || goto :fail

echo Logs:
echo   Worker:          %WORKER_LOG%
echo   Control Plane:   %CONTROL_LOG%
echo   Operator Console:%CONSOLE_LOG%
echo.

echo Starting Worker on 127.0.0.1:8091...
start "%WORKER_TITLE%" /D "%BACKEND_DIR%" cmd /k "title %WORKER_TITLE% && call mvnw.cmd -f execution-worker\pom.xml spring-boot:run ^>^> ^"%WORKER_LOG%^" 2^>^&1"
timeout /t 2 /nobreak >nul
call :recordWindowPid worker "%WORKER_TITLE%"

echo Starting Control Plane on 127.0.0.1:8080 with demo profile...
start "%CONTROL_TITLE%" /D "%BACKEND_DIR%" cmd /k "title %CONTROL_TITLE% && call mvnw.cmd -f control-plane\bootstrap\pom.xml spring-boot:run -Dspring-boot.run.profiles=demo ^>^> ^"%CONTROL_LOG%^" 2^>^&1"
timeout /t 2 /nobreak >nul
call :recordWindowPid control-plane "%CONTROL_TITLE%"

echo Starting Operator Console on 127.0.0.1:5173...
rem npm-dev-always-runs
start "%CONSOLE_TITLE%" /D "%FRONTEND_DIR%" cmd /k "title %CONSOLE_TITLE% && ((if not exist node_modules call npm install) ^&^& call npm run dev -- --host 127.0.0.1 --port 5173) ^>^> ^"%CONSOLE_LOG%^" 2^>^&1"
timeout /t 2 /nobreak >nul
call :recordWindowPid operator-console "%CONSOLE_TITLE%"

echo.
call :waitForUrl "http://127.0.0.1:8080/actuator/health" "Control Plane" 60
set "CONTROL_READY=%ERRORLEVEL%"
call :waitForUrl "http://127.0.0.1:5173/" "Operator Console" 60
set "CONSOLE_READY=%ERRORLEVEL%"

echo.
echo ============================================================
if "%CONTROL_READY%"=="0" if "%CONSOLE_READY%"=="0" (
  echo Demo services are ready.
) else (
  echo Demo services were started, but health checks did not fully pass.
  echo Review logs before presenting the demo.
)
echo ============================================================
echo URL:      http://127.0.0.1:5173
echo Username: admin
echo Password: Admin#2026Demo
echo.
echo SQL Workbench connection: h2-local-test
echo Sample SQL:
echo   select ORDER_ID, STATUS, AMOUNT from PUBLIC.ORDERS order by ORDER_ID
echo.
echo To stop the demo, double-click:
echo   tools\demo\stop-demo.cmd
echo.

start "" "http://127.0.0.1:5173"
pause
exit /b 0

:configureJdk21Bin
set "DEMO_JDK21_BIN=%~1"
if not exist "%DEMO_JDK21_BIN%\java.exe" (
  echo JDK 21 bin path is invalid: %DEMO_JDK21_BIN%
  echo Expected java.exe at: %DEMO_JDK21_BIN%\java.exe
  echo Usage: start-demo.cmd "C:\path\to\jdk-21\bin"
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

:checkPort
set "PORT=%~1"
set "NAME=%~2"
netstat -ano -p tcp | findstr /R /C:":%PORT% .*LISTENING" >nul
if not errorlevel 1 (
  echo Port %PORT% for %NAME% is already in use.
  echo Stop the existing process or run tools\demo\stop-demo.cmd if it was started by this launcher.
  exit /b 1
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
echo Demo startup failed. Review %LAUNCHER_LOG% and .demo\logs for details.
pause
exit /b 1
