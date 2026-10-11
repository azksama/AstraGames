param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Java = $env:JAVA_HOME,
    [string]$Keystore = "$env:USERPROFILE/.android/debug.keystore"
)
$ErrorActionPreference = 'Stop'
if (-not $Sdk) { $Sdk = "$env:LOCALAPPDATA/Android/Sdk" }
if (-not $Java) { $Java = 'C:/Program Files/Android/Android Studio/jbr' }
$projectRoot = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
$probeOutput = [IO.Path]::GetFullPath("$projectRoot/build/wolf-research/native-release-probe")
$probeBuildRoot = [IO.Path]::GetFullPath("$projectRoot/build").TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $probeOutput.StartsWith($probeBuildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Probe output escaped the project build directory'
}
$buildTools = "$Sdk/build-tools/36.1.0"
$androidJar = "$Sdk/platforms/android-36/android.jar"
New-Item -ItemType Directory -Force -Path "$probeOutput/classes", "$probeOutput/dex", "$probeOutput/assets" | Out-Null
Copy-Item -LiteralPath "$projectRoot/app/src/androidTest/assets/wolf-native/video/reference.ogv" -Destination "$probeOutput/assets/reference.ogv"
function Invoke-ProbeTool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Probe tool failed ($LASTEXITCODE): $Executable" }
}
Invoke-ProbeTool "$Java/bin/javac.exe" @('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', $androidJar, '-d', "$probeOutput/classes", "$PSScriptRoot/release-probe/NativeReleaseProbe.java")
Invoke-ProbeTool "$Java/bin/jar.exe" @('cf', "$probeOutput/classes.jar", '-C', "$probeOutput/classes", '.')
Invoke-ProbeTool "$buildTools/d8.bat" @('--min-api', '26', '--lib', $androidJar, '--output', "$probeOutput/dex", "$probeOutput/classes.jar")
Invoke-ProbeTool "$buildTools/aapt2.exe" @('link', '-I', $androidJar, '--manifest', "$PSScriptRoot/release-probe/AndroidManifest.xml", '-A', "$probeOutput/assets", '-o', "$probeOutput/unsigned.apk")
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open("$probeOutput/unsigned.apk", [IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, "$probeOutput/dex/classes.dex", 'classes.dex') | Out-Null }
finally { $archive.Dispose() }
Invoke-ProbeTool "$buildTools/zipalign.exe" @('-f', '-p', '4', "$probeOutput/unsigned.apk", "$probeOutput/aligned.apk")
Invoke-ProbeTool "$buildTools/apksigner.bat" @('sign', '--ks', $Keystore, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', "$probeOutput/probe.apk", "$probeOutput/aligned.apk")
Invoke-ProbeTool "$buildTools/apksigner.bat" @('verify', '--verbose', "$probeOutput/probe.apk")
Write-Output "Probe APK: $probeOutput/probe.apk"
