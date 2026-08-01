package com.ai.fler.core.service

import com.ai.fler.data.dao.AddressMappingDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份管理器。
 *
 * 负责管理 SO 文件的修改历史，支持撤销和恢复操作。
 *
 * 安全流程：
 * 1. 首次编辑 → 创建 .bak 全量备份
 * 2. 应用补丁前 → CRC32 校验原字节
 * 3. 写入新字节 → 验证 CRC
 * 4. 记录到撤销栈
 */
@Singleton
class BackupManager @Inject constructor() {

    private val undoStack = ArrayDeque<PatchRecord>()
    private val MAX_UNDO = 50
    private var backupCreated = false

    /**
     * 首次编辑前创建备份。
     */
    suspend fun createBackupIfNeeded(soFile: File) {
        if (backupCreated) return

        withContext(Dispatchers.IO) {
            try {
                val backupFile = File(soFile.parent, "${soFile.name}.bak")
                if (!backupFile.exists()) {
                    soFile.copyTo(backupFile, overwrite = false)
                    backupCreated = true
                }
            } catch (e: Exception) {
                // 备份失败不阻塞主流程
            }
        }
    }

    /**
     * 记录补丁操作到撤销栈。
     */
    fun recordPatch(
        address: Long,
        oldBytes: ByteArray,
        newBytes: ByteArray,
        soName: String
    ) {
        if (undoStack.size >= MAX_UNDO) {
            undoStack.removeFirst()
        }

        undoStack.addLast(
            PatchRecord(
                address = address,
                oldBytes = oldBytes.copyOf(),
                newBytes = newBytes.copyOf(),
                soName = soName,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /**
     * 撤销上一次补丁。
     *
     * @return 被撤销的补丁记录，如果栈为空返回 null
     */
    fun undo(): PatchRecord? {
        return if (undoStack.isNotEmpty()) {
            undoStack.removeLast()
        } else {
            null
        }
    }

    /**
     * 获取撤销栈大小。
     */
    fun getUndoCount(): Int = undoStack.size

    /**
     * 获取所有补丁记录（用于导出）。
     */
    fun getPatchRecords(): List<PatchRecord> = undoStack.toList()

    /**
     * 清空撤销栈。
     */
    fun clearUndoStack() {
        undoStack.clear()
    }

    /**
     * 从备份文件恢复。
     */
    suspend fun restoreFromBackup(soFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val backupFile = File(soFile.parent, "${soFile.name}.bak")
                if (backupFile.exists()) {
                    backupFile.copyTo(soFile, overwrite = true)
                    backupCreated = false
                    undoStack.clear()
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * 计算 CRC32 校验值。
     */
    fun computeCRC32(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL.toInt()
        for (byte in data) {
            crc = crc xor byte.toInt()
            for (i in 0 until 8) {
                crc = if (crc and 1 != 0) {
                    (crc ushr 1) xor 0xEDB88320.toInt()
                } else {
                    crc ushr 1
                }
            }
        }
        return (crc xor 0xFFFFFFFFL.toInt()).toLong() and 0xFFFFFFFFL
    }

    /**
     * 验证数据完整性。
     */
    fun verifyCRC32(data: ByteArray, expectedCrc: Long): Boolean {
        val actualCrc = computeCRC32(data)
        return actualCrc == expectedCrc
    }

    /**
     * 生成补丁文件内容。
     */
    fun generatePatchFile(soFile: File, records: List<PatchRecord>): String {
        val builder = StringBuilder()
        builder.appendLine("# Patch file for ${soFile.name}")
        builder.appendLine("# Generated at: ${System.currentTimeMillis()}")
        builder.appendLine()

        for (record in records) {
            builder.appendLine(
                "0x${record.address.toString(16).uppercase()}: " +
                    record.newBytes.joinToString(" ") { byte ->
                        byte.toUByte().toString(16).uppercase().padStart(2, '0')
                    }
            )
        }

        return builder.toString()
    }
}

/**
 * 补丁记录。
 */
data class PatchRecord(
    val address: Long,
    val oldBytes: ByteArray,
    val newBytes: ByteArray,
    val soName: String,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PatchRecord) return false
        return address == other.address &&
            oldBytes.contentEquals(other.oldBytes) &&
            newBytes.contentEquals(other.newBytes) &&
            soName == other.soName &&
            timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + oldBytes.contentHashCode()
        result = 31 * result + newBytes.contentHashCode()
        result = 31 * result + soName.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
