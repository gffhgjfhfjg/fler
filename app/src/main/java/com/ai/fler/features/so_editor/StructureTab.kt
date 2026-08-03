package com.ai.fler.features.so_editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import com.ai.fler.ui.animation.AnimDuration
import com.ai.fler.ui.animation.AnimEasing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.graphicsLayer
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
    val selectedSubTabOrdinal by viewModel.structureSubTab.collectAsStateWithLifecycle()
    val selectedSubTab = StructureSubTab.values().getOrElse(selectedSubTabOrdinal) { StructureSubTab.SECTIONS }
    val flashAddress by viewModel.structureFlashAddress.collectAsStateWithLifecycle()
    val flashTrigger by viewModel.structureFlashTrigger.collectAsStateWithLifecycle()

    // 呼吸脉冲动画：用 Animatable 驱动 0→1→0→1→0（2 次呼吸）
    val pulseAlpha = remember { Animatable(0f) }
    LaunchedEffect(flashAddress, flashTrigger) {
        if (flashAddress == null) return@LaunchedEffect
        pulseAlpha.snapTo(0f)
        // 改成 fast=200ms（总时长 800ms）而非 slow=500ms（2000ms），减少每帧重组时间
        repeat(2) {
            pulseAlpha.animateTo(1f, tween(AnimDuration.fast, easing = AnimEasing.entry))
            pulseAlpha.animateTo(0f, tween(AnimDuration.fast, easing = AnimEasing.exit))
        }
    }

    // 每个子Tab的LazyListState（key=ordinal）
    val listStates: Map<Int, LazyListState> = remember {
        StructureSubTab.values().associate { it.ordinal to LazyListState(0, 0) }
    }

    // 子Tab切换：如有 flashAddr（点函数/符号返回）直接定位目标行，不先恢复旧滚动位置避免抖动
    LaunchedEffect(selectedSubTab) {
        val ordinal = selectedSubTab.ordinal
        val state = listStates[ordinal] ?: return@LaunchedEffect
        val flashAddr = viewModel.structureFlashAddress.value

        if (flashAddr == null) {
            // 只有用户正常手动切换子 Tab（非返回场景）才恢复滚动位置
            viewModel.getStructureScroll(ordinal)?.let { (idx, off) ->
                state.scrollToItem(idx, off)
            }
        }

        if (flashAddr != null) {
            // 直接 indexOfFirst：不分配临时 List（原本 functions.map { it.vaddr } 对 2w 条函数会产生 ~160KB 临时装箱对象，放大 GC）
            val idx = when (selectedSubTab) {
                StructureSubTab.FUNCTIONS -> functions.indexOfFirst { it.vaddr == flashAddr }
                StructureSubTab.SYMBOLS -> symbols.indexOfFirst { it.address == flashAddr }
                StructureSubTab.DYNAMIC_SYMBOLS -> dynamicSymbols.indexOfFirst { it.address == flashAddr }
                StructureSubTab.SECTIONS -> sections.indexOfFirst { it.address == flashAddr }
                StructureSubTab.STRINGS -> strings.indexOfFirst { it.address == flashAddr }
            }
            if (idx >= 0) {
                // 不用 animateScrollToItem（长距离 300ms 平滑滚动），改为同步 scrollToItem
                // 视觉上：转场动画结束时直接把目标行定位到列表中部偏上，立即进入闪烁，流畅度显著更好
                state.scrollToItem(
                    index = idx.coerceAtLeast(0),
                    scrollOffset = -40
                )
            }
            viewModel.triggerStructureFlash()
        }
    }

    // 只监听当前子Tab的滚动：保存到 ViewModel（减少 5 个 collector 到 1 个）
    val currentListState = listStates[selectedSubTab.ordinal]!!
    LaunchedEffect(currentListState) {
        snapshotFlow { currentListState.firstVisibleItemIndex to currentListState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (idx, off) ->
                viewModel.saveStructureScroll(selectedSubTab.ordinal, idx, off)
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ===== 搜索状态（位于 TabRow 上方声明，供下方布局使用） =====
        // 节区数据量小，不开放搜索；切到不可搜索Tab时自动收起搜索栏
        val searchableOrdinals = remember {
            setOf(
                StructureSubTab.SYMBOLS.ordinal,
                StructureSubTab.DYNAMIC_SYMBOLS.ordinal,
                StructureSubTab.FUNCTIONS.ordinal,
                StructureSubTab.STRINGS.ordinal
            )
        }
        val isSearchable = selectedSubTab.ordinal in searchableOrdinals
        var showSearch by remember { mutableStateOf(false) }
        // 每个子Tab独立的搜索词（切Tab时保留各自的查询）
        val searchQueries = remember { mutableStateMapOf<Int, String>() }
        val currentQuery = searchQueries[selectedSubTab.ordinal].orEmpty()
        // 切到不可搜索Tab时自动收起
        LaunchedEffect(isSearchable) {
            if (!isSearchable) showSearch = false
        }

        // ===== ScrollableTabRow：自适应宽度，数量完整可见，可水平滚动 =====
        androidx.compose.material3.ScrollableTabRow(
            selectedTabIndex = selectedSubTab.ordinal,
            edgePadding = 12.dp
        ) {
            Tab(
                selected = selectedSubTab == StructureSubTab.SECTIONS,
                onClick = { viewModel.setStructureSubTab(StructureSubTab.SECTIONS.ordinal) },
                text = { Text("节区 (${sections.size})") }
            )
            Tab(
                selected = selectedSubTab == StructureSubTab.SYMBOLS,
                onClick = { viewModel.setStructureSubTab(StructureSubTab.SYMBOLS.ordinal) },
                text = { Text("符号 (${symbols.size})") }
            )
            Tab(
                selected = selectedSubTab == StructureSubTab.DYNAMIC_SYMBOLS,
                onClick = { viewModel.setStructureSubTab(StructureSubTab.DYNAMIC_SYMBOLS.ordinal) },
                text = { Text("动态符号 (${dynamicSymbols.size})") }
            )
            Tab(
                selected = selectedSubTab == StructureSubTab.FUNCTIONS,
                onClick = { viewModel.setStructureSubTab(StructureSubTab.FUNCTIONS.ordinal) },
                text = { Text("函数 (${functions.size})") }
            )
            Tab(
                selected = selectedSubTab == StructureSubTab.STRINGS,
                onClick = {
                    viewModel.setStructureSubTab(StructureSubTab.STRINGS.ordinal)
                    onStringsTabSelected()
                },
                text = { Text("字符串 (${strings.size})") }
            )
        }

        // ===== 搜索框（仅展开时占行，默认完全不显示，不浪费垂直空间） =====
        AnimatedVisibility(
            visible = showSearch && isSearchable,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            OutlinedTextField(
                value = currentQuery,
                onValueChange = { searchQueries[selectedSubTab.ordinal] = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索 ${selectedSubTab.label()}（名称 / 地址 hex）") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (currentQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQueries[selectedSubTab.ordinal] = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清空")
                        }
                    } else {
                        IconButton(onClick = { showSearch = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭搜索")
                        }
                    }
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                )
            )
        }

        // ===== 列表内容 + 悬浮搜索按钮（FAB 叠在列表右下角） =====

        // 4 个过滤结果用 remember(key) + derivedStateOf：
        // - remember(key = 源List + currentQuery)：任一 key 变化时重跑 init block 重捕获闭包，
        //   修复「搜索词改不了」和「字符串首次加载为空」两条回归 bug。
        // - derivedStateOf：pulseAlpha 每帧变化时 keys 不变 → 缓存命中，不重算 2w 条过滤，性能优化保留。
        val filteredSymbols by remember(symbols, currentQuery) {
            derivedStateOf { filterSymbols(symbols, currentQuery) }
        }
        val filteredDynamicSymbols by remember(dynamicSymbols, currentQuery) {
            derivedStateOf { filterSymbols(dynamicSymbols, currentQuery) }
        }
        val filteredFunctions by remember(functions, currentQuery) {
            derivedStateOf { filterFunctions(functions, currentQuery) }
        }
        val filteredStrings by remember(strings, currentQuery) {
            derivedStateOf { filterStrings(strings, currentQuery) }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedSubTab) {
                StructureSubTab.SECTIONS -> SectionsList(
                    sections, onSectionClick,
                    listState = listStates[StructureSubTab.SECTIONS.ordinal]!!,
                    // 直接传 flashAddress，不再每帧切 null；ListItem 内部 item.addr == flashAddress 自己比较，
                    // 保证 flashAddress 参数稳定，LazyColumn 不会因为入参每帧变而重算所有可见行。
                    flashAddress = flashAddress,
                    flashAlpha = pulseAlpha.value,
                    modifier = Modifier.fillMaxSize()
                )
                StructureSubTab.SYMBOLS -> SymbolsList(
                    filteredSymbols, onSymbolClick,
                    listState = listStates[StructureSubTab.SYMBOLS.ordinal]!!,
                    flashAddress = flashAddress,
                    flashAlpha = pulseAlpha.value,
                    isFiltered = currentQuery.isNotBlank(),
                    modifier = Modifier.fillMaxSize()
                )
                StructureSubTab.DYNAMIC_SYMBOLS -> SymbolsList(
                    filteredDynamicSymbols, onSymbolClick,
                    listState = listStates[StructureSubTab.DYNAMIC_SYMBOLS.ordinal]!!,
                    flashAddress = flashAddress,
                    flashAlpha = pulseAlpha.value,
                    isFiltered = currentQuery.isNotBlank(),
                    modifier = Modifier.fillMaxSize()
                )
                StructureSubTab.FUNCTIONS -> FunctionsList(
                    filteredFunctions, onFunctionClick,
                    listState = listStates[StructureSubTab.FUNCTIONS.ordinal]!!,
                    flashAddress = flashAddress,
                    flashAlpha = pulseAlpha.value,
                    isFiltered = currentQuery.isNotBlank(),
                    modifier = Modifier.fillMaxSize()
                )
                StructureSubTab.STRINGS -> StringsList(
                    filteredStrings,
                    listState = listStates[StructureSubTab.STRINGS.ordinal]!!,
                    isFiltered = currentQuery.isNotBlank(),
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 悬浮搜索按钮：仅可搜索Tab + 搜索框收起时显示，展开时由搜索框自带关闭按钮替代
            if (isSearchable && !showSearch) {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = { showSearch = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                        // 有过滤词时叠加一个小红点提示
                        if (currentQuery.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 2.dp)
                                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(50))
                                    .size(6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================================================================
// 搜索过滤：名称 / demangled / 地址 hex 模糊匹配（大小写不敏感）
// ==================================================================

private fun filterSymbols(list: List<SymbolInfo>, query: String): List<SymbolInfo> {
    if (query.isBlank()) return list
    val lower = query.lowercase()
    return list.filter {
        it.name.contains(query, true) ||
            (it.demangledName?.contains(query, true) == true) ||
            it.address.toString(16).lowercase().contains(lower)
    }
}

private fun filterFunctions(list: List<FunctionInfo>, query: String): List<FunctionInfo> {
    if (query.isBlank()) return list
    val lower = query.lowercase()
    return list.filter {
        it.name.contains(query, true) ||
            it.signature.contains(query, true) ||
            it.vaddr.toString(16).lowercase().contains(lower)
    }
}

private fun filterStrings(list: List<StringInfo>, query: String): List<StringInfo> {
    if (query.isBlank()) return list
    val lower = query.lowercase()
    return list.filter {
        it.string.contains(query, true) ||
            it.address.toString(16).lowercase().contains(lower) ||
            it.section.contains(query, true)
    }
}

/** 子Tab 显示标签（用于搜索框 placeholder）。 */
private fun StructureSubTab.label(): String = when (this) {
    StructureSubTab.SECTIONS -> "节区"
    StructureSubTab.SYMBOLS -> "符号"
    StructureSubTab.DYNAMIC_SYMBOLS -> "动态符号"
    StructureSubTab.FUNCTIONS -> "函数"
    StructureSubTab.STRINGS -> "字符串"
}

private enum class StructureSubTab { SECTIONS, SYMBOLS, DYNAMIC_SYMBOLS, FUNCTIONS, STRINGS }

/** 呼吸脉冲颜色：红色 0xFFD32F2F × alpha（0=透明，1=85%不透明）。 */
private const val FLASH_RED = 0xFFD32F2F

/** 计算闪烁背景色：alpha 越大越红，0f 时透明。 */
private fun flashColor(alpha: Float): Color =
    if (alpha <= 0f) Color.Transparent
    else Color(FLASH_RED).copy(alpha = (alpha * 0.85f).coerceIn(0f, 1f))

/** 计算呼吸 scale：alpha 0→1 时 scale 1.0→1.02，模拟轻微放大。 */
private fun flashScale(alpha: Float): Float =
    1f + alpha * 0.02f

// ==================================================================
// 节区列表
// ==================================================================

@Composable
private fun SectionsList(
    sections: List<SectionInfo>,
    onSectionClick: (SectionInfo) -> Unit,
    listState: LazyListState,
    flashAddress: Long?,
    flashAlpha: Float = 0f,
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
            val a = if (isFlash) flashAlpha else 0f
            SectionCard(
                section = section,
                onClick = { onSectionClick(section) },
                flashBg = flashColor(a),
                flashScale = flashScale(a)
            )
        }
    }
}

@Composable
private fun SectionCard(
    section: SectionInfo,
    onClick: () -> Unit,
    flashBg: Color = Color.Transparent,
    flashScale: Float = 1f
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = flashScale; scaleY = flashScale }
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.background(flashBg).padding(12.dp)) {
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
    flashAlpha: Float = 0f,
    isFiltered: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (symbols.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = if (isFiltered) "无匹配结果" else "暂无符号数据",
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
            val a = if (isFlash) flashAlpha else 0f
            SymbolRow(
                symbol = symbol,
                flashBg = flashColor(a),
                flashScale = flashScale(a),
                onClick = { onSymbolClick(symbol) }
            )
        }
    }
}

@Composable
private fun SymbolRow(
    symbol: SymbolInfo,
    flashBg: Color = Color.Transparent,
    flashScale: Float = 1f,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = flashScale; scaleY = flashScale }
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surface)
            .background(flashBg)
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
    flashAlpha: Float = 0f,
    isFiltered: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (functions.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = if (isFiltered) "无匹配结果" else "暂无函数数据（需 Rizin 引擎 + aaa 分析）",
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
            val a = if (isFlash) flashAlpha else 0f
            FunctionRow(
                func = func,
                flashBg = flashColor(a),
                flashScale = flashScale(a),
                onClick = { onFunctionClick(func) }
            )
        }
    }
}

@Composable
private fun FunctionRow(
    func: FunctionInfo,
    flashBg: Color = Color.Transparent,
    flashScale: Float = 1f,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = flashScale; scaleY = flashScale }
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surface)
            .background(flashBg)
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
    isFiltered: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (strings.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = if (isFiltered) "无匹配结果" else "点击此 Tab 自动扫描字符串…",
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
