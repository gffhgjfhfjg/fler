package com.ai.fler.features.output

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.data.entity.PpEntry
import com.ai.fler.feature.output.FilterType
import com.ai.fler.feature.output.PpBrowserViewModel

/**
 * PP（Patch Point）浏览器界面。
 *
 * PP 文件特性：
 * - 每条 PP 记录了 Dart 对象池中的一个条目位置（vmOffset + fileOffset）
 * - isLeaf 区分叶子节点（无调用者）与中间节点
 * - type 标识条目类型（String/Type/Stub/Field 等）
 * - description 是 Blutter 输出的可读描述
 *
 * 重新设计：紧凑列表 + 类型分组筛选 + 行内 SO 定位按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PpBrowserScreen(
    analysisId: Long,
    onBack: () -> Unit = {},
    onLocateInSo: (Long, Long) -> Unit = { _, _ -> },
    viewModel: PpBrowserViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ppEntries by viewModel.ppEntries.collectAsStateWithLifecycle()
    val filterType by viewModel.filterType.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PP 浏览") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载中...")
                    }
                }
                uiState.errorMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("加载失败", color = Color.Red, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(uiState.errorMessage!!, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                else -> {
                    PpBrowserContent(
                        entries = ppEntries,
                        filterType = filterType,
                        onFilterChange = { viewModel.setFilter(it) },
                        searchQuery = query,
                        onSearchChange = { query = it; viewModel.setSearchQuery(it) },
                        onLocateInSo = onLocateInSo
                    )
                }
            }
        }
    }
}

@Composable
private fun PpBrowserContent(
    entries: List<PpEntry>,
    filterType: FilterType,
    onFilterChange: (FilterType) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onLocateInSo: (Long, Long) -> Unit
) {
    val filtered = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) entries
        else entries.filter {
            it.description?.contains(searchQuery, true) == true ||
                it.type?.contains(searchQuery, true) == true ||
                it.vmOffset.toString(16).contains(searchQuery, true) ||
                it.fileOffset.toString(16).contains(searchQuery, true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 筛选 + 搜索栏（紧凑）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(filterType == FilterType.ALL, "全部") { onFilterChange(FilterType.ALL) }
            Spacer(Modifier.width(6.dp))
            FilterChip(filterType == FilterType.LEAVES, "叶子") { onFilterChange(FilterType.LEAVES) }
            Spacer(Modifier.width(6.dp))
            FilterChip(filterType == FilterType.TOP_CALLERS, "Top调用") { onFilterChange(FilterType.TOP_CALLERS) }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            placeholder = { Text("搜索描述/类型/地址...") },
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall
        )

        // 统计行（紧凑）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("${filtered.size} 条", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("叶子 ${filtered.count { it.isLeaf }}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无匹配 PP 条目", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filtered, key = { it.id }) { entry ->
                    PpEntryRow(entry, onLocateInSo)
                }
            }
        }
    }
}

@Composable
private fun FilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
private fun PpEntryRow(
    entry: PpEntry,
    onLocateInSo: (Long, Long) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            // 第一行：类型徽章 + 描述 + SO 定位按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 类型徽章
                entry.type?.takeIf { it.isNotBlank() }?.let { type ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                // 叶子标记
                if (entry.isLeaf) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF4CAF50).copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text("leaf", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                // 描述
                Text(
                    text = entry.description ?: "PP #${entry.id}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // SO 定位按钮
                IconButton(
                    onClick = { onLocateInSo(entry.vmOffset, entry.fileOffset) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = "在 SO 中定位",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 第二行：偏移信息（紧凑）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OffsetChip("VM", "0x${entry.vmOffset.toString(16)}")
                OffsetChip("File", "0x${entry.fileOffset.toString(16)}")
                if (entry.functionSize > 0) {
                    OffsetChip("Size", "${entry.functionSize}")
                }
                if (entry.callerCount > 0) {
                    OffsetChip("Callers", "${entry.callerCount}")
                }
            }
        }
    }
}

@Composable
private fun OffsetChip(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
