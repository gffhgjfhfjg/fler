package com.ai.fler.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 关于页：应用信息 + 引用的开源项目 + 开发语言与第三方库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionCard(title = "应用", icon = Icons.Default.Info) {
                    AboutRow("名称", "Fler")
                    AboutRow("版本", "${com.ai.fler.BuildConfig.VERSION_NAME} (${com.ai.fler.BuildConfig.VERSION_CODE})")
                    AboutRow("类型", "Dart/Flutter 逆向分析工具")
                }
            }

            item {
                SectionCard(title = "开发语言", icon = Icons.Default.Code) {
                    AboutRow("Android 端", "Kotlin + Jetpack Compose (Material 3)")
                    AboutRow("Native 层", "C / C++（JNI 桥接 ELF 解析 / 汇编 / 反汇编）")
                }
            }

            item {
                Text(
                    text = "引用的开源项目",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(openSourceProjects) { project ->
                ProjectCard(project)
            }

            item {
                Text(
                    text = "主要第三方库",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                SectionCard(title = "Android / Kotlin 生态") {
                    AboutRow("Room", "本地分析数据持久化（SQLite ORM）")
                    AboutRow("Hilt", "依赖注入")
                    AboutRow("OkHttp", "网络请求（引擎包下载/更新检测）")
                    AboutRow("kotlinx-serialization", "JSON 序列化（MCP 协议）")
                    AboutRow("Compose Material 3", "UI 组件")
                    AboutRow("Navigation Compose", "页面导航")
                    AboutRow("Commons-Compress / XZ", "引擎包 7z 解压")
                    AboutRow("DocumentFile", "SAF 文件访问（导入/导出）")
                }
            }
        }
    }
}

private data class OpenSourceProject(
    val name: String,
    val license: String,
    val description: String,
)

private val openSourceProjects = listOf(
    OpenSourceProject(
        name = "Blutter",
        license = "Apache-2.0",
        description = "Dart/Flutter 逆向分析引擎（反汇编伪代码、类/方法/PP 条目解析）",
    ),
    OpenSourceProject(
        name = "Keystone",
        license = "BSD-3-Clause",
        description = "AArch64 汇编器（SO 编辑器指令编码）",
    ),
    OpenSourceProject(
        name = "Capstone",
        license = "BSD-3-Clause",
        description = "多架构反汇编框架（ARM64 指令反汇编）",
    ),
)

@Composable
private fun ProjectCard(project: OpenSourceProject) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = project.license,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = project.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
