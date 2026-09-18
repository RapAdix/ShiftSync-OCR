[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome "include\jni.h"))) {
    $JdkHome = $null
    $knownJbr = Get-ChildItem (Join-Path $env:USERPROFILE ".jbr") -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "include\jni.h") } |
        Select-Object -First 1
    if ($knownJbr) {
        $JdkHome = $knownJbr.FullName
    }
}
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome "include\jni.h"))) {
    throw "No JDK/JBR JNI headers found. Pass -JdkHome <path-to-JDK-or-JBR>."
}

$vsWhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$visualStudioPath = if (Test-Path $vsWhere) {
    & $vsWhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
}
if (-not $visualStudioPath) {
    throw "Visual Studio C++ build tools were not found."
}

$vsDevCmd = Join-Path $visualStudioPath "Common7\Tools\VsDevCmd.bat"
$cmakePath = Join-Path $visualStudioPath "Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
if (-not (Test-Path $vsDevCmd) -or -not (Test-Path $cmakePath)) {
    throw "Visual Studio's CMake tools were not found. Add C++ CMake tools for Windows in the Visual Studio Installer."
}

$sourceDirectory = Join-Path $PSScriptRoot "line_detector_native"
$buildDirectory = Join-Path $PSScriptRoot "..\..\..\build\desktopNative\windows-x64"

# NMake uses the MSVC environment from VsDevCmd, avoiding a hard-coded Visual
# Studio generator name and therefore working across Visual Studio releases.
$configureCommand = 'call "{0}" -arch=x64 -host_arch=x64 && "{1}" -S "{2}" -B "{3}" -G "NMake Makefiles" "-DJAVA_HOME={4}"' -f $vsDevCmd, $cmakePath, $sourceDirectory, $buildDirectory, $JdkHome
& cmd.exe /d /s /c $configureCommand
if ($LASTEXITCODE -ne 0) { throw "CMake configuration failed." }

$buildCommand = 'call "{0}" -arch=x64 -host_arch=x64 && "{1}" --build "{2}"' -f $vsDevCmd, $cmakePath, $buildDirectory
& cmd.exe /d /s /c $buildCommand
if ($LASTEXITCODE -ne 0) { throw "CMake build failed." }

$library = Join-Path $buildDirectory "Release\line_detector_native.dll"
if (-not (Test-Path $library)) {
    throw "Build completed without producing $library"
}
Write-Host "Built desktop JNI DLL: $library"
