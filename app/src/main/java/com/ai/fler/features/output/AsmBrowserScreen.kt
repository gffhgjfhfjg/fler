package com.ai.fler.features.output

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.feature.output.AsmBrowserUiState
import com.ai.fler.feature.output.AsmBrowserViewModel
import com.ai.fler.ui.components.EmptyState
import com.ai.fler.ui.components.ErrorState

/**
 * ASM 浏览器界面。
 *
 * @param analysisId 分析记录 ID
 * @param methodId 方法 ID（0=全部方法）
 * @param onBack 返回回调
 * @param onEditInSo 在 SO 编辑器中打开回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsmBrowserScreen(
    analysisId: Long,
    methodId: Long = 0L,
    onBack: () -> Unit = {},
    onEditInSo: (Long) -> Unit = {},
    viewModel: AsmBrowserViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.fileName.ifBlank { "ASM 浏览" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    Text(
                        text = "#$analysisId",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { onEditInSo(methodId) }) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "在 SO 中编辑"
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新"
                        )
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
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "加载中...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                uiState.errorMessage != null -> {
                    ErrorState(
                        message = uiState.errorMessage!!,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                uiState.lines.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.ArrowBack,
                        title = "暂无 ASM 内容",
                        message = "请先完成分析，ASM 汇编结果将在此显示",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                else -> {
                    AsmContent(
                        uiState = uiState,
                        searchQuery = searchQuery,
                        onSearchChange = { viewModel.setSearchQuery(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AsmContent(
    uiState: AsmBrowserUiState,
    searchQuery: String,
    onSearchChange: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        SearchBar(
            query = searchQuery,
            onQueryChange = onSearchChange,
            lineCount = uiState.lineCount,
            modifier = Modifier.fillMaxWidth()
        )

        AsmLineList(
            lines = uiState.lines,
            searchQuery = searchQuery,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    lineCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("搜索汇编指令...") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "$lineCount 行",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AsmLineList(
    lines: List<String>,
    searchQuery: String,
    modifier: Modifier = Modifier
) {
    val filteredLines by remember(lines, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                lines.mapIndexed { index, line ->
                    AsmLineItem(number = index + 1, text = line, isMatch = false)
                }
            } else {
                lines.mapIndexed { index, line ->
                    AsmLineItem(
                        number = index + 1,
                        text = line,
                        isMatch = line.contains(searchQuery, ignoreCase = true)
                    )
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .horizontalScroll(rememberScrollState()),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        items(
            items = filteredLines,
            key = { it.number }
        ) { item ->
            AsmLineRow(item = item)
        }
    }
}

private data class AsmLineItem(
    val number: Int,
    val text: String,
    val isMatch: Boolean
)

@Composable
private fun AsmLineRow(item: AsmLineItem) {
    val backgroundColor = when {
        item.isMatch -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = item.number.toString().padStart(4, ' '),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.width(48.dp)
        )
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (item.isMatch) FontWeight.Bold else FontWeight.Normal,
            color = if (item.isMatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}