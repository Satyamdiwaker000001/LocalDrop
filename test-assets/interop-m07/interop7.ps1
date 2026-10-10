# M07 cross-runtime interop harness (Windows CLR sender -> JVM Android receiver)
#
# Runs the REAL production stacks on both runtimes over loopback:
#   - JVM driver (ReceiverInterop.java): production Android ReceiverEngine +
#     M04 ControlServer + M05 AuthManager/TransactionAuthorizer
#   - dotnet test (SenderInteropTests, gated on LOCALDROP_INTEROP=1):
#     production Windows SenderEngine + M04/M05 client stack
#
# The flow performs mutual ECDSA auth + transaction-code authorization, then
# streams the source file and stream-compares bytes on the JVM side.
# Loopback only. NOT a physical-device Wi-Fi test (see M16).
#
# Prerequisites: JDK 21 (javac/java), Python 3 (source-file generation),
# .NET 10 SDK, and a prior Android unit-test build (provides the compiled
# Kotlin classes + kotlin-stdlib in the Gradle cache).
#
# Usage (from a Windows shell):
#   powershell -ExecutionPolicy Bypass -File test-assets/interop-m07/interop7.ps1 [-SizeMb 100]
#
# Exit: fails when the dotnet test fails, the driver reports MISMATCH, or the
# driver exits non-zero. Both sides print measured throughput (informational;
# the 37 MB/s target is a target, not a gate).
param([int]$SizeMb = 100)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$harnessDir = $PSScriptRoot
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) "localdrop-m07-interop"
$src = Join-Path $tmp "m07_source_$SizeMb.bin"
$dest = Join-Path $tmp "m07_recv_dest"
$info = Join-Path $tmp "m07_info.txt"

if (-not (Test-Path $tmp)) { New-Item -ItemType Directory -Force -Path $tmp | Out-Null }
if (-not (Test-Path $src)) {
    Write-Host "[m07] generating $SizeMb MB source file..."
    python -c "import os; open(r'$src','wb').write(os.urandom($SizeMb*1024*1024))"
}
if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
New-Item -ItemType Directory -Force -Path $dest | Out-Null
if (Test-Path $info) { Remove-Item -Force $info }

# Rebuild JVM classes so the driver links current production code.
Push-Location (Join-Path $repoRoot "android\LocalDrop")
try {
    .\gradlew.bat :app:compileDebugKotlin --offline --console=plain | Select-Object -Last 2
} finally {
    Pop-Location
}
$classes = Join-Path $repoRoot "android\LocalDrop\app\build\tmp\kotlin-classes\debug"
$javacOut = Join-Path $repoRoot "android\LocalDrop\app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes"
$stdlib = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib\2.0.*" -Recurse -Filter "kotlin-stdlib-2.0.*.jar" |
    Where-Object { $_.FullName -notmatch "sources|common|jdk7|jdk8" } |
    Select-Object -First 1 -ExpandProperty FullName)
if (-not $stdlib) { throw "kotlin-stdlib not found in Gradle cache; run :app:testDebugUnitTest once first" }
$driverClasses = Join-Path $tmp "driver-classes"
New-Item -ItemType Directory -Force -Path $driverClasses | Out-Null
$cpBuild = "$classes;$javacOut;$stdlib"
javac -cp $cpBuild -d $driverClasses (Join-Path $harnessDir "ReceiverInterop.java")
if ($LASTEXITCODE -ne 0) { throw "driver compilation failed" }

$cp = "$driverClasses;$cpBuild"
$drvLog = Join-Path $tmp "m07_driver.log"
$drv = Start-Process -FilePath "java" -ArgumentList @("-cp", "`"$cp`"", "ReceiverInterop", "`"$src`"", "`"$dest`"", "`"$info`"") -RedirectStandardOutput $drvLog -PassThru -NoNewWindow

try {
    $deadline = (Get-Date).AddSeconds(90)
    while (-not (Test-Path $info) -and (Get-Date) -lt $deadline -and -not $drv.HasExited) { Start-Sleep -Milliseconds 200 }
    if (-not (Test-Path $info)) { throw "driver did not publish info in time (exited=$($drv.HasExited))" }
    $lines = (Get-Content $info | Where-Object { $_ -match '\S' })
    $env:LOCALDROP_INTEROP = "1"
    $env:LOCALDROP_SOURCE_FILE = $src
    $env:LOCALDROP_CONTROL_PORT = $lines[0].Trim()
    $env:LOCALDROP_DATA_PORT = $lines[1].Trim()
    $env:LOCALDROP_RECEIVER_ID = $lines[2].Trim()
    $codeFile = Join-Path $tmp "m07_code.txt"
    Set-Content -NoNewline -Path $codeFile -Value $lines[3].Trim()
    $env:LOCALDROP_CODE_FILE = $codeFile
    Write-Host "[m07] controlPort=$($lines[0]) dataPort=$($lines[1]) receiver=$($lines[2])"
    dotnet test (Join-Path $repoRoot "windows\LocalDrop.Protocol.Tests\LocalDrop.Protocol.Tests.csproj") -c Debug --nologo --filter "FullyQualifiedName~SenderInteropTests"
    if ($LASTEXITCODE -ne 0) { throw "dotnet interop test failed" }
} finally {
    $env:LOCALDROP_INTEROP = $null
    $env:LOCALDROP_SOURCE_FILE = $null
    $env:LOCALDROP_CONTROL_PORT = $null
    $env:LOCALDROP_DATA_PORT = $null
    $env:LOCALDROP_RECEIVER_ID = $null
    $env:LOCALDROP_CODE_FILE = $null
    if (-not $drv.WaitForExit(180000)) { try { $drv.Kill() } catch { }; throw "JVM driver timed out" }
    # NOTE: driver status is read from its log, not Process.ExitCode (the
    # handle's exit code is unreliable under output redirection here).
    $drvOut = Get-Content $drvLog | Select-Object -Last 10
    $drvOut | ForEach-Object { Write-Host $_ }
    if (-not ($drvOut -match "^\[driver\] MATCH ")) { throw "JVM driver did not report MATCH" }
}
