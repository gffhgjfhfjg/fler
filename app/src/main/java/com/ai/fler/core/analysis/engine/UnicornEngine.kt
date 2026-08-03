package com.ai.fler.core.analysis.engine

import android.util.Log
import com.ai.fler.core.analysis.EmuStepResult
import com.ai.fler.core.analysis.EmulationCapability
import com.ai.fler.core.analysis.EmulationEngine
import com.ai.fler.core.analysis.EmulationHandle
import com.ai.fler.core.analysis.EmulationOptions
import com.ai.fler.core.analysis.RegisterSnapshot
import com.ai.fler.core.analysis.StopReason
import com.ai.fler.core.jni.ElfParserBindings
import com.ai.fler.core.jni.UnicornBindings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Unicorn 仿真引擎（真实实现，替换 UnicornEnginePlaceholder）。
 *
 * 底层：静态链接进 fler_jni.so 的 libunicorn.a（arm64 单架构），
 * 通过 [UnicornBindings] JNI 桥调用。
 *
 * 会话模型：
 * - [open] 打开会话并按 PT_LOAD 段把 ELF 装载进模拟地址空间，
 *   PC 指向 e_entry，LR 指向哨兵地址（执行到即 FUNCTION_RETURN）
 * - 每个 [EmulationHandle] 对应一个 native 会话，[close] 释放
 *
 * 线程安全：所有 JNI 调用在 native 侧有会话级互斥；本类用
 * ConcurrentHashMap 管理句柄，suspend 方法统一切到 Default 调度器。
 */
class UnicornEngine : EmulationEngine {

    companion object {
        private const val TAG = "UnicornEngine"
        private const val PAGE_MASK: Long = -0x1000L // ~0xFFF，页对齐用
    }

    override val engineId: String = "unicorn"
    override val displayName: String = "Unicorn"

    /** 二级降级开关：编译期禁用或后端缺失时为 false，注册中心据此跳过。 */
    override val isAvailable: Boolean get() = UnicornBindings.isAvailable

    override val capabilities: Set<EmulationCapability> = setOf(
        EmulationCapability.INSTRUCTION_EMU,
        EmulationCapability.CODE_HOOK,
        EmulationCapability.MEMORY_HOOK,
        EmulationCapability.SINGLE_STEP
    )

    private val handleSeq = AtomicLong(1)
    private val sessions = ConcurrentHashMap<Long, Long>() // handle.value -> native handle

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override suspend fun open(filePath: String, options: EmulationOptions): EmulationHandle =
        withContext(Dispatchers.Default) {
            val native = UnicornBindings.open()
            if (native == 0L) {
                Log.e(TAG, "uc_open failed for $filePath")
                return@withContext EmulationHandle.INVALID
            }
            val ok = try {
                loadElfIntoSession(native, filePath, baseAddress = 0L)
            } catch (e: Exception) {
                Log.e(TAG, "load ELF failed: ${e.message}", e)
                false
            }
            if (!ok) {
                UnicornBindings.close(native)
                return@withContext EmulationHandle.INVALID
            }
            val value = handleSeq.getAndIncrement()
            sessions[value] = native
            EmulationHandle(value)
        }

    override suspend fun close(handle: EmulationHandle) = withContext(Dispatchers.Default) {
        sessions.remove(handle.value)?.let { UnicornBindings.close(it) }
        Unit
    }

    /**
     * 把一个 so 的 PT_LOAD 段追加装载进已有会话。
     * @param baseAddress 0 = 使用 ELF 原生 vaddr；非 0 = 重定位到该基址
     * @return 重定位后的入口点地址；失败 null
     */
    override suspend fun loadLibrary(
        handle: EmulationHandle,
        libraryPath: String,
        baseAddress: Long
    ): Long? = withContext(Dispatchers.Default) {
        val native = sessions[handle.value] ?: return@withContext null
        if (!loadElfIntoSession(native, libraryPath, baseAddress)) return@withContext null
        entryOf(libraryPath, baseAddress)
    }

    // ------------------------------------------------------------------
    // 内存 / 寄存器
    // ------------------------------------------------------------------

    override suspend fun mapMemory(
        handle: EmulationHandle,
        baseAddress: Long,
        size: Long,
        perms: Int
    ): Boolean = withContext(Dispatchers.Default) {
        val native = sessions[handle.value] ?: return@withContext false
        UnicornBindings.mapMemory(native, baseAddress, size, perms)
    }

    override suspend fun readMemory(
        handle: EmulationHandle,
        address: Long,
        size: Long
    ): ByteArray = withContext(Dispatchers.Default) {
        val native = sessions[handle.value] ?: return@withContext ByteArray(0)
        UnicornBindings.readMemory(native, address, size) ?: ByteArray(0)
    }

    override suspend fun writeMemory(
        handle: EmulationHandle,
        address: Long,
        data: ByteArray
    ): Boolean = withContext(Dispatchers.Default) {
        val native = sessions[handle.value] ?: return@withContext false
        UnicornBindings.writeMemory(native, address, data)
    }

    override suspend fun readRegister(handle: EmulationHandle, name: String): Long? =
        withContext(Dispatchers.Default) {
            val native = sessions[handle.value] ?: return@withContext null
            UnicornBindings.readRegister(native, name)
        }

    override suspend fun writeRegister(handle: EmulationHandle, name: String, value: Long): Boolean =
        withContext(Dispatchers.Default) {
            val native = sessions[handle.value] ?: return@withContext false
            UnicornBindings.writeRegister(native, name, value)
        }

    override suspend fun readAllRegisters(handle: EmulationHandle): RegisterSnapshot =
        withContext(Dispatchers.Default) {
            val native = sessions[handle.value]
                ?: return@withContext RegisterSnapshot()
            RegisterSnapshot(UnicornBindings.readAllRegisters(native))
        }

    // ------------------------------------------------------------------
    // 执行控制
    // ------------------------------------------------------------------

    override suspend fun run(
        handle: EmulationHandle,
        instrCount: Long,
        timeoutMs: Long
    ): EmuStepResult = withContext(Dispatchers.Default) {
        val native = sessions[handle.value]
            ?: return@withContext EmuStepResult(0, 0, stoppedBy = StopReason.ERROR)
        val r = UnicornBindings.run(native, instrCount, timeoutMs)
            ?: return@withContext EmuStepResult(0, 0, stoppedBy = StopReason.ERROR)
        EmuStepResult(
            pc = r.pc,
            instructionCount = r.instructionCount,
            stoppedBy = toStopReason(r.stopReason)
        )
    }

    override suspend fun step(handle: EmulationHandle): EmuStepResult =
        withContext(Dispatchers.Default) {
            val native = sessions[handle.value]
                ?: return@withContext EmuStepResult(0, 0, stoppedBy = StopReason.ERROR)
            val r = UnicornBindings.step(native)
                ?: return@withContext EmuStepResult(0, 0, stoppedBy = StopReason.ERROR)
            EmuStepResult(
                pc = r.pc,
                instructionCount = r.instructionCount,
                stoppedBy = toStopReason(r.stopReason)
            )
        }

    /** 请求中断正在运行的会话（另一线程调用安全）。 */
    override fun requestStop(handle: EmulationHandle) {
        sessions[handle.value]?.let { UnicornBindings.requestStop(it) }
    }

    override suspend fun setPc(handle: EmulationHandle, pc: Long) =
        withContext(Dispatchers.Default) {
            sessions[handle.value]?.let { UnicornBindings.setPc(it, pc) }
            Unit
        }

    // ------------------------------------------------------------------
    // 断点
    // ------------------------------------------------------------------

    override suspend fun addBreakpoint(handle: EmulationHandle, address: Long): Boolean =
        withContext(Dispatchers.Default) {
            val native = sessions[handle.value] ?: return@withContext false
            UnicornBindings.addBreakpoint(native, address)
        }

    override suspend fun removeBreakpoint(handle: EmulationHandle, address: Long): Boolean =
        withContext(Dispatchers.Default) {
            val native = sessions[handle.value] ?: return@withContext false
            UnicornBindings.removeBreakpoint(native, address)
        }

    override suspend fun listBreakpoints(handle: EmulationHandle): List<Long> =
        withContext(Dispatchers.Default) {
            val native = sessions[handle.value] ?: return@withContext emptyList()
            UnicornBindings.listBreakpoints(native)
        }

    // ------------------------------------------------------------------
    // 内部：ELF 装载
    // ------------------------------------------------------------------

    /**
     * 按 PT_LOAD 段装载 ELF 到模拟地址空间。
     *
     * 每段：页对齐映射（perms 取 p_flags，与 UC_PROT 同义 1=R 2=W 4=X）→
     * 写入 filesz 字节（memsz 多出的 BSS 由 uc_mem_map 零填充天然覆盖）。
     * 段重叠导致的重复映射会被 uc_mem_map 拒绝并跳过（沿用已有映射）。
     *
     * @return 是否装载成功（至少一个段写入成功）
     */
    private fun loadElfIntoSession(native: Long, filePath: String, baseAddress: Long): Boolean {
        ElfParserBindings().use { elf ->
            if (!elf.open(filePath)) {
                Log.e(TAG, "ElfParser open failed: $filePath")
                return false
            }
            val segments = elf.getLoadSegments()
            if (segments.isEmpty()) {
                Log.e(TAG, "no PT_LOAD segments: $filePath")
                return false
            }
            // 重定位：所有段按最小 vaddr 对齐到 baseAddress
            val minVaddr = segments.minOf { it.vaddr }
            val delta = if (baseAddress != 0L) baseAddress - minVaddr else 0L

            var loaded = false
            for (seg in segments) {
                val vaddr = seg.vaddr + delta
                val pageStart = vaddr and PAGE_MASK
                val pageEnd = (vaddr + seg.memsz + 0xFFF) and PAGE_MASK
                val size = pageEnd - pageStart
                if (size <= 0) continue
                // 失败通常是段重叠已映射，继续即可
                UnicornBindings.mapMemory(native, pageStart, size, seg.flags and 0x7)
                if (seg.filesz > 0) {
                    val data = elf.readBytes(seg.offset, seg.filesz)
                    if (data.size == seg.filesz.toInt()) {
                        UnicornBindings.writeMemory(native, vaddr, data)
                        loaded = true
                    } else {
                        Log.w(TAG, "segment data truncated: vaddr=0x${vaddr.toString(16)}")
                    }
                }
            }
            if (loaded && baseAddress == 0L) {
                // open 场景：PC=入口，LR=哨兵（函数返回探测）
                UnicornBindings.setPc(native, elf.getEntry())
                UnicornBindings.writeRegister(native, "lr", UnicornBindings.SENTINEL_ADDR)
            }
            return loaded
        }
    }

    /** 计算装载后的入口点（loadLibrary 返回值）。 */
    private fun entryOf(filePath: String, baseAddress: Long): Long? {
        ElfParserBindings().use { elf ->
            if (!elf.open(filePath)) return null
            val segments = elf.getLoadSegments()
            if (segments.isEmpty()) return null
            val minVaddr = segments.minOf { it.vaddr }
            val delta = if (baseAddress != 0L) baseAddress - minVaddr else 0L
            return elf.getEntry() + delta
        }
    }

    private fun toStopReason(code: Int): StopReason = when (code) {
        UnicornBindings.STOP_BREAKPOINT -> StopReason.BREAKPOINT
        UnicornBindings.STOP_SINGLE_STEP -> StopReason.SINGLE_STEP
        UnicornBindings.STOP_TIMEOUT -> StopReason.TIMEOUT
        UnicornBindings.STOP_ERROR -> StopReason.ERROR
        UnicornBindings.STOP_INTERRUPTED -> StopReason.INTERRUPTED
        UnicornBindings.STOP_FUNCTION_RETURN -> StopReason.FUNCTION_RETURN
        else -> StopReason.NONE
    }
}
