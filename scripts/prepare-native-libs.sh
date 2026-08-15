#!/usr/bin/env bash
#
# prepare-native-libs.sh — 在 CI(runner) 上准备 fler 构建所需的原生静态库（.a）。
#
# fler 的 assembleRelease 需要 app/libs/arm64-v8a/ 下的硬依赖 .a：
#   libkeystone.a  libcapstone.a  libfrida-core.a  librz_*.a  libunicorn.a
# 这些 .a 被 gitignore，不进源仓库。本脚本把它们下载/解压到仓库对应路径。
#
# 默认策略（Option A，单一来源）：
#   从 myfler/fler 的 GitHub Release 资产 fler-native-libs-<ver>.tar.xz 一次性解出全部 .a。
#   可用环境变量覆盖来源/版本，便于切换：
#     FLER_NATIVE_URL  -> 7z 资产 browser_download_url（默认用 github.repository 拼）
#     FLER_NATIVE_SHA  -> 整包 sha256（非空则校验）
#     FLER_NATIVE_VER  -> 版本段（默认 v1）
#     FLER_LIBS_HOME   -> 解压目标（默认 <repo>/app/libs/arm64-v8a）
#
# 用法：
#   bash scripts/prepare-native-libs.sh
#
# 任一必需库缺失/校验失败即 exit 1。
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS_HOME="${FLER_LIBS_HOME:-$REPO_ROOT/app/libs/arm64-v8a}"
VER="${FLER_NATIVE_VER:-v1}"
GITHUB_REPO="${GITHUB_REPOSITORY:-myfler/fler}"

# 默认资产 URL：https://github.com/<repo>/releases/download/native-libs-<ver>/fler-native-libs-<ver>.tar.xz
DEFAULT_URL="https://github.com/${GITHUB_REPO}/releases/download/native-libs-${VER}/fler-native-libs-${VER}.tar.xz"
URL="${FLER_NATIVE_URL:-$DEFAULT_URL}"
SHA="${FLER_NATIVE_SHA:-79c25d84941456de18189f53eac0a052dfc971e4c6328d10adc67fc840ebff43}"

mkdir -p "$LIBS_HOME"

echo "==> 原生库来源: $URL"

TMP_ARCHIVE="${TMPDIR:-/tmp}/fler-native-libs.$$.tar.xz"
trap 'rm -f "$TMP_ARCHIVE"' EXIT

echo "==> 下载 $URL"
curl -fL --retry 3 -o "$TMP_ARCHIVE" "$URL"

if [ -n "$SHA" ]; then
    ACTUAL="$(sha256sum "$TMP_ARCHIVE" | awk '{print $1}')"
    echo "==> 校验 sha256: expect=$SHA actual=$ACTUAL"
    [ "$ACTUAL" = "$SHA" ] || { echo "!! sha256 不匹配"; exit 1; }
fi

echo "==> 解压到 $LIBS_HOME"
TMP_X="$(mktemp -d)"
trap 'rm -f "$TMP_ARCHIVE"; rm -rf "$TMP_X"' EXIT
tar -xJf "$TMP_ARCHIVE" -C "$TMP_X"
# 布局：<tmp>/fler-native-libs/lib/*.a
SRC="$(find "$TMP_X" -mindepth 2 -maxdepth 2 -name '*.a' | head -n1)"
[ -n "$SRC" ] || { echo "!! 归档中未找到任何 .a"; exit 1; }
LIBDIR_SRC="$(dirname "$SRC")"
cp "$LIBDIR_SRC"/*.a "$LIBS_HOME"/

echo "==> 校验必需库存在"
for lib in libkeystone.a libcapstone.a libfrida-core.a libunicorn.a; do
    [ -s "$LIBS_HOME/$lib" ] || { echo "!! 缺少 $lib"; exit 1; }
done
# rizin 至少一个（可选但应存在）
if ! ls "$LIBS_HOME"/librz_*.a >/dev/null 2>&1; then
    echo "!! 缺少 librz_*.a（Rizin 库）"; exit 1
fi

echo "==> 完成：$(ls "$LIBS_HOME" | wc -l) 个 .a"
ls -lh "$LIBS_HOME"
