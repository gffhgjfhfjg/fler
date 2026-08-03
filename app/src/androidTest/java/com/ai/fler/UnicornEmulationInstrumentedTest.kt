package com.ai.fler

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.fler.core.jni.NativeLoader
import com.ai.fler.core.jni.UnicornBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unicorn 仿真引擎真机回归测试（M6）。
 *
 * 验证静态链接的 libunicorn.a 在 arm64 设备上端到端可用：
 * 会话生命周期 / 代码执行 / 哨兵返回 / 单步 / 断点 / 内存读写 / 寄存器。
 *
 * 运行：gradlew :app:connectedDebugAndroidTest（或仅过滤本类）
 */
@RunWith(AndroidJUnit4::class)
class UnicornEmulationInstrumentedTest {

    companion object {
        private const val CODE_ADDR = 0x1000L
        // MOV X0, #42 ; RET
        private val CODE = byteArrayOf(
            0x40, 0x05, 0x80.toByte(), 0xD2.toByte(), // MOV X0, #42
            0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte()  // RET
        )
    }

    @Before
    fun setUp() {
        NativeLoader.load()
    }

    @Test
    fun engineIsAvailable() {
        assertTrue("Unicorn 应编译进当前构建", UnicornBindings.isAvailable)
        assertTrue("版本号非空", UnicornBindings.version.isNotBlank())
    }

    @Test
    fun callFunctionReturnsViaSentinel() {
        val h = UnicornBindings.open()
        assertTrue("open 应返回有效 handle", h != 0L)
        try {
            assertTrue(UnicornBindings.mapMemory(h, CODE_ADDR, 0x1000, 0b101)) // R+X
            assertTrue(UnicornBindings.writeMemory(h, CODE_ADDR, CODE))
            assertTrue(UnicornBindings.writeRegister(h, "lr", UnicornBindings.SENTINEL_ADDR))
            assertTrue(UnicornBindings.setPc(h, CODE_ADDR))

            val result = UnicornBindings.run(h, instrCount = 100, timeoutMs = 2000)
            assertNotNull("run 应返回结果", result)
            assertEquals("应停在哨兵（FUNCTION_RETURN）",
                UnicornBindings.STOP_FUNCTION_RETURN, result!!.stopReason)
            assertEquals("执行了 2 条指令", 2L, result.instructionCount)
            assertEquals("x0 == 42", 42L, UnicornBindings.readRegister(h, "x0"))
            assertEquals("PC 停在哨兵地址", UnicornBindings.SENTINEL_ADDR, result.pc)
        } finally {
            UnicornBindings.close(h)
        }
    }

    @Test
    fun singleStepAdvancesPc() {
        val h = UnicornBindings.open()
        assertTrue(h != 0L)
        try {
            assertTrue(UnicornBindings.mapMemory(h, CODE_ADDR, 0x1000, 0b101))
            assertTrue(UnicornBindings.writeMemory(h, CODE_ADDR, CODE))
            assertTrue(UnicornBindings.setPc(h, CODE_ADDR))

            val step1 = UnicornBindings.step(h)
            assertNotNull(step1)
            assertEquals("单步后 PC=+4", CODE_ADDR + 4, step1!!.pc)
            assertEquals(UnicornBindings.STOP_SINGLE_STEP, step1.stopReason)
        } finally {
            UnicornBindings.close(h)
        }
    }

    @Test
    fun breakpointStopsExecution() {
        val h = UnicornBindings.open()
        assertTrue(h != 0L)
        try {
            assertTrue(UnicornBindings.mapMemory(h, CODE_ADDR, 0x1000, 0b101))
            assertTrue(UnicornBindings.writeMemory(h, CODE_ADDR, CODE))
            assertTrue(UnicornBindings.addBreakpoint(h, CODE_ADDR + 4))
            assertEquals(listOf(CODE_ADDR + 4), UnicornBindings.listBreakpoints(h))
            assertTrue(UnicornBindings.writeRegister(h, "lr", UnicornBindings.SENTINEL_ADDR))
            assertTrue(UnicornBindings.setPc(h, CODE_ADDR))

            val result = UnicornBindings.run(h, instrCount = 100, timeoutMs = 2000)
            assertNotNull(result)
            assertEquals("应命中断点", UnicornBindings.STOP_BREAKPOINT, result!!.stopReason)
            assertEquals(CODE_ADDR + 4, result.pc)

            assertTrue(UnicornBindings.removeBreakpoint(h, CODE_ADDR + 4))
            assertTrue(UnicornBindings.listBreakpoints(h).isEmpty())
        } finally {
            UnicornBindings.close(h)
        }
    }

    @Test
    fun memoryRoundTrip() {
        val h = UnicornBindings.open()
        assertTrue(h != 0L)
        try {
            assertTrue(UnicornBindings.mapMemory(h, 0x20000, 0x1000, 0b111)) // RWX
            val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(0x1122334455667788uL.toLong()).array()
            assertTrue(UnicornBindings.writeMemory(h, 0x20000, data))
            val back = UnicornBindings.readMemory(h, 0x20000, 8)
            assertNotNull(back)
            assertTrue("内存读回应一致", data.contentEquals(back))
            // 未映射区域读取应失败（null）而非崩溃
            assertEquals(null, UnicornBindings.readMemory(h, 0x90000000, 8))
        } finally {
            UnicornBindings.close(h)
        }
    }

    @Test
    fun registerSnapshotContainsCoreRegs() {
        val h = UnicornBindings.open()
        assertTrue(h != 0L)
        try {
            assertTrue(UnicornBindings.writeRegister(h, "x7", 0xABCDEFL))
            val regs = UnicornBindings.readAllRegisters(h)
            assertTrue("寄存器快照非空", regs.isNotEmpty())
            assertTrue("含 sp/pc/lr", listOf("sp", "pc", "lr").all { it in regs })
            assertEquals(0xABCDEFL, regs["x7"])
            // 未知寄存器名返回 null
            assertEquals(null, UnicornBindings.readRegister(h, "bogus_reg"))
        } finally {
            UnicornBindings.close(h)
        }
    }

    /**
     * 性能基线（M6.3）：NOP×2 + 回跳循环跑 10 万条指令，记录耗时与 MIPS。
     * 耗时从 testcase time 属性与 Logcat（TAG=UnicornPerf）读取。
     */
    @Test
    fun perfBaseline() {
        val h = UnicornBindings.open()
        assertTrue(h != 0L)
        try {
            // NOP; NOP; B -8（跳回首条 NOP，无限循环）
            val loop = byteArrayOf(
                0x1F, 0x20, 0x03, 0xD5.toByte(),    // NOP
                0x1F, 0x20, 0x03, 0xD5.toByte(),    // NOP
                0xFE.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0x17                 // B -8
            )
            assertTrue(UnicornBindings.mapMemory(h, CODE_ADDR, 0x1000, 0b101))
            assertTrue(UnicornBindings.writeMemory(h, CODE_ADDR, loop))
            assertTrue(UnicornBindings.setPc(h, CODE_ADDR))

            val n = 100_000L
            val t0 = System.nanoTime()
            val result = UnicornBindings.run(h, instrCount = n, timeoutMs = 10_000)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            assertNotNull(result)
            assertEquals(n, result!!.instructionCount)
            val mips = n / elapsedMs / 1000.0
            Log.i("UnicornPerf", String.format(
                "perf baseline: %d instrs in %.1f ms (%.1f MIPS), stop=%d",
                n, elapsedMs, mips, result.stopReason))
        } finally {
            UnicornBindings.close(h)
        }
    }
}
