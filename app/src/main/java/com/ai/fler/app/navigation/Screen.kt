package com.ai.fler.app.navigation

/**
 * 根导航路由定义。
 *
 * 顶层 3 个 Tab 为 Bottom Navigation 的 destination；
 * 后续 P3-P5 的详情页通过同名 route 的子路径挂在 NavGraph 上。
 *
 * 注意：「产物」Tab 已移除——其内容（分析记录列表 + PP/ASM 入口）与
 * ProjectDetailScreen 高度重复，因此产物浏览统一从项目详情页进入。
 */
sealed class Screen(val route: String) {
    /** 项目管理 Tab（P3）。 */
    data object Projects : Screen("projects")

    /** SO 编辑器 Tab（P5）。 */
    data object SoEditor : Screen("so_editor")

    /** 设置 Tab。 */
    data object Settings : Screen("settings")

    /** MCP 服务器日志页。 */
    data object McpLog : Screen("mcp_log")

    /** SO 编辑器详情页（带文件路径、可选偏移和方法长度）。 */
    data object SoEditorDetail : Screen("so_editor/{filePath}?offset={offset}&length={length}") {
        /**
         * 路径用 URL-safe base64 编码（不含 / + %），避免 Navigation 路径参数
         * 对 %2F 的自动解码导致路由匹配失败/参数损坏。
         *
         * @param length 方法字节长度；>0 时 SO 编辑器只展示该方法范围（来自 ASM 跳转）
         */
        fun createRoute(filePath: String, offset: Long = 0L, length: Long = 0L): String {
            val encoded = android.util.Base64.encodeToString(
                filePath.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            return "so_editor/$encoded?offset=$offset&length=$length"
        }
    }

    /** 项目详情页（项目信息 + 分析记录 + SO 文件）。 */
    data object ProjectDetail : Screen("project_detail/{projectId}") {
        fun createRoute(projectId: Long): String = "project_detail/$projectId"
    }

    /** PP 条目浏览页。 */
    data object PpBrowser : Screen("pp_browser/{analysisId}") {
        fun createRoute(analysisId: Long): String = "pp_browser/$analysisId"
    }

    /** ASM 方法列表页（按方法浏览 Blutter 反汇编伪代码）。 */
    data object AsmList : Screen("asm_list/{analysisId}") {
        fun createRoute(analysisId: Long): String = "asm_list/$analysisId"
    }

    /** ASM 内容查看页（单方法反汇编伪代码）。 */
    data object AsmBrowser : Screen("asm_browser/{analysisId}/{methodId}") {
        fun createRoute(analysisId: Long, methodId: Long): String = "asm_browser/$analysisId/$methodId"
    }
}

/** 顶层 Tab 列表（顺序决定显示顺序）。 */
val TopLevelTabs: List<Screen> = listOf(
    Screen.Projects,
    Screen.SoEditor,
    Screen.Settings,
)
