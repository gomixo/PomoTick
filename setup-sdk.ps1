# PomoTick Android SDK setup script
# Run: powershell -ExecutionPolicy Bypass -File .\setup-sdk.ps1

$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$sdk = 'C:\Users\xiaob\AppData\Local\Android\Sdk'

Write-Host '== PomoTick SDK Setup ==' -ForegroundColor Cyan
Write-Host ('SDK: {0}' -f $sdk)
Write-Host ''

# 1. local.properties
$localProps = Join-Path $root 'local.properties'
$content = "## This file is auto-generated. Do not commit.`nsdk.dir=$sdk`n"
[System.IO.File]::WriteAllText($localProps, $content, [System.Text.Encoding]::UTF8)
Write-Host ('[OK] Wrote: {0}' -f $localProps) -ForegroundColor Green

# 2. Check current platforms
Write-Host ''
Write-Host '== Current platforms ==' -ForegroundColor Cyan
Get-ChildItem (Join-Path $sdk 'platforms') -ErrorAction SilentlyContinue | ForEach-Object { Write-Host ('  - {0}' -f $_.Name) }

# 3. Install required components
$sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkmanager)) {
    Write-Host ('[FAIL] sdkmanager not found' -f $sdkmanager) -ForegroundColor Red
    exit 1
}

$needed = @('platforms;android-34', 'build-tools;34.0.0')
$toInstall = @()
foreach ($n in $needed) {
    $localPath = $n -replace ';', '\'
    if (Test-Path -LiteralPath (Join-Path $sdk $localPath)) {
        Write-Host ('[OK] Already installed: {0}' -f $n) -ForegroundColor Green
    } else {
        $toInstall += $n
        Write-Host ('[NEED] {0}' -f $n) -ForegroundColor Yellow
    }
}

if ($toInstall.Count -gt 0) {
    Write-Host ''
    Write-Host '== Installing ==' -ForegroundColor Cyan

    # Auto-accept licenses by piping many "y" lines (sdkmanager prompts multiple times)
    $yLines = ("y`n" * 30) -join ''
    $yBytes = [System.Text.Encoding]::UTF8.GetBytes($yLines)
    $yFile = Join-Path $env:TEMP 'pomotick-yes.txt'
    [System.IO.File]::WriteAllBytes($yFile, $yBytes)

    foreach ($pkg in $toInstall) {
        Write-Host ('Installing: {0}' -f $pkg) -ForegroundColor Cyan
        Write-Host '  (this may take a few minutes, downloading from dl.google.com)'
        try {
            $proc = Start-Process -FilePath $sdkmanager -ArgumentList $pkg -NoNewWindow -Wait -PassThru `
                -RedirectStandardInput $yFile -RedirectStandardOutput 'stdout.tmp' -RedirectStandardError 'stderr.tmp'
            Get-Content 'stdout.tmp' -ErrorAction SilentlyContinue | Select-Object -Last 10
            Get-Content 'stderr.tmp' -ErrorAction SilentlyContinue | Select-Object -Last 5
            Remove-Item 'stdout.tmp','stderr.tmp' -ErrorAction SilentlyContinue
        } catch {
            Write-Host ('[ERROR] {0}' -f $_.Exception.Message) -ForegroundColor Red
        }
    }
    Remove-Item $yFile -ErrorAction SilentlyContinue
}

# 4. Verify
Write-Host ''
Write-Host '== Verify ==' -ForegroundColor Cyan
$required = @('platforms\android-34', 'build-tools\34.0.0')
$allOk = $true
foreach ($r in $required) {
    $full = Join-Path $sdk $r
    if (Test-Path -LiteralPath $full) {
        Write-Host ('[OK] {0}' -f $r) -ForegroundColor Green
    } else {
        Write-Host ('[MISSING] {0}' -f $r) -ForegroundColor Red
        $allOk = $false
    }
}

if ($allOk) {
    Write-Host ''
    Write-Host '== Trying build ==' -ForegroundColor Cyan
    Push-Location $root
    try {
        & .\gradlew.bat :app:assembleDebug 2>&1 | Select-Object -Last 40
    } catch {
        Write-Host ('[FAIL] {0}' -f $_.Exception.Message) -ForegroundColor Red
    }
    Pop-Location
}
