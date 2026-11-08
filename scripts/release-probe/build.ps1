param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Jdk = 'C:\Program Files\Android\Android Studio\jbr',
    [string]$Keystore = "$env:USERPROFILE\.android\debug.keystore"
)
$ErrorActionPreference = 'Stop'
$out = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\build\release-upgrade-probe'))
$androidJar = Join-Path $Sdk 'platforms\android-36\android.jar'
$buildTools = Join-Path $Sdk 'build-tools\36.1.0'
New-Item -ItemType Directory -Force -Path $out, (Join-Path $out 'classes') | Out-Null
& (Join-Path $Jdk 'bin\javac.exe') -source 8 -target 8 -classpath $androidJar -d (Join-Path $out 'classes') (Join-Path $PSScriptRoot 'UpgradeProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
$env:JAVA_HOME = $Jdk
$classes = @(Get-ChildItem -LiteralPath (Join-Path $out 'classes') -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName)
& (Join-Path $buildTools 'd8.bat') --lib $androidJar --min-api 26 --output $out $classes
if ($LASTEXITCODE -ne 0) { throw 'Probe dexing failed' }
& (Join-Path $buildTools 'aapt.exe') package -f -M (Join-Path $PSScriptRoot 'AndroidManifest.xml') -I $androidJar -F (Join-Path $out 'unsigned.apk')
if ($LASTEXITCODE -ne 0) { throw 'Probe packaging failed' }
Push-Location -LiteralPath $out
try {
    & (Join-Path $buildTools 'aapt.exe') add -f unsigned.apk classes.dex
    if ($LASTEXITCODE -ne 0) { throw 'Cannot add probe DEX' }
} finally { Pop-Location }
& (Join-Path $buildTools 'zipalign.exe') -f 4 (Join-Path $out 'unsigned.apk') (Join-Path $out 'upgrade-probe.apk')
if ($LASTEXITCODE -ne 0) { throw 'Probe alignment failed' }
# Only for the historical development certificate. This probe is not a user APK.
& (Join-Path $buildTools 'apksigner.bat') sign --ks $Keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android (Join-Path $out 'upgrade-probe.apk')
if ($LASTEXITCODE -ne 0) { throw 'Probe signing failed' }
Write-Output (Join-Path $out 'upgrade-probe.apk')
