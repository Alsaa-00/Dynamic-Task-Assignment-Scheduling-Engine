@echo off
echo ========================================================
echo        STARTING TASKFLOW PLATFORM (SPRING BOOT)
echo ========================================================

set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [1/3] Checking Java 17 environment...
"%JAVA_HOME%\bin\java.exe" -version
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Java 17 not found at %JAVA_HOME%
    pause
    exit /b 1
)

echo [2/3] Ensuring Port 8080 & 8081 are free...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-NetTCPConnection -LocalPort 8080, 8081 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }" >nul 2>&1
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080 :8081" ^| find "LISTENING"') do taskkill /F /PID %%a >nul 2>&1

echo.
echo [3/3] Starting Backend Server on port 8080...
echo Once you see "Started TaskEngineApplication", open: http://localhost:8080/
echo.

if exist "%~dp0backend" (
    cd /d "%~dp0backend"
) else (
    cd /d "%~dp0"
)
call mvn -o spring-boot:run
pause
