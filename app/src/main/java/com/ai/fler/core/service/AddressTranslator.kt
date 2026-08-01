package com.ai.fler.core.service

import android.util.Log
import com.ai.fler.core.jni.ElfParserBindings
import com.ai.fler.data.dao.AddressMappingDao
import com.ai.fler.data.entity.AddressMapping
import com.ai.fler.data.entity.DartMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 地址转换器。
 *
 * 负责在 Dart VM 偏移、ELF 文件偏移和虚拟地址之间进行相互转换。
 * 支持从 Blutter 分析结果（methods 表）构建映射关系。
 */
@Singleton
class AddressTranslator @Inject constructor(
    private val addressMappingDao: AddressMappingDao
) {

    companion object {
        private const val TAG = "AddressTranslator"
    }

    /**
     * 从分析结果的方法表构建地址映射。
     *
     * DartMethod.functionOffset 是方法的 ELF 虚拟地址，借助 ELF 节头
     * （节虚拟地址 ↔ 文件偏移）换算成文件偏移，写入 address_mappings，
     * 供「SO 中定位」跳转使用。
     *
     * @param projectId 项目 ID
     * @param libappSoPath libapp.so 本地路径
     * @param methods 该次分析的方法列表
     */
    suspend fun importMethods(
        projectId: Long,
        libappSoPath: String,
        methods: List<DartMethod>
    ) {
        withContext(Dispatchers.IO) {
            try {
                val sections = ElfParserBindings().use { parser ->
                    if (!parser.open(libappSoPath)) {
                        Log.w(TAG, "无法打开 libapp.so: $libappSoPath")
                        return@withContext
                    }
                    parser.getSections()
                }

                // 仅用可分配且具虚拟地址的节做 vaddr→fileoff 换算
                val usable = sections.filter { it.address > 0 && it.offset > 0 && it.size > 0 }

                val mappings = mutableListOf<AddressMapping>()
                for (method in methods) {
                    val addr = method.functionOffset ?: continue
                    if (addr <= 0) continue

                    val section = usable.firstOrNull {
                        addr >= it.address && addr < it.address + it.size
                    }
                    val fileOffset = if (section != null) {
                        section.offset + (addr - section.address)
                    } else {
                        0L
                    }

                    mappings.add(
                        AddressMapping(
                            projectId = projectId,
                            vmOffset = addr,
                            fileOffset = fileOffset,
                            elfAddress = addr,
                            section = section?.name ?: "",
                            symbol = method.methodName,
                        )
                    )
                }

                if (mappings.isNotEmpty()) {
                    addressMappingDao.deleteByProjectId(projectId)
                    addressMappingDao.insertAll(mappings)
                    Log.i(TAG, "导入地址映射 ${mappings.size} 条 (projectId=$projectId)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "构建地址映射失败: ${e.message}", e)
            }
        }
    }

    /**
     * 将 ELF 虚拟地址转换为文件偏移。
     *
     * @param elfAddress ELF 虚拟地址
     * @return 文件偏移，未找到返回 null
     */
    suspend fun elfAddressToFileOffset(elfAddress: Long): Long? {
        return try {
            val mapping = addressMappingDao.findByElfAddress(elfAddress)
            mapping?.fileOffset
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将 ELF 虚拟地址转换为文件偏移（带 ELF 文件回退）。
     *
     * 先查 DB 缓存（[elfAddressToFileOffset]）；若未命中则直接打开 ELF
     * 节头表换算：fileOffset = section.offset + (elfAddress - section.address)。
     *
     * 用于「ASM 方法 → SO 编辑器」跳转：分析后若 DB 映射缺失（旧分析、
     * importMethods 未覆盖到的方法），仍能拿到正确的文件偏移，
     * 避免 SoEditorViewModel.readFileBytes 因偏移越界返回空字节
     * 导致「该方法无可汇编字节」。
     *
     * @param elfAddress ELF 虚拟地址（DartMethod.functionOffset）
     * @param libappSoPath libapp.so 本地路径
     * @return 文件偏移，失败返回 null
     */
    suspend fun elfAddressToFileOffsetFromElf(
        elfAddress: Long,
        libappSoPath: String
    ): Long? {
        // 1. 先查 DB 缓存
        val cached = elfAddressToFileOffset(elfAddress)
        if (cached != null && cached > 0) return cached

        // 2. DB 未命中 → 从 ELF 节头表换算
        return withContext(Dispatchers.IO) {
            try {
                val sections = ElfParserBindings().use { parser ->
                    if (!parser.open(libappSoPath)) {
                        Log.w(TAG, "elfAddressToFileOffsetFromElf: 无法打开 $libappSoPath")
                        return@withContext null
                    }
                    parser.getSections()
                }
                val usable = sections.filter { it.address > 0 && it.offset > 0 && it.size > 0 }
                val section = usable.firstOrNull {
                    elfAddress >= it.address && elfAddress < it.address + it.size
                }
                if (section != null) {
                    val fileOffset = section.offset + (elfAddress - section.address)
                    Log.i(TAG, "elfAddressToFileOffsetFromElf: 0x${elfAddress.toString(16)} -> " +
                        "0x${fileOffset.toString(16)} (section=${section.name})")
                    fileOffset
                } else {
                    Log.w(TAG, "elfAddressToFileOffsetFromElf: 未找到包含 0x${elfAddress.toString(16)} 的节")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "elfAddressToFileOffsetFromElf 失败: ${e.message}", e)
                null
            }
        }
    }

    /**
     * 获取指定地址的上下文信息。
     *
     * @param address 地址（文件偏移或虚拟地址）
     * @return 地址上下文信息
     */
    suspend fun getContext(address: Long): AddressContext {
        return try {
            val mapping = addressMappingDao.findByFileOffset(address)
                ?: addressMappingDao.findByElfAddress(address)
                ?: addressMappingDao.findByVmOffset(address)

            if (mapping != null) {
                AddressContext(
                    found = true,
                    vmOffset = mapping.vmOffset,
                    fileOffset = mapping.fileOffset,
                    elfAddress = mapping.elfAddress,
                    section = mapping.section,
                    symbol = mapping.symbol
                )
            } else {
                AddressContext(found = false)
            }
        } catch (e: Exception) {
            AddressContext(found = false, errorMessage = e.message)
        }
    }
}

/**
 * 地址上下文信息。
 */
data class AddressContext(
    val found: Boolean,
    val vmOffset: Long = 0,
    val fileOffset: Long = 0,
    val elfAddress: Long = 0,
    val section: String = "",
    val symbol: String? = null,
    val errorMessage: String? = null
)
