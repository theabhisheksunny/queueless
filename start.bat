@echo off
setlocal enabledelayedexpansion
title QueueLess - Digital Token Register

echo ========================================================
echo          QueueLess - Digital Token Register
echo ========================================================
echo.

where java >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Java is not installed or not in your PATH.
    echo Please install Java 21 or later from https://adoptium.net/
    pause
    exit /b 1
)

set "JAR_PATH=%~dp0backend\target\queueless-backend-1.0.0.jar"

if not exist "%JAR_PATH%" (
    echo [INFO] Application JAR not found. Building with Maven...
    cd /d "%~dp0backend"
    call mvn clean package -DskipTests
    if not exist "%JAR_PATH%" (
        echo [ERROR] Failed to package application JAR. Ensure Maven is installed.
        pause
        exit /b 1
    )
    cd /d "%~dp0"
)

echo [OK] Launching QueueLess on http://localhost:8080 ...
echo.
echo ========================================================
echo   Demo Mobile Login: 9876543210 (PIN: 123456)
echo   Demo Email Login:  owner@queueless.com / password123
echo   Admin Portal:      http://localhost:8080/admin
echo   Admin Master Key:  admin-secret-queueless-2026
echo ========================================================
echo.

start "" "http://localhost:8080"
java -jar "%JAR_PATH%" --spring.profiles.active=local

pause
