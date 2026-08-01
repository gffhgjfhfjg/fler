package com.ai.fler.core.mcp

/** MCP resource 描述（resources/list 返回）。 */
data class McpResource(
    val uri: String,
    val name: String,
    val mimeType: String,
)

/** MCP resources 数据源：由 [McpToolHandlers] 实现（复用其注入的分析 DAO）。 */
interface McpResourceProvider {
    suspend fun listResources(): List<McpResource>
    suspend fun readResource(uri: String): String?
}
