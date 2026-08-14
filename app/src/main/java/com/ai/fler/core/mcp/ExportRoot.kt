package com.ai.fler.core.mcp

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * MCP HTTP `/export` 下载根抽象。
 *
 * 把 [McpHttpServer] 的文件访问解耦成可换实现，使 `/export` 列表/下载能跟随
 * 「工作目录」动态切换（未设置工作目录时回退到 App 缓存，设置后走 SAF）。
 * 服务器每请求通过 provider 解析当前实现，工作目录变更无需重启即可生效。
 */
interface ExportRoot {

    /** 目录内普通文件列表（按文件名排序）。 */
    fun list(): List<ExportFileInfo>

    /**
     * 按文件名打开只读流。
     * 实现方必须做路径校验（拒绝 `..` / `/` / `\`），非法或不存在返回 null。
     */
    fun open(name: String): InputStream?

    /** 启动期准备（File 实现 mkdirs；SAF 实现空操作）。 */
    fun prepare() {}

    /** 是否可用（URI 可解析 / 目录存在）。 */
    fun isAvailable(): Boolean = true
}

/** `/export` 列表项。 */
data class ExportFileInfo(val name: String, val size: Long)

/** 文件名安全校验：拒绝路径穿越与分隔符。 */
internal fun isSafeExportName(name: String): Boolean =
    name.isNotBlank() && !name.contains("..") && !name.contains('/') && !name.contains('\\')

/** File 实现：cacheDir/so_export 等本地目录。 */
class FileExportRoot(private val file: File) : ExportRoot {

    override fun list(): List<ExportFileInfo> =
        file.listFiles()?.filter { it.isFile }?.map { ExportFileInfo(it.name, it.length()) }?.sortedBy { it.name }
            ?: emptyList()

    override fun open(name: String): InputStream? {
        if (!isSafeExportName(name)) return null
        val safeRoot = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val target = runCatching { File(file, name).canonicalFile }.getOrNull() ?: return null
        if (!target.path.startsWith(safeRoot + File.separator) || !target.isFile) return null
        return runCatching { FileInputStream(target) }.getOrNull()
    }

    override fun prepare() {
        runCatching { file.mkdirs() }
    }
}

/** SAF 实现：工作目录 tree URI（DocumentFile 遍历 + ContentResolver 流）。 */
class SafExportRoot(
    private val context: Context,
    private val treeUri: Uri,
) : ExportRoot {

    private fun rootDoc(): DocumentFile? =
        runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()

    override fun list(): List<ExportFileInfo> =
        rootDoc()?.listFiles()
            ?.filter { it.isFile && !it.name.isNullOrBlank() }
            ?.map { ExportFileInfo(it.name!!, it.length()) }
            ?.sortedBy { it.name }
            ?: emptyList()

    override fun open(name: String): InputStream? {
        if (!isSafeExportName(name)) return null
        val doc = rootDoc()?.findFile(name) ?: return null
        if (!doc.isFile) return null
        return runCatching { context.contentResolver.openInputStream(doc.uri) }.getOrNull()
    }

    override fun isAvailable(): Boolean = rootDoc() != null
}
