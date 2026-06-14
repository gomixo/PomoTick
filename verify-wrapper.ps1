# PomoTick Gradle Wrapper verification script
# Run: powershell -ExecutionPolicy Bypass -File .\verify-wrapper.ps1

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

Write-Host '== PomoTick Wrapper Verification ==' -ForegroundColor Cyan
Write-Host ('Project root: {0}' -f $root)
Write-Host ''

# 1. Check required files
$required = @(
    @{ Path = (Join-Path $root 'gradlew'); Desc = 'POSIX launcher' },
    @{ Path = (Join-Path $root 'gradlew.bat'); Desc = 'Windows launcher' },
    @{ Path = (Join-Path $root 'gradle\wrapper\gradle-wrapper.properties'); Desc = 'Wrapper config' },
    @{ Path = (Join-Path $root 'gradle\wrapper\gradle-wrapper.jar'); Desc = 'Wrapper bootstrap jar' }
)

$allOk = $true
foreach ($item in $required) {
    $p = $item.Path
    if (Test-Path -LiteralPath $p) {
        $size = (Get-Item -LiteralPath $p).Length
        Write-Host ('[OK] {0}  ({1} bytes)  - {2}' -f $p, $size, $item.Desc) -ForegroundColor Green
    } else {
        Write-Host ('[MISSING] {0}  - {1}' -f $p, $item.Desc) -ForegroundColor Red
        $allOk = $false
    }
}

Write-Host ''

# 2. Validate jar content
$jar = Join-Path $root 'gradle\wrapper\gradle-wrapper.jar'
if (Test-Path -LiteralPath $jar) {
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'org/gradle/wrapper/GradleWrapperMain.class' } | Select-Object -First 1
        if ($entry) {
            Write-Host '[OK] gradle-wrapper.jar contains org/gradle/wrapper/GradleWrapperMain.class' -ForegroundColor Green
        } else {
            Write-Host '[WARN] GradleWrapperMain.class not found in jar' -ForegroundColor Yellow
        }
        $zip.Dispose()
    } catch {
        Write-Host ('[ERROR] jar validation failed: {0}' -f $_.Exception.Message) -ForegroundColor Red
        $allOk = $false
    }
}

Write-Host ''

# 3. Show properties
$props = Join-Path $root 'gradle\wrapper\gradle-wrapper.properties'
if (Test-Path -LiteralPath $props) {
    Write-Host '== gradle-wrapper.properties ==' -ForegroundColor Cyan
    Get-Content -LiteralPath $props
}

Write-Host ''

# 4. Try to run gradlew
if ($allOk) {
    Write-Host '== Running ./gradlew.bat --version ==' -ForegroundColor Cyan
    Push-Location $root
    try {
        $output = & .\gradlew.bat --version 2>&1
        $output | Select-Object -First 12
    } catch {
        Write-Host ('[WARN] gradlew run failed: {0}' -f $_.Exception.Message) -ForegroundColor Yellow
    }
    Pop-Location
} else {
    Write-Host '== Skipping gradlew test (files missing) ==' -ForegroundColor Yellow
}

Write-Host ''
if ($allOk) {
    Write-Host '== All checks passed ==' -ForegroundColor Green
} else {
    Write-Host '== Issues detected ==' -ForegroundColor Red
    exit 1
}
