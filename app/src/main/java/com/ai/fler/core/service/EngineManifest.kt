package com.ai.fler.core.service

/**
 * 远程引擎清单（manifest.json，位于 fler-dart 仓库 main 分支稳定地址）。
 *
 * App 设置页据此列出可用的远程预编译 Dart 版本并按下拉按需下载；
 * 运行库为必装基线（libc++_shared.so），任何引擎加载前必须先就绪。
 *
 * JSON 结构示例：
 * ```json
 * {
 *   "packVersion": "v0.4.0",
 *   "releaseNotes": "...",
 *   "runtimeLibs": { "file": "fler-runtime-libs.7z", "url": "...", "sha256": "...", "sizeBytes": 123 },
 *   "engines": [ { "dartVersion": "3.12.2", "file": "dartvm-3.12.2.7z", "url": "...", "sha256": "...", "sizeBytes": 456 } ]
 * }
 * ```
 */
data class EngineManifest(
    val packVersion: String,
    val releaseNotes: String?,
    val runtimeLibs: RuntimeLibsEntry?,
    val engines: List<EngineEntry>,
)

/** 运行库条目（必装，与 Dart 版本无关）。 */
data class RuntimeLibsEntry(
    val file: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** 单个 Dart 版本引擎条目（按版本独立下载）。 */
data class EngineEntry(
    val dartVersion: String,
    val file: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)
