param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
# SDK-only probe of the current Wine release; no retired native runtime fixtures.
& $Python "$PSScriptRoot/../../scripts/wolf-runtime/build-release-probe.py"
if ($LASTEXITCODE -ne 0) { throw "Release probe build failed ($LASTEXITCODE)" }
