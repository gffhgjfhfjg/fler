package com.ai.fler.features.so_editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.ui.animation.AnimDuration
import com.ai.fler.ui.animation.AnimEasing
import kotlinx.coroutines.launch

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
    val flashTrigger by viewModel.flashTrigger.collectAsStateWithLifecycle()

    var inputOffset by remember { mutableStateOf("") }
    var selectedByteIndex by remember { mutableStateOf(-1) }
    var newByteValue by remember { mutableStateOf("") }
    var writeStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // 初始加载数据
    LaunchedEffect(Unit) {
        if (hexData.data.isEmpty()) {
            viewModel.loadHexData(selectedOffset)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 地址输入和导航
        HexNavigationBar(
            inputOffset = inputOffset,
            onInputOffsetChange = { inputOffset = it },
            onJumpToOffset = {
                val raw = inputOffset.toLongOrNull(16) ?: inputOffset.toLongOrNull() ?: 0L
                // 粘贴虚拟地址（如长按菜单复制的函数地址）时自动换算成文件偏移
                scope.launch {
                    val offset = viewModel.resolveJumpAddress(raw)
                    viewModel.loadHexData(offset)
                    viewModel.setFlashOffset(offset)
                }
            },
            onPrevPage = {
                viewModel.loadHexData((hexData.offset - 256).coerceAtLeast(0))
            },
            onNextPage = {
                viewModel.loadHexData(hexData.offset + 256)
            }
        )

        // Hex 数据显示
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
                    flashTrigger = flashTrigger,
                    onByteClick = { index ->
                        selectedByteIndex = index
                        newByteValue = ""
                        writeStatus = ""
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }

        // 选中字节信息 + 写入（底部固定栏）
        if (selectedByteIndex >= 0 && selectedByteIndex < hexData.data.size) {
            val byteOffset = hexData.offset + selectedByteIndex
            SelectedByteInfo(
                byteValue = hexData.data[selectedByteIndex],
                offset = byteOffset
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newByteValue,
                    onValueChange = { input ->
                        // 只保留 hex 字符，最多 2 位
                        val filtered = input.filter { it in "0123456789abcdefABCDEF" }.take(2)
                        newByteValue = filtered.uppercase()
                        writeStatus = ""
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("新字节 (如 FF)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
                Button(
                    onClick = {
                        val value = newByteValue.toIntOrNull(16)
                        if (value != null && value in 0..255) {
                            val hexInput = newByteValue
                            scope.launch {
                                val ok = viewModel.writeByte(byteOffset, value.toByte())
                                writeStatus = if (ok) "已写入 0x$hexInput" else "写入失败"
                            }
                            newByteValue = ""
                        } else {
                            writeStatus = "无效值: $newByteValue"
                        }
                    },
                    enabled = newByteValue.length == 2
                ) {
                    Text("写入")
                }
            }
            if (writeStatus.isNotEmpty()) {
                Text(
                    text = writeStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (writeStatus.startsWith("已写入")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp)
                )
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
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrevPage,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = "上一页"
            )
        }

        // 跳转通过键盘 IME「完成/搜索」键触发，布局保持左右对称
        CompactTextField(
            value = inputOffset,
            onValueChange = onInputOffsetChange,
            modifier = Modifier.weight(1f),
            placeholder = "偏移 (hex 或 dec)",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onJumpToOffset() })
        )

        IconButton(
            onClick = onNextPage,
            modifier = Modifier.size(28.dp)
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
    flashTrigger: Int,
    onByteClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val bytesPerRow = 8
    val rows = (data.size + bytesPerRow - 1) / bytesPerRow
    val listState = rememberLazyListState()

    // 闪烁地址所在行滚动到可视区（flashTrigger 保证跳同一地址也重新定位）
    LaunchedEffect(flashOffset, flashTrigger) {
        val fo = flashOffset ?: return@LaunchedEffect
        val idx = (fo - baseOffset).toInt()
        if (idx in 0 until data.size) {
            listState.animateScrollToItem(idx / bytesPerRow)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(vertical = 2.dp)
    ) {
        items(count = rows) { rowIndex ->
            val startIndex = rowIndex * bytesPerRow
            val endIndex = minOf(startIndex + bytesPerRow, data.size)
            val rowOffset = baseOffset + startIndex

            // 切片数组缓存：data 只在翻页/补丁后替换引用，行重组时复用切片避免重复分配
            val rowBytes = remember(data, startIndex, endIndex) { data.sliceArray(startIndex until endIndex) }

            HexRow(
                rowOffset = rowOffset,
                bytes = rowBytes,
                startIndex = startIndex,
                selectedByteIndex = selectedByteIndex,
                patchedOffsets = patchedOffsets,
                flashOffset = flashOffset,
                flashTrigger = flashTrigger,
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
    flashTrigger: Int,
    onByteClick: (Int) -> Unit
) {
    // 闪烁目标是否在本行：行级动画，只有命中行参与脉冲，避免全列表重组
    val rowFlashActive = flashOffset != null &&
        flashOffset >= rowOffset && flashOffset < rowOffset + bytes.size
    val pulseAlpha = remember { Animatable(0f) }
    LaunchedEffect(rowFlashActive, flashTrigger) {
        if (!rowFlashActive) {
            pulseAlpha.snapTo(0f)
            return@LaunchedEffect
        }
        pulseAlpha.snapTo(0f)
        repeat(2) {
            pulseAlpha.animateTo(1f, tween(AnimDuration.fast, easing = AnimEasing.entry))
            pulseAlpha.animateTo(0f, tween(AnimDuration.fast, easing = AnimEasing.exit))
        }
    }
    val flashAlpha = pulseAlpha.value
    // 显示字符串缓存：行重组时复用，避免重复格式化分配
    val rowLabel = remember(rowOffset) { rowOffset.toString(16).uppercase().padStart(8, '0') }
    val byteHexCache = remember(bytes) { bytes.map { it.toUByte().toString(16).uppercase().padStart(2, '0') } }
    val asciiCache = remember(bytes) { bytes.map { if (it in 32..126) it.toChar().toString() else "." } }
    val asciiJoined = remember(bytes) { asciiCache.joinToString("").padEnd(8, ' ') }
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 地址列 - 固定宽度，8 位十六进制 mono
        Text(
            text = rowLabel,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = scheme.primary,
            modifier = Modifier.width(72.dp),
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(6.dp))

        // 字节列 - 弹性填充，8 字节按 4+4 分组，中间留间隙
        Row(modifier = Modifier.weight(1f)) {
            for (i in 0 until 8) {
                if (i == 4) {
                    Spacer(modifier = Modifier.width(10.dp))
                }
                if (i < bytes.size) {
                    val byteIndex = startIndex + i
                    val isSelected = byteIndex == selectedByteIndex
                    val byteValue = bytes[i]
                    val isPatched = rowOffset + i in patchedOffsets
                    val isFlash = rowOffset + i == flashOffset

                    // 只有高亮/选中/补丁字节才需要底色与圆角裁剪，普通字节直接点击，省掉每格 clip+background
                    val bgColor = when {
                        isFlash -> Color(0xFFD32F2F).copy(alpha = flashAlpha)
                        isSelected -> scheme.primary
                        isPatched -> scheme.errorContainer.copy(alpha = 0.7f)
                        else -> null
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(22.dp)
                            .then(if (bgColor != null) Modifier.clip(RoundedCornerShape(3.dp)).background(bgColor) else Modifier)
                            .clickable { onByteClick(byteIndex) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = byteHexCache[i],
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            color = when {
                                isSelected -> scheme.onPrimary
                                else -> if (byteValue in 32..126) scheme.onSurface else scheme.onSurfaceVariant
                            }
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(modifier = Modifier.width(6.dp))

        // ASCII 列 - 固定宽度，单 Text 输出整行字符（省掉 8 个 Text 节点）
        Text(
            text = asciiJoined,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp)
        )
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
            .padding(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "偏移",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "0x${offset.toString(16).uppercase()}",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "十六进制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "0x${byteValue.toUByte().toString(16).uppercase().padStart(2, '0')}",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "十进制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = byteValue.toUByte().toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "字符",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (byteValue in 32..126) byteValue.toChar().toString() else "不可打印",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
