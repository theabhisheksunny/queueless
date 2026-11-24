# ==============================================================================
# QueueLess — Staging Database Provisioning SQL Preparation Script
# Target: Supabase Staging (Project: queueless-staging, Ref: tlgibnjprelzpxfxycab)
# ==============================================================================
# This script securely prompts for the App and Migrator passwords (or accepts
# them securely), validates their security posture, and generates a ready-to-execute
# staging provisioning SQL script stored ONLY in the Git-ignored .secrets directory.
#
# Credentials are NEVER printed, logged, or tracked in source control.
# ==============================================================================

[CmdletBinding()]
param (
    [Parameter(Mandatory = $false)]
    $AppPassword,

    [Parameter(Mandatory = $false)]
    $MigratorPassword,

    [Parameter(Mandatory = $false)]
    [string]$TemplatePath,

    [Parameter(Mandatory = $false)]
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'

function ConvertTo-PlainText([System.Security.SecureString]$secureStr) {
    if ($null -eq $secureStr) { return $null }
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureStr)
    try {
        return [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "QueueLess — Staging Database Provisioning SQL Preparation" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "Project:   queueless-staging (tlgibnjprelzpxfxycab)" -ForegroundColor Gray
Write-Host "Engine:    PostgreSQL 16.4" -ForegroundColor Gray
Write-Host "Pooler:    aws-0-ap-southeast-1.pooler.supabase.com:5432" -ForegroundColor Gray
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host ""

# 1. Resolve Paths
$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
if (-not $TemplatePath) {
    $TemplatePath = Join-Path $repoRoot "scripts\provision_staging_db_roles.sql"
}
if (-not $OutputPath) {
    $secretsDir = Join-Path $repoRoot ".secrets"
    if (-not (Test-Path $secretsDir)) {
        New-Item -ItemType Directory -Path $secretsDir -Force | Out-Null
    }
    $OutputPath = Join-Path $secretsDir "staging_provisioning.sql"
}

if (-not (Test-Path $TemplatePath)) {
    throw "Template provisioning script not found at: $TemplatePath"
}

# 2. Verify Output Directory is Git-Ignored
$ignored = git check-ignore -q $OutputPath
if ($LASTEXITCODE -ne 0) {
    Write-Warning "Output path [$OutputPath] was not ignored by Git! Enforcing ignore rule..."
    $gitExclude = Join-Path $repoRoot ".git\info\exclude"
    if (Test-Path $gitExclude) {
        Add-Content -Path $gitExclude -Value "`n.secrets/`n*.secret.sql"
    }
    git check-ignore -q $OutputPath
    if ($LASTEXITCODE -ne 0) {
        throw "SECURITY ABORT: Output path [$OutputPath] is NOT ignored by Git! Halting to protect credentials."
    }
}

# 3. Securely Prompt for Passwords if not supplied
if ($AppPassword -is [System.Security.SecureString]) {
    $rawAppPass = ConvertTo-PlainText $AppPassword
} elseif ($AppPassword -is [string] -and -not [string]::IsNullOrWhiteSpace($AppPassword)) {
    $rawAppPass = $AppPassword
} else {
    Write-Host "Please enter the password for: queueless_app_staging (SPRING_DATASOURCE_PASSWORD)" -ForegroundColor Yellow
    Write-Host "(Input is masked; minimum 24 characters; must match Render configuration)" -ForegroundColor DarkGray
    $secApp = Read-Host -Prompt "App Password" -AsSecureString
    $rawAppPass = ConvertTo-PlainText $secApp
}

if ($MigratorPassword -is [System.Security.SecureString]) {
    $rawMigratorPass = ConvertTo-PlainText $MigratorPassword
} elseif ($MigratorPassword -is [string] -and -not [string]::IsNullOrWhiteSpace($MigratorPassword)) {
    $rawMigratorPass = $MigratorPassword
} else {
    Write-Host "Please enter the password for: queueless_migrator_staging (SPRING_FLYWAY_PASSWORD)" -ForegroundColor Yellow
    Write-Host "(Input is masked; minimum 24 characters; must match Render configuration)" -ForegroundColor DarkGray
    $secMig = Read-Host -Prompt "Migrator Password" -AsSecureString
    $rawMigratorPass = ConvertTo-PlainText $secMig
}

# 4. Strict Security & Posture Validations
if ([string]::IsNullOrWhiteSpace($rawAppPass) -or [string]::IsNullOrWhiteSpace($rawMigratorPass)) {
    throw "SECURITY VALIDATION FAILED: Both passwords are required."
}

if ($rawAppPass.Length -lt 24) {
    throw "SECURITY VALIDATION FAILED: App password must be at least 24 characters (actual length: $($rawAppPass.Length))."
}

if ($rawMigratorPass.Length -lt 24) {
    throw "SECURITY VALIDATION FAILED: Migrator password must be at least 24 characters (actual length: $($rawMigratorPass.Length))."
}

if ($rawAppPass -eq $rawMigratorPass) {
    throw "SECURITY VALIDATION FAILED: App password and Migrator password must be distinct and independent for least-privilege isolation."
}

if ($rawAppPass.Contains("'") -or $rawMigratorPass.Contains("'")) {
    throw "SECURITY VALIDATION FAILED: Passwords must not contain single quotes (') to prevent SQL literal syntax errors."
}

if ($rawAppPass.StartsWith("<STRONG_") -or $rawMigratorPass.StartsWith("<STRONG_") -or
    $rawAppPass.StartsWith("REPLACE_WITH_") -or $rawMigratorPass.StartsWith("REPLACE_WITH_")) {
    throw "SECURITY VALIDATION FAILED: Password cannot be a template placeholder string."
}

# 5. Populate Template
$templateContent = Get-Content -Path $TemplatePath -Raw -Encoding UTF8

if (-not $templateContent.Contains("<STRONG_STAGING_MIGRATOR_PASSWORD>") -or
    -not $templateContent.Contains("<STRONG_STAGING_APP_PASSWORD>")) {
    throw "SECURITY VALIDATION FAILED: Template file does not contain expected placeholder tokens."
}

$generatedSql = $templateContent.Replace("<STRONG_STAGING_MIGRATOR_PASSWORD>", $rawMigratorPass)
$generatedSql = $generatedSql.Replace("<STRONG_STAGING_APP_PASSWORD>", $rawAppPass)

# 6. Verify Complete Resolution
if ($generatedSql.Contains("<STRONG_STAGING_MIGRATOR_PASSWORD>") -or
    $generatedSql.Contains("<STRONG_STAGING_APP_PASSWORD>")) {
    throw "SECURITY VALIDATION FAILED: Unresolved placeholder tokens detected in generated SQL."
}

# Verify assignment lines do not contain placeholder patterns
if ($generatedSql -match "migrator_pass\s+TEXT\s*:=\s*'<STRONG_" -or
    $generatedSql -match "app_pass\s+TEXT\s*:=\s*'<STRONG_" -or
    $generatedSql -match "migrator_pass\s+TEXT\s*:=\s*'REPLACE_WITH_" -or
    $generatedSql -match "app_pass\s+TEXT\s*:=\s*'REPLACE_WITH_") {
    throw "SECURITY VALIDATION FAILED: Placeholder pattern still present in variable assignment."
}

# 7. Write to Local Git-Ignored File
[System.IO.File]::WriteAllText($OutputPath, $generatedSql, [System.Text.UTF8Encoding]::new($false))

# 8. Compute Checksums
$provHash = (Get-FileHash -Path $OutputPath -Algorithm SHA256).Hash
$rollbackPath = Join-Path $repoRoot "scripts\rollback_staging_db_roles.sql"
$rollHash = (Get-FileHash -Path $rollbackPath -Algorithm SHA256).Hash

# 9. Clean up memory
$rawAppPass = $null
$rawMigratorPass = $null
$generatedSql = $null
[System.GC]::Collect()

Write-Host ""
Write-Host "SUCCESS: Generated staging provisioning SQL written successfully!" -ForegroundColor Green
Write-Host ""
Write-Host "File Details:" -ForegroundColor Cyan
Write-Host "  Location:           $OutputPath" -ForegroundColor White
Write-Host "  Git Ignored:        YES (verified via git check-ignore)" -ForegroundColor Green
Write-Host "  Provisioning SHA-256: $provHash" -ForegroundColor Yellow
Write-Host "  Rollback SHA-256:     $rollHash" -ForegroundColor Yellow
Write-Host ""
Write-Host "Next Steps for Staging Execution:" -ForegroundColor Cyan
Write-Host "  1. Open the Supabase Dashboard for 'queueless-staging' (tlgibnjprelzpxfxycab)." -ForegroundColor Gray
Write-Host "  2. Navigate to SQL Editor -> New Query." -ForegroundColor Gray
Write-Host "  3. Open and copy the contents of:" -ForegroundColor Gray
Write-Host "     $OutputPath" -ForegroundColor White
Write-Host "  4. Paste into the Supabase SQL Editor and click 'Run'." -ForegroundColor Gray
Write-Host "  5. Verify that 'SUCCESS: Staging roles, least-privilege allowlist, and complete Canary integrity bootstrapped cleanly!' is returned." -ForegroundColor Gray
Write-Host "  6. Delete or archive the local file in .secrets once provisioning is verified." -ForegroundColor Gray
Write-Host "================================================================================" -ForegroundColor Cyan
