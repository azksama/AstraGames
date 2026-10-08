$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$output = Join-Path $projectRoot 'build/wolf-research/release-probe'
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$java = 'C:/Program Files/Android/Android Studio/jbr'
$env:JAVA_HOME = $java
$buildTools = Join-Path $sdk 'build-tools/36.1.0'
$androidJar = Join-Path $sdk 'platforms/android-36/android.jar'
New-Item -ItemType Directory -Force "$output/classes", "$output/dex" | Out-Null
& "$java/bin/javac.exe" -source 17 -target 17 -classpath $androidJar -d "$output/classes" "$PSScriptRoot/RuntimeProbe.java"
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
& "$java/bin/jar.exe" cf "$output/probe.jar" -C "$output/classes" .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
& "$buildTools/d8.bat" --min-api 26 --lib $androidJar --output "$output/dex" "$output/probe.jar"
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
& "$buildTools/aapt.exe" package -f -M "$PSScriptRoot/AndroidManifest.xml" -I $androidJar -F "$output/probe-unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'aapt failed' }
Push-Location "$output/dex"
try { & "$buildTools/aapt.exe" add "$output/probe-unsigned.apk" classes.dex; if ($LASTEXITCODE -ne 0) { throw 'dex packaging failed' } }
finally { Pop-Location }
& "$buildTools/apksigner.bat" sign --ks "$env:USERPROFILE/.android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$output/probe.apk" "$output/probe-unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'probe signing failed' }
Write-Output "$output/probe.apk"
