@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
set "START_SCRIPT=%SCRIPT_DIR%start-demo.cmd"
set "STOP_SCRIPT=%SCRIPT_DIR%stop-demo.cmd"
set "START_BACKEND_JARS_SCRIPT=%SCRIPT_DIR%start-backend-jars.cmd"

if not exist "%START_SCRIPT%" (
  echo Missing start-demo.cmd
  exit /b 1
)

if not exist "%STOP_SCRIPT%" (
  echo Missing stop-demo.cmd
  exit /b 1
)

if not exist "%START_BACKEND_JARS_SCRIPT%" (
  echo Missing start-backend-jars.cmd
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

findstr /I /C:"powershell" "%START_BACKEND_JARS_SCRIPT%" >nul && (
  echo start-backend-jars.cmd must not call PowerShell
  exit /b 1
)

findstr /I /C:"spring-boot.run.profiles=demo" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must enable the demo profile
  exit /b 1
)

findstr /I /C:"OPS_AGENT_DEMO_ADMIN_PASSWORD" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must require the injected demo admin password
  exit /b 1
)

findstr /I /C:"OPS_AGENT_SQL_DML_RECEIPT_HMAC_SECRET" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must require the DML receipt signing secret
  exit /b 1
)

findstr /I /C:"OPS_AGENT_SKILL_REGISTRY_SIGNING_SECRET" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must require the Skill registry signing secret
  exit /b 1
)

findstr /I /C:"Starting Worker on 127.0.0.1:8091 with demo profile" "%START_SCRIPT%" >nul || (
  echo start-demo.cmd must enable the demo profile for the Worker
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

findstr /I /C:"control-plane-bootstrap-*.jar" "%START_BACKEND_JARS_SCRIPT%" >nul || (
  echo start-backend-jars.cmd must locate the copied control plane jar
  exit /b 1
)

findstr /I /C:"execution-worker-*.jar" "%START_BACKEND_JARS_SCRIPT%" >nul || (
  echo start-backend-jars.cmd must locate the copied execution worker jar
  exit /b 1
)

findstr /I /C:"set \"JAR_DIR=%%SCRIPT_DIR%%\"" "%START_BACKEND_JARS_SCRIPT%" >nul || (
  echo start-backend-jars.cmd must default to jars copied next to the script
  exit /b 1
)

findstr /I /C:"Usage: start-backend-jars.cmd [JAR_DIR] [JDK21_BIN_PATH]" "%START_BACKEND_JARS_SCRIPT%" >nul || (
  echo start-backend-jars.cmd must document copied jar directory usage
  exit /b 1
)

findstr /I /C:"backend\control-plane\bootstrap\target" "%START_BACKEND_JARS_SCRIPT%" >nul && (
  echo start-backend-jars.cmd must not assume repository target paths
  exit /b 1
)

findstr /I /C:"backend\execution-worker\target" "%START_BACKEND_JARS_SCRIPT%" >nul && (
  echo start-backend-jars.cmd must not assume repository target paths
  exit /b 1
)

findstr /I /C:"java %%OPS_AGENT_JAVA_OPTS%% -jar" "%START_BACKEND_JARS_SCRIPT%" >nul || (
  echo start-backend-jars.cmd must launch jars with java -jar
  exit /b 1
)

findstr /I /C:"--spring.profiles.active=demo" "%START_BACKEND_JARS_SCRIPT%" >nul || (
  echo start-backend-jars.cmd must enable the demo profile for control plane
  exit /b 1
)

findstr /I /C:"Starting Worker on 127.0.0.1:8091 with demo profile" "%START_BACKEND_JARS_SCRIPT%" >nul || (
  echo start-backend-jars.cmd must enable the demo profile for the Worker
  exit /b 1
)

findstr /I /C:"OPS_AGENT_DEMO_ADMIN_PASSWORD" "%START_BACKEND_JARS_SCRIPT%" >nul || (
  echo start-backend-jars.cmd must require the injected demo admin password
  exit /b 1
)

findstr /I /C:"OPS_AGENT_SQL_DML_RECEIPT_HMAC_SECRET" "%START_BACKEND_JARS_SCRIPT%" >nul || (
  echo start-backend-jars.cmd must require the DML receipt signing secret
  exit /b 1
)

findstr /I /C:"OPS_AGENT_SKILL_REGISTRY_SIGNING_SECRET" "%START_BACKEND_JARS_SCRIPT%" >nul || (
  echo start-backend-jars.cmd must require the Skill registry signing secret
  exit /b 1
)

findstr /I /C:"mvnw.cmd" "%START_BACKEND_JARS_SCRIPT%" >nul && (
  echo start-backend-jars.cmd must not build with Maven Wrapper
  exit /b 1
)

findstr /I /C:"mvn -f" "%START_BACKEND_JARS_SCRIPT%" >nul && (
  echo start-backend-jars.cmd must not build with Maven
  exit /b 1
)

findstr /I /C:"npm" "%START_BACKEND_JARS_SCRIPT%" >nul && (
  echo start-backend-jars.cmd must not start or build frontend dev tooling
  exit /b 1
)

echo Demo script checks passed.
