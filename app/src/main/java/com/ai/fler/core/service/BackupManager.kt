package com.ai.fler.core.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份管理器。
 *
 * 负责管理 SO 文件的修改历史，支持撤销和恢复操作。
 *
 * **按文件管理**：每个 SO 文件有独立的 undoStack，切换文件不会丢失记录。
 * **持久化**：每次 recordPatch / undo 后写入 JSON 文件，App 重启后仍可撤销。
 *
 * 安全流程：
 * 1. 首次编辑 → 创建 .bak 全量备份
 * 2. 应用补丁前 → CRC32 校验原字节
 * 3. 写入新字节 → 验证 CRC
 * 4. 记录到撤销栈 + 持久化
 */
@Singleton
class BackupManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "BackupManager"
        private const val MAX_UNDO = 50
        private const val UNDO_DIR = "undo"
    }

    /** 当前操作的文件路径（openFile 时设置）。 */
    private var currentFilePath: String = ""

    /** 每个文件的撤销栈。 */
    private val fileStacks = mutableMapOf<String, ArrayDeque<PatchRecord>>()

    /** 每个文件的 seqCounter。 */
    private val fileSeqs = mutableMapOf<String, Long>()

    /** 每个文件是否已创建 .bak。 */
    private val fileBackupCreated = mutableMapOf<String, Boolean>()

    private val undoDir: File by lazy {
        File(context.filesDir, UNDO_DIR).also { it.mkdirs() }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 设置当前操作的文件，并加载其持久化的撤销栈。
     */
    fun setCurrentFile(filePath: String) {
        currentFilePath = filePath
        if (filePath.isNotBlank()) {
            fileStacks.getOrPut(filePath) { loadFromFile(filePath) }
            fileSeqs.getOrPut(filePath) {
                fileStacks[filePath]?.maxOfOrNull { it.seq }?.plus(1) ?: 0L
            }
            fileBackupCreated.getOrPut(filePath) {
                File(File(filePath).parent, "${File(filePath).name}.bak").exists()
            }
        }
    }

    /**
     * 首次编辑前创建备份。
     */
    suspend fun createBackupIfNeeded(soFile: File) {
        val path = soFile.absolutePath
        if (fileBackupCreated[path] == true) return

        withContext(Dispatchers.IO) {
            try {
                val backupFile = File(soFile.parent, "${soFile.name}.bak")
                if (!backupFile.exists()) {
                    soFile.copyTo(backupFile, overwrite = false)
                }
                fileBackupCreated[path] = true
            } catch (e: Exception) {
                Log.w(TAG, "创建备份失败: ${soFile.name}", e)
            }
        }
    }

    /**
     * 记录补丁操作到撤销栈并持久化。
     */
    fun recordPatch(
        address: Long,
        oldBytes: ByteArray,
        newBytes: ByteArray,
        soName: String
    ) {
        val stack = currentStack()
        if (stack.size >= MAX_UNDO) {
            stack.removeFirst()
        }

        val seq = fileSeqs.getOrPut(currentFilePath) { 0L }
        fileSeqs[currentFilePath] = seq + 1

        stack.addLast(
            PatchRecord(
                address = address,
                oldBytes = oldBytes.copyOf(),
                newBytes = newBytes.copyOf(),
                soName = soName,
                timestamp = System.currentTimeMillis(),
                seq = seq
            )
        )
        saveToFile(currentFilePath, stack)
    }

    /**
     * 撤销上一次补丁。
     *
     * @return 被撤销的补丁记录，如果栈为空返回 null
     */
    fun undo(): PatchRecord? {
        val stack = currentStack()
        if (stack.isEmpty()) return null
        val record = stack.removeLast()
        saveToFile(currentFilePath, stack)
        return record
    }

    /**
     * 获取当前文件的所有补丁记录（用于导出 / 高亮未保存修改）。
     */
    fun getPatchRecords(): List<PatchRecord> = currentStack().toList()

    /**
     * 清空内存中所有文件的撤销栈（用户「清理项目缓存」后调用）。
     * 保留 currentFilePath，让后续写入操作仍能自动重建持久化目录。
     */
    fun clearAllInMemory() {
        fileStacks.clear()
        fileSeqs.clear()
        fileBackupCreated.clear()
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

    // ------------------------------------------------------------------
    // 内部：持久化
    // ------------------------------------------------------------------

    private fun currentStack(): ArrayDeque<PatchRecord> =
        fileStacks.getOrPut(currentFilePath) { ArrayDeque() }

    private fun undoFilePath(filePath: String): File {
        val md5 = MessageDigest.getInstance("MD5").digest(filePath.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(undoDir, "$md5.json")
    }

    private fun loadFromFile(filePath: String): ArrayDeque<PatchRecord> {
        return try {
            val file = undoFilePath(filePath)
            if (!file.exists()) return ArrayDeque()
            val content = file.readText()
            val arr = json.parseToJsonElement(content).jsonArray
            val stack = ArrayDeque<PatchRecord>()
            for (element in arr) {
                val obj = element.jsonObject
                stack.addLast(
                    PatchRecord(
                        address = obj["address"]!!.jsonPrimitive.content.toLong(),
                        oldBytes = hexToBytes(obj["oldBytes"]!!.jsonPrimitive.content),
                        newBytes = hexToBytes(obj["newBytes"]!!.jsonPrimitive.content),
                        soName = obj["soName"]!!.jsonPrimitive.content,
                        timestamp = obj["timestamp"]!!.jsonPrimitive.content.toLong(),
                        seq = obj["seq"]?.jsonPrimitive?.content?.toLong() ?: 0L
                    )
                )
            }
            stack
        } catch (e: Exception) {
            Log.w(TAG, "加载撤销栈失败: $filePath", e)
            ArrayDeque()
        }
    }

    private fun saveToFile(filePath: String, stack: ArrayDeque<PatchRecord>) {
        try {
            val arr = buildJsonArray {
                for (r in stack) {
                    add(buildJsonObject {
                        put("address", JsonPrimitive(r.address.toString()))
                        put("oldBytes", JsonPrimitive(bytesToHex(r.oldBytes)))
                        put("newBytes", JsonPrimitive(bytesToHex(r.newBytes)))
                        put("soName", JsonPrimitive(r.soName))
                        put("timestamp", JsonPrimitive(r.timestamp.toString()))
                        put("seq", JsonPrimitive(r.seq.toString()))
                    })
                }
            }
            undoFilePath(filePath).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "保存撤销栈失败: $filePath", e)
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4)
                    + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
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
    val timestamp: Long,
    /** 全局自增序号，用于区分「未保存」的补丁（seq > savedSeq）。 */
    val seq: Long = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PatchRecord) return false
        return address == other.address &&
            oldBytes.contentEquals(other.oldBytes) &&
            newBytes.contentEquals(other.newBytes) &&
            soName == other.soName &&
            timestamp == other.timestamp &&
            seq == other.seq
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + oldBytes.contentHashCode()
        result = 31 * result + newBytes.contentHashCode()
        result = 31 * result + soName.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + seq.hashCode()
        return result
    }
}
