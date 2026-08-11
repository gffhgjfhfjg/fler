# =============================================================================
# 获取 frida-core-devkit（arm64）并解压到工程位
#
# devkit 包含模块的 libfrida-core.a（约 260MB）与 frida-core.h（约 3MB）。
# 版本固定 = frida-server 版本（17.17.0），步骤升级需同步替 server 二进制。
#
#   PowerShell:
#     pwsh scripts/fetch-frida-devkit.ps1
#   CMD (陈旧 5.1 也支持):
#     powershell -ExecutionPolicy Bypass -File scripts/fetch-frida-devkit.ps1
# =============================================================================
param(
    [string]$Version = "17.17.0",
    [string]$Sha256  = "8f7a941aa4fb29f156fa3bae82506d960a740555bab03aa34a0d7339daf51f96"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$LibDir  = Join-Path $Root "app\libs\arm64-v8a"
$HeaderDir = Join-Path $Root "app\src\main\cpp\include\frida"
$Tmp = Join-Path $env:TEMP "frida-devkit-${Version}"

$Url = "https://github.com/frida/frida/releases/download/${Version}/frida-core-devkit-${Version}-android-arm64.tar.xz"
$Archive = Join-Path $env:TEMP "frida-core-devkit-${Version}-android-arm64.tar.xz"

Write-Host "[*] download $Url"
curl.exe -L --fail --retry 3 -o $Archive $Url
if ($LASTEXITCODE -ne 0) { throw "download failed" }

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Archive).Hash.ToLower()
Write-Host "[*] sha256 = $hash"
Write-Host "[*] expect = $Sha256"
if ($hash -ne $Sha256) { throw "sha256 mismatch; abort" }

# .tar.xz 解压：Windows tar.exe 依赖外部 xz，改由 python(内置 lzma)完成
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue -LiteralPath $tmp
New-Item -ItemType Directory -Path $tmp | Out-Null
python -c "import tarfile,lzma,sys; tarfile.open(sys.argv[1],'r:xz').extractall(sys.argv[2])" $Archive $tmp
if ($LASTEXITCODE -ne 0) { throw "extract failed (need python3 with lzma)" }

New-Item -ItemType Directory -Force -Path $HeaderDir | Out-Null
Copy-Item -Force -LiteralPath (Join-Path $tmp "libfrida-core.a") -Destination (Join-Path $LibDir "libfrida-core.a")
Copy-Item -Force -LiteralPath (Join-Path $tmp "frida-core.h")   -Destination (Join-Path $HeaderDir "frida-core.h")
Write-Host "[+] libfrida-core.a  -> $LibDir\libfrida-core.a"
Write-Host "[+] frida-core.h      -> $HeaderDir\frida-core.h"