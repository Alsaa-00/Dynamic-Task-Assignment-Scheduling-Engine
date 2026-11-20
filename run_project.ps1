# TaskFlow Platform Launcher
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "       STARTING TASKFLOW PLATFORM (SPRING BOOT)" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# Ensure Port 8080 is free
Write-Host "[1/3] Ensuring Port 8080 is free..." -ForegroundColor Yellow
Stop-Process -Name java -Force -ErrorAction SilentlyContinue

# Verify Java 17
Write-Host "[2/3] Checking Java 17 environment..." -ForegroundColor Yellow
& "$env:JAVA_HOME\bin\java.exe" -version
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Java 17 was not found at $env:JAVA_HOME!" -ForegroundColor Red
    pause
    exit
}

Write-Host "`n[3/3] Starting Backend Server on port 8080..." -ForegroundColor Green
Write-Host "Once you see 'Started TaskEngineApplication', open: http://localhost:8080/`n" -ForegroundColor Green

Set-Location "$PSScriptRoot\backend"
mvn -o spring-boot:run
