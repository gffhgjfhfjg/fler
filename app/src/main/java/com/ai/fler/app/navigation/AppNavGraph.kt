package com.ai.fler.app.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ai.fler.R
import com.ai.fler.core.service.AddressTranslator
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.features.output.AsmBrowserScreen
import com.ai.fler.features.output.AsmListScreen
import com.ai.fler.features.output.PpBrowserScreen
import com.ai.fler.features.mcp.McpLogScreen
import com.ai.fler.features.project.ProjectDetailScreen
import com.ai.fler.features.project.ProjectScreen
import com.ai.fler.features.settings.AboutScreen
import com.ai.fler.features.settings.McpSettingsScreen
import com.ai.fler.features.settings.SettingsScreen
import com.ai.fler.features.so_editor.SoEditorDetailScreen
import com.ai.fler.features.so_editor.SoEditorScreen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/** Tab -> 图标。 */
private val tabIcons: Map<Screen, ImageVector> = mapOf(
    Screen.Projects to Icons.Outlined.Folder,
    Screen.SoEditor to Icons.Outlined.Memory,
    Screen.McpLog to Icons.Outlined.Article,
    Screen.Settings to Icons.Outlined.Settings,
)

/** Tab -> 字符串资源 id。 */
private val tabLabels: Map<Screen, Int> = mapOf(
    Screen.Projects to R.string.tab_projects,
    Screen.SoEditor to R.string.tab_so,
    Screen.McpLog to R.string.tab_mcp_log,
    Screen.Settings to R.string.tab_settings,
)

/** 底部导航 label 淡入淡出时长（毫秒），改这里即可调整动画速度。 */
private const val TAB_LABEL_FADE_MS = 180

/**
 * 应用根导航图。
 *
 * 4 个顶层 Tab + 子页面路由（项目详情 / PP 浏览 / ASM 方法列表 / ASM 内容 / SO 编辑器）。
 * 子页面隐藏底部导航栏（不在 NavHost 之外渲染，直接覆盖全屏）。
 */
@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 用于在导航回调中查询库文件路径（如 PP 定位到 SO 编辑器）
    val navEntry = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            FlerNavEntryPoint::class.java
        )
    }
    val libraryDao = remember { navEntry.libraryDao() }
    val dartMethodDao = remember { navEntry.dartMethodDao() }
    val addressTranslator = remember { navEntry.addressTranslator() }

    // 从指定分析记录取 libapp.so 的本地路径
    val libappPathFor: suspend (Long) -> String? = { analysisId ->
        libraryDao.getByAnalysisIdList(analysisId)
            .firstOrNull { it.libraryName == "libapp.so" }
            ?.path
    }

    // ASM 方法 → SO 编辑器：取方法函数偏移和长度，换算为文件偏移，跳转
    val editMethodInSo: suspend (Long, Long) -> Unit = { analysisId, methodId ->
        val method = dartMethodDao.getById(methodId)
        val libapp = libappPathFor(analysisId)
        if (method?.functionOffset != null && libapp != null) {
            // 用 ELF 节头表换算文件偏移（DB 缓存未命中时自动回退到解析 ELF），
            // 避免直接拿 ELF 虚拟地址当文件偏移导致越界读空字节。
            val fileOffset = addressTranslator.elfAddressToFileOffsetFromElf(
                method.functionOffset!!,
                libapp
            )
            val methodLength = method.functionSize ?: 0L
            if (fileOffset != null && fileOffset > 0) {
                navController.navigate(
                    Screen.SoEditorDetail.createRoute(libapp, fileOffset, methodLength)
                )
            } else {
                // 极端兜底：仍拿不到文件偏移，用虚拟地址降级（可能越界但保留入口）
                navController.navigate(
                    Screen.SoEditorDetail.createRoute(libapp, method.functionOffset!!, methodLength)
                )
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                TopLevelTabs.forEach { tab ->
                    val isSelected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            navController.navigate(tab.route) {
                                // 切换 Tab 的标准模式：弹出至起点、避免栈累积、恢复状态
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            val icon = tabIcons[tab]
                            if (icon != null) {
                                Icon(imageVector = icon, contentDescription = null)
                            }
                        },
                        // 默认只显示图标；选中该页时文字由下往上平滑弹出（graphicsLayer 无布局回流）
                        label = {
                            val progress by animateFloatAsState(
                                targetValue = if (isSelected) 1f else 0f,
                                animationSpec = tween(durationMillis = TAB_LABEL_FADE_MS),
                                label = "tab-label"
                            )
                            Text(
                                text = stringResource(tabLabels[tab]!!),
                                modifier = Modifier.graphicsLayer {
                                    alpha = progress
                                    translationY = (1f - progress) * 8.dp.toPx()
                                }
                            )
                        },
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Projects.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            // ========== 顶层 Tab ==========
            composable(Screen.Projects.route) {
                ProjectScreen(
                    onProjectClick = { projectId ->
                        navController.navigate(Screen.ProjectDetail.createRoute(projectId))
                    }
                )
            }
            composable(Screen.SoEditor.route) { SoEditorScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onOpenMcpSettings = { navController.navigate(Screen.McpSettings.route) },
                    onOpenAbout = { navController.navigate(Screen.About.route) },
                )
            }

            // ========== MCP 日志（顶层 Tab）==========
            composable(Screen.McpLog.route) {
                McpLogScreen(onBack = null)
            }

            // ========== MCP 配置（二级 Screen）==========
            composable(Screen.McpSettings.route) {
                McpSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLog = { navController.navigate(Screen.McpLog.route) },
                )
            }

            // ========== 关于（二级 Screen）==========
            composable(Screen.About.route) {
                AboutScreen(onBack = { navController.popBackStack() })
            }

            // ========== 项目详情 ==========
            composable(
                route = Screen.ProjectDetail.route,
                arguments = listOf(navArgument("projectId") { type = NavType.LongType })
            ) { entry ->
                val projectId = entry.arguments?.getLong("projectId") ?: 0L
                ProjectDetailScreen(
                    projectId = projectId,
                    onBack = { navController.popBackStack() },
                    onPpBrowse = { analysisId ->
                        navController.navigate(Screen.PpBrowser.createRoute(analysisId))
                    },
                    onAsmBrowse = { analysisId ->
                        navController.navigate(Screen.AsmList.createRoute(analysisId))
                    },
                    onOpenSo = { filePath, offset ->
                        navController.navigate(Screen.SoEditorDetail.createRoute(filePath, offset))
                    }
                )
            }

            // ========== PP 浏览 ==========
            composable(
                route = Screen.PpBrowser.route,
                arguments = listOf(navArgument("analysisId") { type = NavType.LongType })
            ) { entry ->
                val analysisId = entry.arguments?.getLong("analysisId") ?: 0L
                PpBrowserScreen(
                    analysisId = analysisId,
                    onBack = { navController.popBackStack() },
                    onLocateInSo = { vmOffset, fileOffset ->
                        scope.launch {
                            val path = libappPathFor(analysisId)
                            if (path != null) {
                                val offset = if (fileOffset > 0) fileOffset else vmOffset
                                navController.navigate(
                                    Screen.SoEditorDetail.createRoute(path, offset)
                                )
                            }
                        }
                    }
                )
            }

            // ========== ASM 方法列表 ==========
            // 点击方法 → AsmBrowser（查看 src_code），AsmBrowser 内「在 SO 中编辑」→ SO 编辑器。
            composable(
                route = Screen.AsmList.route,
                arguments = listOf(navArgument("analysisId") { type = NavType.LongType }),
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) + fadeOut() }
            ) { entry ->
                val analysisId = entry.arguments?.getLong("analysisId") ?: 0L
                AsmListScreen(
                    analysisId = analysisId,
                    onBack = { navController.popBackStack() },
                    onMethodClick = { aId, methodId ->
                        navController.navigate(Screen.AsmBrowser.createRoute(aId, methodId))
                    }
                )
            }

            // ========== ASM 内容 ==========
            // 单方法 src_code 查看页（Blutter 反汇编伪代码）；「在 SO 中编辑」→ SO 编辑器。
            composable(
                route = Screen.AsmBrowser.route,
                arguments = listOf(
                    navArgument("analysisId") { type = NavType.LongType },
                    navArgument("methodId") { type = NavType.LongType }
                ),
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) + fadeOut() }
            ) { entry ->
                val analysisId = entry.arguments?.getLong("analysisId") ?: 0L
                val methodId = entry.arguments?.getLong("methodId") ?: 0L
                AsmBrowserScreen(
                    analysisId = analysisId,
                    methodId = methodId,
                    onBack = { navController.popBackStack() },
                    onEditInSo = { mId ->
                        scope.launch { editMethodInSo(analysisId, mId) }
                    }
                )
            }

            // ========== SO 编辑器详情 ==========
            composable(
                route = Screen.SoEditorDetail.route,
                arguments = listOf(
                    navArgument("filePath") { type = NavType.StringType },
                    navArgument("offset") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                    navArgument("length") {
                        type = NavType.LongType
                        defaultValue = 0L
                    }
                )
            ) { backStackEntry ->
                val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
                val offset = backStackEntry.arguments?.getLong("offset") ?: 0L
                val methodLength = backStackEntry.arguments?.getLong("length") ?: 0L
                val filePath = if (encodedPath.isNotEmpty()) {
                    try {
                        String(
                            android.util.Base64.decode(
                                encodedPath,
                                android.util.Base64.URL_SAFE
                            ),
                            Charsets.UTF_8
                        )
                    } catch (_: Exception) {
                        encodedPath
                    }
                } else {
                    ""
                }
                SoEditorDetailScreen(
                    filePath = filePath,
                    initialOffset = offset,
                    methodLength = methodLength,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

/** 导航层 Hilt EntryPoint：在 Composable 回调中查询 DAO。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FlerNavEntryPoint {
    fun libraryDao(): LibraryDao
    fun dartMethodDao(): DartMethodDao
    fun addressTranslator(): AddressTranslator
}
