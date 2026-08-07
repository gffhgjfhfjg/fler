package com.ai.fler.core.service

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * BackupManager 撤销栈回归测试（undo/redo 语义 + 每文件隔离 + 持久化重载 + CRC32 校验）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupManagerTest {

    private fun newManager(): BackupManager = BackupManager(RuntimeEnvironment.getApplication())

    @Test
    fun `computeCRC32 标准校验值正确`() {
        // CRC-32/IEEE 标准校验值：CRC32("123456789") == 0xCBF43926
        val crc = newManager().computeCRC32("123456789".toByteArray(Charsets.US_ASCII))
        assertEquals(0xCBF43926L, crc)
    }

    @Test
    fun `recordPatch 后 undo 返回并弹出栈顶`() {
        val bm = newManager()
        bm.setCurrentFile("/tmp/a.so")
        val rec = PatchRecord(0x100, byteArrayOf(1), byteArrayOf(2), "a.so", 111L, seq = 0)
        bm.recordPatch(rec.address, rec.oldBytes, rec.newBytes, rec.soName)

        val undone = bm.undo()
        assertEquals(rec.address, undone?.address)
        assertTrue(undone!!.oldBytes.contentEquals(byteArrayOf(1)))
        // 弹出后栈空
        assertNull(bm.undo())
        assertTrue(bm.getPatchRecords().isEmpty())
    }

    @Test
    fun `不同文件撤销栈相互隔离`() {
        val bm = newManager()
        bm.setCurrentFile("/tmp/a.so")
        bm.recordPatch(0x100, byteArrayOf(1), byteArrayOf(2), "a.so")
        bm.setCurrentFile("/tmp/b.so")
        // b 文件无记录
        assertTrue(bm.getPatchRecords().isEmpty())
        bm.recordPatch(0x200, byteArrayOf(3), byteArrayOf(4), "b.so")
        assertEquals(1, bm.getPatchRecords().size)

        bm.setCurrentFile("/tmp/a.so")
        assertEquals(1, bm.getPatchRecords().size)
    }

    @Test
    fun `撤销栈超过上限时丢弃最旧记录`() {
        val bm = newManager()
        bm.setCurrentFile("/tmp/a.so")
        for (i in 0 until 60) {
            bm.recordPatch(i.toLong(), byteArrayOf(i.toByte()), byteArrayOf((i + 1).toByte()), "a.so")
        }
        val records = bm.getPatchRecords()
        // 最多保留 50 条，最旧的（0..9）被丢弃
        assertEquals(50, records.size)
        assertEquals(10L, records.first().address)
    }

    @Test
    fun `持久化后重开同一文件可恢复撤销栈`() {
        val bm = newManager()
        bm.setCurrentFile("/tmp/z.so")
        bm.recordPatch(0x500, byteArrayOf(0x11), byteArrayOf(0x22), "z.so")

        // 模拟重开：新实例同路径 setCurrentFile 应从磁盘恢复
        val bm2 = newManager()
        bm2.setCurrentFile("/tmp/z.so")
        assertEquals(1, bm2.getPatchRecords().size)
        val rec = bm2.getPatchRecords().first()
        assertEquals(0x500L, rec.address)
        assertTrue(rec.oldBytes.contentEquals(byteArrayOf(0x11)))
        assertTrue(rec.newBytes.contentEquals(byteArrayOf(0x22)))
    }
}