# ========================================================
# QueueLess - One-Click Application Startup Script
# ========================================================
param (
    [switch]$Build,
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
$JarPath = Join-Path $ProjectRoot "backend\target\queueless-backend-1.0.0.jar"
$FrontendStatic = Join-Path $ProjectRoot "backend\src\main\resources\static\index.html"

Write-Host "========================================================" -ForegroundColor Magenta
Write-Host "         QueueLess - Digital Token Register            " -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Magenta
Write-Host ""

# 1. Check for Java
$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCmd) {
    Write-Host "[✗] Java is not installed or not in your PATH." -ForegroundColor Red
    Write-Host "    Please install Java 21 or later from https://adoptium.net/" -ForegroundColor Yellow
    Exit 1
}
Write-Host "[✓] Java runtime detected: $($javaCmd.Source)" -ForegroundColor Green

# 2. Check if JAR exists or if rebuild requested
$NeedsBuild = $Build -or (-not (Test-Path $JarPath))

if ($NeedsBuild) {
    Write-Host "[i] Application JAR not found or build requested. Packaging..." -ForegroundColor Yellow

    # Verify frontend static build exists inside backend
    if (-not (Test-Path $FrontendStatic)) {
        Write-Host "[i] Building frontend assets..." -ForegroundColor Yellow
        Push-Location (Join-Path $ProjectRoot "frontend")
        try {
            npm install
            npm run build
            # Copy to backend static
            $destStatic = Join-Path $ProjectRoot "backend\src\main\resources\static"
            Copy-Item -Path "dist\*" -Destination $destStatic -Recurse -Force
            Write-Host "[✓] Frontend built and synced to backend static." -ForegroundColor Green
        } catch {
            Write-Host "[!] Failed to build frontend. Ensure Node.js is installed." -ForegroundColor Red
        } finally {
            Pop-Location
        }
    }

    # Build backend JAR
    Push-Location (Join-Path $ProjectRoot "backend")
    try {
        Write-Host "[i] Compiling backend with Maven..." -ForegroundColor Yellow
        mvn clean package -DskipTests
        if (Test-Path $JarPath) {
            Write-Host "[✓] Backend JAR created successfully." -ForegroundColor Green
        } else {
            Write-Host "[✗] JAR build failed." -ForegroundColor Red
            Exit 1
        }
    } catch {
        Write-Host "[✗] Maven packaging failed. Ensure Apache Maven is installed." -ForegroundColor Red
        Exit 1
    } finally {
        Pop-Location
    }
} else {
    Write-Host "[✓] Found existing application JAR: $JarPath" -ForegroundColor Green
}

# 3. Print Quick Access Information
Write-Host ""
Write-Host "--------------------------------------------------------" -ForegroundColor DarkGray
Write-Host "🚀 Launching QueueLess Application on http://localhost:8080" -ForegroundColor Cyan
Write-Host ""
Write-Host "  👤 Demo Shop Owner Login:" -ForegroundColor White
Write-Host "     Email:    owner@queueless.com" -ForegroundColor Yellow
Write-Host "     Password: password123" -ForegroundColor Yellow
Write-Host "     (Or click 1-Click Demo Login on the sign-in page)" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  🔑 Admin Settings:" -ForegroundColor White
Write-Host "     URL:      http://localhost:8080/admin" -ForegroundColor Yellow
Write-Host "     Key:      admin-secret-queueless-2026" -ForegroundColor Yellow
Write-Host "--------------------------------------------------------" -ForegroundColor DarkGray
Write-Host ""

# 4. Check if port 8080 is already in use
$portActive = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($portActive) {
    Write-Host "[!] Port 8080 is already active! QueueLess is already running." -ForegroundColor Green
    Write-Host "    Access it directly at: http://localhost:8080" -ForegroundColor Cyan
    if (-not $NoBrowser) {
        try { Start-Process "http://localhost:8080" } catch {}
    }
    Exit 0
}

# 5. Open browser in background after startup
if (-not $NoBrowser) {
    Start-Job -ScriptBlock {
        Start-Sleep -Seconds 3
        try {
            Start-Process "http://localhost:8080"
        } catch {}
    } | Out-Null
}

# 6. Start application
Push-Location (Join-Path $ProjectRoot "backend")
try {
    java -jar $JarPath
} finally {
    Pop-Location
}
