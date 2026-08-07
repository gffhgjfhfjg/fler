# ============================================================
# fler 一次性验证脚本：lint → JVM 单测 → Kover 覆盖率报告 → Release 构建
#
# 用法（在仓库根目录）：
#   powershell -ExecutionPolicy Bypass -File scripts\verify.ps1
#
# 任一环节失败即退出非零，并打印对应报告路径。
# ============================================================
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $root "gradlew.bat"

function Run-Step($name, $argsList) {
    Write-Host ""
    Write-Host "==== [$name] ====" -ForegroundColor Cyan
    & $gradle @argsList --console=plain
    if ($LASTEXITCODE -ne 0) {
        Write-Host "FAILED [$name] (exit=$LASTEXITCODE)" -ForegroundColor Red
        exit $LASTEXITCODE
    }
    Write-Host "OK [$name]" -ForegroundColor Green
}

Run-Step "lint"            @(":app:lintDebug")
Run-Step "JVM unit tests"  @(":app:testDebugUnitTest")
Run-Step "Kover report"    @(":app:koverHtmlReport")

# release 构建（R8 minify + shrinkResources，debug 签名冒烟）
Run-Step "assembleRelease" @(":app:assembleRelease")

Write-Host ""
Write-Host "=== ALL PASS ===" -ForegroundColor Green
Write-Host "Coverage: file:///$root/app/build/reports/kover/html/index.html"