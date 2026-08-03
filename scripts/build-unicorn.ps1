# build-unicorn.ps1 — 交叉编译 Unicorn v2.0.1 静态库（arm64-v8a）
# 用法: .\scripts\build-unicorn.ps1
# 产物: vendor\unicorn-src\build-android\libunicorn.a → 自动拷贝到 app\libs\arm64-v8a\
# 依赖: Android SDK（NDK + cmake 3.22.1，从 local.properties 的 sdk.dir 读取）

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Src = Join-Path $Root "vendor\unicorn-src"
$Build = Join-Path $Src "build-android"

# 从 local.properties 读取 sdk.dir（形如 sdk.dir=C\:\\Users\\...，properties 转义）
$localProps = Get-Content (Join-Path $Root "local.properties")
$sdkDir = ($localProps | Where-Object { $_ -match '^sdk\.dir=' }) -replace '^sdk\.dir=', '' -replace '\\:', ':' -replace '\\\\', '\'

$Ndk = Get-ChildItem (Join-Path $sdkDir "ndk") -Directory | Sort-Object Name -Descending | Select-Object -First 1
$CmakeExe = Join-Path $sdkDir "cmake\3.22.1\bin\cmake.exe"
$NinjaExe = Join-Path $sdkDir "cmake\3.22.1\bin\ninja.exe"

if (-not (Test-Path $Src)) { throw "缺少 unicorn 源码：$Src" }
if (-not (Test-Path $CmakeExe)) { throw "缺少 SDK cmake：$CmakeExe" }

# Unicorn 的 CMake 需要 sh（qemu/configure + create_config）；Windows 上用 Git Bash 的 sh.exe
$gitSh = "C:\Program Files\Git\bin"
if (Test-Path (Join-Path $gitSh "sh.exe")) {
    $env:PATH = "$gitSh;$env:PATH"
} elseif (-not (Get-Command sh -ErrorAction SilentlyContinue)) {
    throw "找不到 sh.exe（需安装 Git for Windows）"
}

Write-Host "[1/3] CMake configure (NDK=$($Ndk.Name))..."
if (Test-Path (Join-Path $Build "CMakeCache.txt")) {
    Remove-Item -Recurse -Force $Build   # 清理旧缓存，避免残留配置污染
}
$configureArgs = @(
    "-B", $Build, "-S", $Src,
    "-G", "Ninja",
    "-DCMAKE_MAKE_PROGRAM=$NinjaExe",
    "-DCMAKE_TOOLCHAIN_FILE=$(Join-Path $Ndk.FullName 'build\cmake\android.toolchain.cmake')",
    "-DANDROID_ABI=arm64-v8a",
    "-DANDROID_PLATFORM=android-26",
    "-DCMAKE_BUILD_TYPE=Release",
    "-DUNICORN_ARCH=aarch64",
    "-DBUILD_SHARED_LIBS=OFF",
    "-DUNICORN_BUILD_TESTS=OFF",
    "-DUNICORN_INSTALL=OFF"
)
& $CmakeExe @configureArgs
if ($LASTEXITCODE -ne 0) { throw "configure 失败" }

Write-Host "[2/3] Build libunicorn.a ..."
& $CmakeExe --build $Build --target unicorn -j8
if ($LASTEXITCODE -ne 0) { throw "build 失败" }

Write-Host "[3/3] 拷贝产物到 app\libs\arm64-v8a\ ..."
$Out = Join-Path $Root "app\libs\arm64-v8a"
$Lib = Join-Path $Build "libunicorn.a"
if (-not (Test-Path $Lib)) { throw "产物不存在：$Lib" }
Copy-Item $Lib $Out -Force
Write-Host "完成：$(Join-Path $Out 'libunicorn.a') ($([math]::Round((Get-Item $Lib).Length/1MB,1)) MB)"
