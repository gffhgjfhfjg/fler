# =============================================================================
# Package locally cross-compiled native static libs (.a) into a tar.xz bundle
#
# Purpose: pack app/libs/arm64-v8a/*.a (keystone/capstone/rizin/frida/unicorn,
#          29 files, ~407MB) into one tar.xz that gets uploaded as a GitHub
#          Release asset of myfler/fler, so CI (.github/workflows/build-release.yml)
#          can download + unpack it back to the same path.
#
# Facts:
#   - All these .a are ignored by .gitignore (/app/libs/), never committed.
#   - CI runner (ubuntu) extracts with `tar -xJf`; locally we pack with python
#     (builtin lzma), same convention as scripts/fetch-frida-devkit.ps1, no 7z.
#
# Usage (repo root):
#   powershell -ExecutionPolicy Bypass -File scripts\package-native-libs.ps1 -Version v1
# Outputs (under out/):
#   fler-native-libs-v1.tar.xz
#   fler-native-libs-v1.sha256.txt   (whole-archive sha256, used by CI)
#   fler-native-libs-v1.manifest.txt (per-file list + sha256)
# =============================================================================
param(
    [string]$Version = "v1"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$LibDir  = Join-Path $Root "app\libs\arm64-v8a"
$OutDir  = Join-Path $Root "out"
$Archive = Join-Path $OutDir "fler-native-libs-$Version.tar.xz"

if (-not (Test-Path -LiteralPath $LibDir)) {
    throw "libs dir not found: $LibDir (cross-compile first)"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$tmp = Join-Path $OutDir ("_pkg_" + $Version)
if (Test-Path -LiteralPath $tmp) { Remove-Item -Recurse -Force -LiteralPath $tmp }
New-Item -ItemType Directory -Path "$tmp\lib" | Out-Null

# Layout: lib/*.a (CI unpacks then copies into app/libs/arm64-v8a/)
Get-ChildItem -LiteralPath $LibDir -Filter "*.a" | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $tmp ("lib\" + $_.Name))
}

# manifest: filename + bytes + sha256
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("fler-native-libs-$Version")
$lines.Add("Build date : " + (Get-Date -Format "yyyy-MM-ddTHH:mm:ssZ"))
$lines.Add("Layout     : lib/*.a -> app/libs/arm64-v8a/")
$lines.Add("Contents   : keystone capstone rizin(26) frida-core unicorn")
$lines.Add("")
Get-ChildItem -LiteralPath "$tmp\lib" -Filter "*.a" | Sort-Object Name | ForEach-Object {
    $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLower()
    $lines.Add(("{0}  bytes={1}  sha256={2}" -f $_.Name, $_.Length, $h))
}
$manifest = Join-Path $OutDir "fler-native-libs-$Version.manifest.txt"
$lines | Set-Content -LiteralPath $manifest -Encoding utf8

# python: tar.xz in one shot (tarfile 'w:xz')
python -c "import tarfile,sys; tarfile.open(sys.argv[2],'w:xz',preset=9).add(sys.argv[1], arcname='fler-native-libs')" "$tmp" "$Archive"
if ($LASTEXITCODE -ne 0) { throw "tar.xz packaging failed (exit=$LASTEXITCODE)" }
Remove-Item -Recurse -Force -LiteralPath $tmp -ErrorAction SilentlyContinue

$fullHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Archive).Hash.ToLower()
$size = (Get-Item -LiteralPath $Archive).Length
("$fullHash  $Archive") | Set-Content -LiteralPath (Join-Path $OutDir "fler-native-libs-$Version.sha256.txt") -Encoding ascii

Write-Host ""
Write-Host "=== Packaging done ===" -ForegroundColor Green
Write-Host "Archive : $Archive  ($([math]::Round($size/1MB,1)) MB)"
Write-Host "sha256  : $fullHash"
Write-Host "Manifest: $manifest"
Write-Host ""
Write-Host "Next: upload $Archive to myfler/fler GitHub Release (tag e.g. native-libs-$Version)"
Write-Host "     and put browser_download_url + sha256 into the CI default / workflow env."