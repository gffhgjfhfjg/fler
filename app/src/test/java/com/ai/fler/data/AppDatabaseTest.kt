package com.ai.fler.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.ai.fler.data.dao.ProjectDao
import com.ai.fler.data.entity.Analysis
import com.ai.fler.data.entity.Library
import com.ai.fler.data.entity.Project
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Room 数据库 + 应用层级联删除逻辑 回归测试（Robolectric 内存库）。
 *
 * AppDatabase 使用 app 层（而非数据库级外键）显式级联删除
 * （见 AppDatabase.cascadeDeleteProject/cascadeDeleteAnalysis），
 * 这里验证级联正确性：删项目 → 关联 analyses/libraries 全清；
 * 删单条分析 → 仅该分析及其子数据被删，项目与其他分析保留。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase
    private lateinit var projectDao: ProjectDao

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        projectDao = db.projectDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 插入 1 个项目 + n 条分析与子数据。返回 project id。 */
    private suspend fun seedProject(projectId: Long, analysisCount: Int): Long {
        val pid = projectDao.insert(
            Project(id = projectId, name = "p-$projectId", apkPath = "/data/p$projectId.apk")
        )
        val analysisDao = db.analysisDao()
        repeat(analysisCount) { i ->
            val aid = analysisDao.insert(
                Analysis(projectId = pid, resultCode = Analysis.RESULT_SUCCESS)
            )
            db.libraryDao().insert(
                Library(analysisId = aid, libraryName = "lib$i.so", path = "/s/lib$i.so")
            )
        }
        return pid
    }

    @Test
    fun projectCrud() = runBlocking {
        val pid = projectDao.insert(Project(name = "p", apkPath = "/a.apk"))
        assertTrue(pid > 0)

        val loaded = projectDao.getById(pid)
        assertNotNull(loaded)
        assertEquals("p", loaded!!.name)
        // 唯一 apk_path：同路径再插入走 REPLACE，仍只有一行
        projectDao.insert(Project(name = "p2", apkPath = "/a.apk"))
        assertEquals(1, projectDao.getAll().first().size)

        projectDao.deleteById(pid)
        assertNull(projectDao.getById(pid))
    }

    @Test
    fun cascadeDeleteProjectRemovesAllChildren() = runBlocking {
        val pid = seedProject(0, analysisCount = 2)
        val analysisIds = db.analysisDao().getByProjectIdList(pid).map { it.id }

        val deleted = db.cascadeDeleteProject(pid)
        assertEquals("应返回被删分析数=2", 2, deleted)
        assertNull("项目应被删", projectDao.getById(pid))
        assertTrue("该项目的分析应全删", db.analysisDao().getByProjectIdList(pid).isEmpty())
        analysisIds.forEach { aid ->
            assertTrue("analysis=$aid 的 libraries 应被级联删除", db.libraryDao().getByAnalysisIdList(aid).isEmpty())
        }
    }

    @Test
    fun cascadeDeleteAnalysisKeepsSiblingsAndProject() = runBlocking {
        val pid = seedProject(0, analysisCount = 2)
        val analyses = db.analysisDao().getByProjectIdList(pid)
        assertEquals(2, analyses.size)

        db.cascadeDeleteAnalysis(analyses[0].id)

        val remaining = db.analysisDao().getByProjectIdList(pid)
        assertEquals("仅删一条分析", 1, remaining.size)
        assertEquals("保留的是第二条", analyses[1].id, remaining[0].id)
        assertNotNull("项目本体应保留", projectDao.getById(pid))
    }

    @Test
    fun projectFlowReflectsInserts() = runBlocking {
        val pid = seedProject(0, analysisCount = 1) // creates project with apk_path /data/p0.apk
        val flow = projectDao.getByIdFlow(pid).first()
        assertNotNull(flow)
        assertEquals(pid, flow!!.id)
    }
}