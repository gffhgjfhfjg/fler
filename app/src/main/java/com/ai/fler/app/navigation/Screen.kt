package com.ai.fler.app.navigation

/**
 * 根导航路由定义。
 *
 * 顶层 3 个 Tab 为 Bottom Navigation 的 destination；
 * 后续 P3-P5 的详情页通过同名 route 的子路径挂在 NavGraph 上。
 *
 * 注意：
 * 1.「产物」Tab 已移除——其内容（分析记录列表 + PP/ASM 入口）与
 *    ProjectDetailScreen 高度重复，因此产物浏览统一从项目详情页进入。
 * 2.「SO 编辑器」Tab 已移除——SO 编辑统一从项目详情页的 SO 列表进入（immersive=true），
 *    导航栏不再提供无参数的独立入口。
 */
sealed class Screen(val route: String) {
    /** 项目管理 Tab（P3）。 */
    data object Projects : Screen("projects")

    /** SO 编辑器（二级页，从项目详情/PP/ASM 上下文进入，immersive=true 时隐藏底部导航栏）。 */
    data object SoEditor : Screen("so_editor?filePath={filePath}&offset={offset}&length={length}&immersive={immersive}") {
        /**
         * 路径用 URL-safe base64 编码（不含 / + %），避免 Navigation 对路径参数
         * 自动解码导致路由匹配失败/参数损坏。
         *
         * @param offset 定位偏移（文件偏移坐标）
         * @param length 方法字节长度；>0 时 SO 编辑器只展示该方法范围（来自 ASM 跳转）
         * @param immersive true=上下文进入（项目/PP/ASM），隐藏底部导航栏并显示返回键
         */
        fun createRoute(
            filePath: String = "",
            offset: Long = 0L,
            length: Long = 0L,
            immersive: Boolean = false,
        ): String {
            // 纯 Tab 入口：不带 query，全部走路由默认值
            if (filePath.isBlank()) return "so_editor"
            val encoded = android.util.Base64.encodeToString(
                filePath.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            return "so_editor?filePath=$encoded&offset=$offset&length=$length&immersive=$immersive"
        }
    }

    /** 设置 Tab。 */
    data object Settings : Screen("settings")

    /** MCP 服务器日志页（顶层 Tab）。 */
    data object McpLog : Screen("mcp_log")

    /** MCP 服务器配置页（二级 Screen，从设置页进入）。 */
    data object McpSettings : Screen("mcp_settings")

    /** MCP 调用统计页（二级 Screen，从 MCP 配置页进入）。 */
    data object McpStats : Screen("mcp_stats")

    /** 关于页（开源项目与第三方库说明）。 */
    data object About : Screen("about")

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

    /** Hook 脚本管理页（二级 Screen，从设置页进入）。 */
    data object HookScripts : Screen("hook_scripts")

    /** Hook 脚本编辑页（新建 id=0 / 编辑 id>0）。 */
    data object HookScriptEdit : Screen("hook_script_edit/{scriptId}") {
        fun createRoute(scriptId: Long): String = "hook_script_edit/$scriptId"
    }
}

/** 顶层 Tab 列表（顺序决定显示顺序）。 */
val TopLevelTabs: List<Screen> = listOf(
    Screen.Projects,
    Screen.McpLog,
    Screen.Settings,
)
