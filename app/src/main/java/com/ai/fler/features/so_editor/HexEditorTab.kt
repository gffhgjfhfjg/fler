package com.ai.fler.features.so_editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Hex 编辑器 Tab。
 *
 * 以传统 Hex 布局展示二进制数据：
 * 偏移 | 00 01 02...0F | ASCII
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HexEditorTab(
    viewModel: SoEditorViewModel,
    modifier: Modifier = Modifier
) {
    val hexData by viewModel.hexData.collectAsStateWithLifecycle()
    val selectedOffset by viewModel.selectedOffset.collectAsStateWithLifecycle()
    val patchedOffsets by viewModel.patchedOffsets.collectAsStateWithLifecycle()
    val flashOffset by viewModel.flashOffset.collectAsStateWithLifecycle()

    var inputOffset by remember { mutableStateOf(selectedOffset.toString()) }
    var selectedByteIndex by remember { mutableStateOf(-1) }

    // 初始加载数据
    LaunchedEffect(Unit) {
        if (hexData.data.isEmpty()) {
            viewModel.loadHexData(selectedOffset)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 地址输入和导航（基于 hexData.offset 翻页，而非 selectedOffset）
        HexNavigationBar(
            inputOffset = inputOffset,
            onInputOffsetChange = { inputOffset = it },
            onJumpToOffset = {
                val offset = inputOffset.toLongOrNull(16) ?: inputOffset.toLongOrNull() ?: 0L
                viewModel.loadHexData(offset)
            },
            onPrevPage = {
                viewModel.loadHexData((hexData.offset - 256).coerceAtLeast(0))
            },
            onNextPage = {
                viewModel.loadHexData(hexData.offset + 256)
            }
        )

        // Hex 数据显示（用 weight 占据剩余空间，保证底部输入栏始终可见）
        when {
            hexData.isLoading -> {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            hexData.data.isEmpty() -> {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                HexDataView(
                    data = hexData.data,
                    baseOffset = hexData.offset,
                    selectedByteIndex = selectedByteIndex,
                    patchedOffsets = patchedOffsets,
                    flashOffset = flashOffset,
                    onByteClick = { index ->
                        selectedByteIndex = index
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }

        // 选中字节信息 + 写入（底部固定栏，不会被导航栏遮挡）
        if (selectedByteIndex >= 0 && selectedByteIndex < hexData.data.size) {
            val byteOffset = hexData.offset + selectedByteIndex
            SelectedByteInfo(
                byteValue = hexData.data[selectedByteIndex],
                offset = byteOffset
            )
            var newByteValue by remember(byteOffset) { mutableStateOf("") }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactTextField(
                    value = newByteValue,
                    onValueChange = { newByteValue = it.take(2) },
                    modifier = Modifier.weight(1f),
                    placeholder = "新字节 (hex)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
                TextButton(
                    onClick = {
                        val value = newByteValue.toIntOrNull(16)
                        if (value != null && value in 0..255) {
                            viewModel.writeByte(byteOffset, value.toByte())
                            newByteValue = ""
                        }
                    },
                    enabled = newByteValue.isNotBlank()
                ) {
                    Text("写入")
                }
            }
        }
    }
}

@Composable
private fun HexNavigationBar(
    inputOffset: String,
    onInputOffsetChange: (String) -> Unit,
    onJumpToOffset: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrevPage,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = "上一页"
            )
        }

                CompactTextField(
                    value = inputOffset,
                    onValueChange = onInputOffsetChange,
                    modifier = Modifier.weight(1f),
                    placeholder = "偏移 (hex 或 dec)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )

        IconButton(
            onClick = onJumpToOffset,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "跳转"
            )
        }

        IconButton(
            onClick = onNextPage,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = "下一页"
            )
        }
    }
}

@Composable
private fun HexDataView(
    data: ByteArray,
    baseOffset: Long,
    selectedByteIndex: Int,
    patchedOffsets: Set<Long>,
    flashOffset: Long?,
    onByteClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val bytesPerRow = 16
    val rows = (data.size + bytesPerRow - 1) / bytesPerRow
    val listState = rememberLazyListState()

    // 撤销跳转：闪烁地址所在行滚动到可视区
    LaunchedEffect(flashOffset) {
        val fo = flashOffset ?: return@LaunchedEffect
        val idx = (fo - baseOffset).toInt()
        if (idx in 0 until data.size) {
            listState.animateScrollToItem(idx / bytesPerRow)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        items(count = rows) { rowIndex ->
            val startIndex = rowIndex * bytesPerRow
            val endIndex = minOf(startIndex + bytesPerRow, data.size)
            val rowOffset = baseOffset + startIndex

            HexRow(
                rowOffset = rowOffset,
                bytes = data.sliceArray(startIndex until endIndex),
                startIndex = startIndex,
                selectedByteIndex = selectedByteIndex,
                patchedOffsets = patchedOffsets,
                flashOffset = flashOffset,
                onByteClick = onByteClick
            )
        }
    }
}

@Composable
private fun HexRow(
    rowOffset: Long,
    bytes: ByteArray,
    startIndex: Int,
    selectedByteIndex: Int,
    patchedOffsets: Set<Long>,
    flashOffset: Long?,
    onByteClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 偏移列
        Text(
            text = "0x${rowOffset.toString(16).uppercase().padStart(8, '0')}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )

        // 字节列
        Row(modifier = Modifier.width(200.dp)) {
            for (i in 0 until 16) {
                if (i < bytes.size) {
                    val byteIndex = startIndex + i
                    val isSelected = byteIndex == selectedByteIndex
                    val byteValue = bytes[i]
                    val isPatched = rowOffset + i in patchedOffsets
                    val isFlash = rowOffset + i == flashOffset

                    Box(
                        modifier = Modifier
                            .width(14.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                when {
                                    isFlash -> Color(0xFFD32F2F)
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    isPatched -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                                    else -> Color.Transparent
                                }
                            )
                            .clickable { onByteClick(byteIndex) }
                            .padding(horizontal = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = byteValue.toUByte().toString(16).uppercase().padStart(2, '0'),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                else -> {
                                    // 非打印字符用深灰色
                                    if (byteValue in 32..126) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                }
                            }
                        )
                    }

                    if (i == 7) {
                        Spacer(modifier = Modifier.width(8.dp))
                    } else if (i < 15) {
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                } else {
                    Spacer(modifier = Modifier.width(14.dp))
                    if (i == 7) {
                        Spacer(modifier = Modifier.width(8.dp))
                    } else if (i < 15) {
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                }
            }
        }

        // ASCII 列
        Spacer(modifier = Modifier.width(8.dp))
        Row(modifier = Modifier.width(100.dp)) {
            for (i in 0 until 16) {
                if (i < bytes.size) {
                    val byteValue = bytes[i]
                    val char = if (byteValue in 32..126) byteValue.toChar() else '.'

                    Text(
                        text = char.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (byteValue in 32..126) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                } else {
                    Text(
                        text = " ",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedByteInfo(
    byteValue: Byte,
    offset: Long
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "偏移",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "0x${offset.toString(16).uppercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Column {
                Text(
                    text = "十六进制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "0x${byteValue.toUByte().toString(16).uppercase().padStart(2, '0')}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Column {
                Text(
                    text = "十进制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = byteValue.toUByte().toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Column {
                Text(
                    text = "字符",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (byteValue in 32..126) byteValue.toChar().toString() else "不可打印",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
