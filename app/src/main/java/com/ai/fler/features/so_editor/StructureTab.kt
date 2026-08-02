package com.ai.fler.features.so_editor

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.core.analysis.FunctionInfo
import com.ai.fler.core.analysis.SectionInfo
import com.ai.fler.core.analysis.StringInfo
import com.ai.fler.core.analysis.SymbolInfo
import com.ai.fler.core.analysis.SymbolType
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * ELF 结构 Tab（Engine 抽象层版本）。
 *
 * 功能增强：
 * - 每个子Tab独立保存/恢复 LazyColumn 滚动位置（切到汇编再回来不丢位置）
 * - 从汇编切回时，上次点击的函数/符号行高亮闪烁3次
 * - 列表完整显示（不再 take(500)），由 LazyColumn 虚拟化保证性能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StructureTab(
    sections: List<SectionInfo>,
    symbols: List<SymbolInfo>,
    dynamicSymbols: List<SymbolInfo>,
    functions: List<FunctionInfo>,
    strings: List<StringInfo>,
    onSectionClick: (SectionInfo) -> Unit,
    onSymbolClick: (SymbolInfo) -> Unit,
    onFunctionClick: (FunctionInfo) -> Unit,
    onStringsTabSelected: () -> Unit = {},
    viewModel: SoEditorViewModel,
    modifier: Modifier = Modifier
) {
    var selectedSubTab by remember { mutableStateOf(StructureSubTab.SECTIONS) }
    val flashAddress by viewModel.structureFlashAddress.collectAsStateWithLifecycle()

    // 每个子Tab的LazyListState（key=ordinal）
    val listStates: Map<Int, LazyListState> = remember {
        StructureSubTab.values().associate { it.ordinal to LazyListState(0, 0) }
    }

    // 子Tab切换：恢复滚动位置 + 如果有 flashAddress 滚动到该行
    LaunchedEffect(selectedSubTab) {
        val ordinal = selectedSubTab.ordinal
        val state = listStates[ordinal] ?: return@LaunchedEffect
        // 1) 恢复保存的滚动位置
        viewModel.getStructureScroll(ordinal)?.let { (idx, off) ->
            state.requestScrollToItem(idx, off)
        }
        // 2) 切回时触发闪烁动画（ViewModel里的定时器开始toggle flashAddress）
        if (ordinal == StructureSubTab.FUNCTIONS.ordinal ||
            ordinal == StructureSubTab.SYMBOLS.ordinal ||
            ordinal == StructureSubTab.DYNAMIC_SYMBOLS.ordinal
        ) {
            viewModel.triggerStructureFlash()
        }
    }

    // 监听每个子Tab的滚动：保存到 ViewModel
    StructureSubTab.values().forEach { sub ->
        val ordinal = sub.ordinal
        val state = listStates[ordinal] ?: return@forEach
        LaunchedEffect(state) {
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                .distinctUntilChanged()
                .collect { (idx, off) ->
                    viewModel.saveStructureScroll(ordinal, idx, off)
                }
        }
    }

    // flashAddress 变化时（toggle闪烁），把该行滚动到可视区
    LaunchedEffect(flashAddress) {
        val addr = flashAddress ?: return@LaunchedEffect
        val ordinal = selectedSubTab.ordinal
        val state = listStates[ordinal] ?: return@LaunchedEffect
        val list: List<Pair<Long, Any>> = when (selectedSubTab) {
            StructureSubTab.FUNCTIONS -> functions.map { it.vaddr to it }
            StructureSubTab.SYMBOLS -> symbols.map { it.address to it }
            StructureSubTab.DYNAMIC_SYMBOLS -> dynamicSymbols.map { it.address to it }
            StructureSubTab.SECTIONS -> sections.map { it.address to it }
            StructureSubTab.STRINGS -> strings.map { it.address to it }
        }
        val idx = list.indexOfFirst { it.first == addr }
        if (idx >= 0) {
            // 稍等1帧等恢复位置先执行完
            kotlinx.coroutines.delay(50)
            state.animateScrollToItem(idx.coerceAtLeast(0))
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedSubTab.ordinal) {
            Tab(
                selected = selectedSubTab == StructureSubTab.SECTIONS,
                onClick = { selectedSubTab = StructureSubTab.SECTIONS },
                text = { Text("节区 (${sections.size})") }
            )
            Tab(
                selected = selectedSubTab == StructureSubTab.SYMBOLS,
                onClick = { selectedSubTab = StructureSubTab.SYMBOLS },
                text = { Text("符号 (${symbols.size})") }
            )
            Tab(
                selected = selectedSubTab == StructureSubTab.DYNAMIC_SYMBOLS,
                onClick = { selectedSubTab = StructureSubTab.DYNAMIC_SYMBOLS },
                text = { Text("动态符号 (${dynamicSymbols.size})") }
            )
            Tab(
                selected = selectedSubTab == StructureSubTab.FUNCTIONS,
                onClick = { selectedSubTab = StructureSubTab.FUNCTIONS },
                text = { Text("函数 (${functions.size})") }
            )
            Tab(
                selected = selectedSubTab == StructureSubTab.STRINGS,
                onClick = {
                    selectedSubTab = StructureSubTab.STRINGS
                    onStringsTabSelected()
                },
                text = { Text("字符串 (${strings.size})") }
            )
        }

        when (selectedSubTab) {
            StructureSubTab.SECTIONS -> SectionsList(
                sections, onSectionClick,
                listState = listStates[StructureSubTab.SECTIONS.ordinal]!!,
                flashAddress = flashAddress,
                modifier = Modifier.fillMaxSize()
            )
            StructureSubTab.SYMBOLS -> SymbolsList(
                symbols, onSymbolClick,
                listState = listStates[StructureSubTab.SYMBOLS.ordinal]!!,
                flashAddress = flashAddress,
                modifier = Modifier.fillMaxSize()
            )
            StructureSubTab.DYNAMIC_SYMBOLS -> SymbolsList(
                dynamicSymbols, onSymbolClick,
                listState = listStates[StructureSubTab.DYNAMIC_SYMBOLS.ordinal]!!,
                flashAddress = flashAddress,
                modifier = Modifier.fillMaxSize()
            )
            StructureSubTab.FUNCTIONS -> FunctionsList(
                functions, onFunctionClick,
                listState = listStates[StructureSubTab.FUNCTIONS.ordinal]!!,
                flashAddress = flashAddress,
                modifier = Modifier.fillMaxSize()
            )
            StructureSubTab.STRINGS -> StringsList(
                strings,
                listState = listStates[StructureSubTab.STRINGS.ordinal]!!,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private enum class StructureSubTab { SECTIONS, SYMBOLS, DYNAMIC_SYMBOLS, FUNCTIONS, STRINGS }

/**
 * 闪烁背景色动画：如果 isFlash=true 则用醒目的橙黄色容器色，否则透明。
 * 在 ViewModel 里定时切换 flashAddress（存在→null→存在...） → 驱动这里自动变色。
 */
@Composable
private fun flashRowBackgroundColor(isFlash: Boolean): State<Color> {
    val target: Color = if (isFlash) {
        // 橙黄高亮光 + 55% 透明度（在行背景上叠一层闪烁）
        Color(0xFFFFA726).copy(alpha = 0.55f)
    } else {
        Color.Transparent
    }
    return animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 120)
    )
}

// ==================================================================
// 节区列表
// ==================================================================

@Composable
private fun SectionsList(
    sections: List<SectionInfo>,
    onSectionClick: (SectionInfo) -> Unit,
    listState: LazyListState,
    flashAddress: Long?,
    modifier: Modifier = Modifier
) {
    if (sections.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "暂无节区数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = sections, key = { it.name }) { section ->
            val isFlash = flashAddress != null && section.address == flashAddress
            val flashBg by flashRowBackgroundColor(isFlash)
            Box(Modifier.background(flashBg)) {
                SectionCard(section = section, onClick = { onSectionClick(section) })
            }
        }
    }
}

@Composable
private fun SectionCard(section: SectionInfo, onClick: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = section.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                SectionTypeBadge(typeInt = section.typeInt, typeName = section.type)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SectionInfo(
                    label = "地址",
                    value = "0x${section.address.toString(16).uppercase()}",
                    modifier = Modifier.weight(1f)
                )
                SectionInfo(
                    label = "偏移",
                    value = "0x${section.offset.toString(16).uppercase()}",
                    modifier = Modifier.weight(1f)
                )
                SectionInfo(
                    label = "大小",
                    value = section.size.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "标志: 0x${section.flags.toString(16).uppercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (section.size > 0) {
                        androidx.compose.material3.TextButton(onClick = onClick) {
                            Text("查看数据")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val perm = section.perm.ifBlank {
                        val sb = StringBuilder(3)
                        if (section.flags and SHF_READ != 0L) sb.append('r') else sb.append('-')
                        if (section.flags and SHF_WRITE != 0L) sb.append('w') else sb.append('-')
                        if (section.flags and SHF_EXECINSTR != 0L) sb.append('x') else sb.append('-')
                        sb.toString()
                    }
                    if (perm[0] == 'r') FlagChip("R")
                    if (perm[1] == 'w') FlagChip("W")
                    if (perm[2] == 'x') FlagChip("X")
                }
            }
        }
    }
}

private const val SHF_WRITE = 1L
private const val SHF_ALLOC = 2L
private const val SHF_EXECINSTR = 4L
private const val SHF_READ = 1L

@Composable
private fun SectionTypeBadge(typeInt: Int, typeName: String = "") {
    val pair: Pair<String, Color> = when {
        typeName.isNotBlank() -> typeName.uppercase().let { n ->
            n to when {
                n.contains("PROGBITS") -> Color(0xFF4CAF50)
                n.contains("SYMTAB") -> Color(0xFF2196F3)
                n.contains("STRTAB") -> Color(0xFF00BCD4)
                n.contains("DYNSYM") || n == "DYNSYM" -> Color(0xFF9C27B0)
                n.contains("NOBITS") -> Color(0xFFFF9800)
                n.contains("RELA") || n.contains("REL") -> Color(0xFFF44336)
                else -> Color.Gray
            }
        }
        else -> when (typeInt) {
            1 -> "PROGBITS" to Color(0xFF4CAF50)
            2 -> "SYMTAB" to Color(0xFF2196F3)
            3 -> "STRTAB" to Color(0xFF00BCD4)
            11 -> "DYNSYM" to Color(0xFF9C27B0)
            8 -> "NOBITS" to Color(0xFFFF9800)
            4 -> "RELA" to Color(0xFFF44336)
            else -> "TYPE_$typeInt" to Color.Gray
        }
    }
    Text(
        text = pair.first,
        style = MaterialTheme.typography.labelSmall,
        color = pair.second,
        modifier = Modifier
            .background(pair.second.copy(alpha = 0.1f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun SectionInfo(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun FlagChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .background(Color(0xFF607D8B))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

// ==================================================================
// 符号列表（静态 + 动态共用）
// ==================================================================

@Composable
private fun SymbolsList(
    symbols: List<SymbolInfo>,
    onSymbolClick: (SymbolInfo) -> Unit,
    listState: LazyListState,
    flashAddress: Long?,
    modifier: Modifier = Modifier
) {
    if (symbols.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "暂无符号数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(
            items = symbols,
            key = { "${it.name}_${it.address}" }
        ) { symbol ->
            val isFlash = flashAddress != null && symbol.address == flashAddress
            val flashBg by flashRowBackgroundColor(isFlash)
            SymbolRow(
                symbol = symbol,
                flashBg = flashBg,
                onClick = { onSymbolClick(symbol) }
            )
        }
    }
}

@Composable
private fun SymbolRow(
    symbol: SymbolInfo,
    flashBg: Color = Color.Transparent,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(flashBg)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SymbolTypeBadge(type = symbol.type)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = (symbol.demangledName?.ifBlank { null } ?: symbol.name).ifBlank { "<unnamed>" },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "0x${symbol.address.toString(16).uppercase().padStart(16, '0')}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (symbol.size > 0) {
            Text(
                text = "${symbol.size}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SymbolTypeBadge(type: SymbolType) {
    val (text, color) = when (type) {
        SymbolType.FUNC -> "FUNC" to Color(0xFF4CAF50)
        SymbolType.OBJECT -> "OBJ" to Color(0xFF2196F3)
        SymbolType.SECTION -> "SECT" to Color(0xFFFF9800)
        SymbolType.FILE -> "FILE" to Color.Gray
        SymbolType.TLS -> "TLS" to Color(0xFF795548)
        SymbolType.COMMON -> "COMMON" to Color(0xFF3F51B5)
        SymbolType.NOTYPE -> "NOTYPE" to Color.Gray
        SymbolType.UNKNOWN -> "?" to Color.Gray
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

// ==================================================================
// 函数列表
// ==================================================================

@Composable
private fun FunctionsList(
    functions: List<FunctionInfo>,
    onFunctionClick: (FunctionInfo) -> Unit,
    listState: LazyListState,
    flashAddress: Long?,
    modifier: Modifier = Modifier
) {
    if (functions.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "暂无函数数据（需 Rizin 引擎 + aaa 分析）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(
            items = functions,
            key = { "${it.name}_${it.vaddr}" }
        ) { func ->
            val isFlash = flashAddress != null && func.vaddr == flashAddress
            val flashBg by flashRowBackgroundColor(isFlash)
            FunctionRow(
                func = func,
                flashBg = flashBg,
                onClick = { onFunctionClick(func) }
            )
        }
    }
}

@Composable
private fun FunctionRow(
    func: FunctionInfo,
    flashBg: Color = Color.Transparent,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(flashBg)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "FN",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF4CAF50),
            modifier = Modifier
                .background(Color(0xFF4CAF50).copy(alpha = 0.1f))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = func.name.ifBlank { "<unnamed>" },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (func.signature.isNotBlank() && func.signature != func.name) {
                Text(
                    text = func.signature,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "0x${func.vaddr.toString(16).uppercase().padStart(8, '0')}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (func.size > 0) {
                Text(
                    text = "${func.size}B",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ==================================================================
// 字符串列表
// ==================================================================

@Composable
private fun StringsList(
    strings: List<StringInfo>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    if (strings.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "点击此 Tab 自动扫描字符串…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(
            items = strings,
            key = { "${it.address}_${it.string.hashCode()}" }
        ) { str ->
            StringRow(str = str)
        }
    }
}

@Composable
private fun StringRow(str: StringInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = str.string,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Row {
            Text(
                text = "0x${str.address.toString(16).uppercase().padStart(8, '0')}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (str.section.isNotBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = str.section,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (str.size > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${str.size}B",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
