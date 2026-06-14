# PomoTick Gradle Wrapper setup script
# Run ONCE to create gradle-wrapper.jar and gradle-wrapper.properties
# in your physical filesystem:
#   powershell -ExecutionPolicy Bypass -File .\setup-wrapper.ps1

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$gradleVersion = '8.2'

Write-Host '== PomoTick Wrapper Setup ==' -ForegroundColor Cyan
Write-Host ('Root: {0}' -f $root)
Write-Host ''

# 1. Ensure gradle\wrapper directory
$wrapperDir = Join-Path $root 'gradle\wrapper'
if (-not (Test-Path -LiteralPath $wrapperDir)) {
    New-Item -ItemType Directory -Path $wrapperDir -Force | Out-Null
    Write-Host ('[OK] Created: {0}' -f $wrapperDir) -ForegroundColor Green
} else {
    Write-Host ('[OK] Exists: {0}' -f $wrapperDir) -ForegroundColor Green
}

$jarPath = Join-Path $wrapperDir 'gradle-wrapper.jar'
$propsPath = Join-Path $wrapperDir 'gradle-wrapper.properties'

# 2. Download the canonical bootstrap jar from Gradle's official GitHub repo
$jarUrl = "https://raw.githubusercontent.com/gradle/gradle/v$gradleVersion.0/gradle/wrapper/gradle-wrapper.jar"
Write-Host ('Downloading: {0}' -f $jarUrl)
try {
    Invoke-WebRequest -Uri $jarUrl -OutFile $jarPath -TimeoutSec 60 -UseBasicParsing -ErrorAction Stop
    Write-Host ('[OK] {0}  ({1} bytes)' -f $jarPath, (Get-Item -LiteralPath $jarPath).Length) -ForegroundColor Green
} catch {
    Write-Host ('[FAIL] {0}' -f $_.Exception.Message) -ForegroundColor Red
}

# 3. Write properties via standard file API
$propsContent = @"
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
"@
[System.IO.File]::WriteAllText($propsPath, $propsContent, [System.Text.Encoding]::ASCII)
Write-Host ('[OK] {0}  ({1} bytes)' -f $propsPath, (Get-Item -LiteralPath $propsPath).Length) -ForegroundColor Green

# 4. Verify the jar has GradleWrapperMain
Write-Host ''
Write-Host '== Verify ==' -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
$entry = $zip.Entries | Where-Object { $_.FullName -eq 'org/gradle/wrapper/GradleWrapperMain.class' } | Select-Object -First 1
$zip.Dispose()
if ($entry) {
    Write-Host '[OK] gradle-wrapper.jar contains GradleWrapperMain.class' -ForegroundColor Green
} else {
    Write-Host '[FAIL] jar missing GradleWrapperMain.class' -ForegroundColor Red
}

# 5. Test gradlew
Write-Host ''
Write-Host '== Test ./gradlew.bat --version ==' -ForegroundColor Cyan
Push-Location $root
try {
    $out = & .\gradlew.bat --version 2>&1
    $out | Select-Object -First 10
} catch {
    Write-Host ('[FAIL] {0}' -f $_.Exception.Message) -ForegroundColor Red
}
Pop-Location

Write-Host ''
Write-Host '== Done ==' -ForegroundColor Cyan
