package com.ai.fler.features.so_editor

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.fler.core.jni.ElfSection
import com.ai.fler.core.jni.ElfSymbol

/**
 * ELF 结构 Tab。
 *
 * 展示节头表和符号表，支持点击跳转到对应位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StructureTab(
    sections: List<ElfSection>,
    symbols: List<ElfSymbol>,
    dynamicSymbols: List<ElfSymbol>,
    onSectionClick: (ElfSection) -> Unit,
    onSymbolClick: (ElfSymbol) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSubTab by remember { mutableStateOf(StructureSubTab.SECTIONS) }

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
        }

        when (selectedSubTab) {
            StructureSubTab.SECTIONS -> {
                SectionsList(
                    sections = sections,
                    onSectionClick = onSectionClick,
                    modifier = Modifier.fillMaxSize()
                )
            }
            StructureSubTab.SYMBOLS -> {
                SymbolsList(
                    symbols = symbols,
                    onSymbolClick = onSymbolClick,
                    modifier = Modifier.fillMaxSize()
                )
            }
            StructureSubTab.DYNAMIC_SYMBOLS -> {
                SymbolsList(
                    symbols = dynamicSymbols,
                    onSymbolClick = onSymbolClick,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private enum class StructureSubTab {
    SECTIONS,
    SYMBOLS,
    DYNAMIC_SYMBOLS
}

@Composable
private fun SectionsList(
    sections: List<ElfSection>,
    onSectionClick: (ElfSection) -> Unit,
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
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = sections,
            key = { it.name }
        ) { section ->
            SectionCard(
                section = section,
                onClick = { onSectionClick(section) }
            )
        }
    }
}

@Composable
private fun SectionCard(
    section: ElfSection,
    onClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
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
                SectionTypeBadge(type = section.type)
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

                // 显示标志位含义
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (section.flags and ElfSection.SHF_WRITE != 0L) {
                        FlagChip("W")
                    }
                    if (section.flags and ElfSection.SHF_ALLOC != 0L) {
                        FlagChip("A")
                    }
                    if (section.flags and ElfSection.SHF_EXECINSTR != 0L) {
                        FlagChip("X")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionInfo(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
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
private fun SectionTypeBadge(type: Int) {
    val text: String
    val color: Color
    when (type) {
        ElfSection.SHT_PROGBITS -> { text = "PROGBITS"; color = Color(0xFF4CAF50) }
        ElfSection.SHT_SYMTAB -> { text = "SYMTAB"; color = Color(0xFF2196F3) }
        ElfSection.SHT_STRTAB -> { text = "STRTAB"; color = Color(0xFF00BCD4) }
        ElfSection.SHT_DYNSYM -> { text = "DYNSYM"; color = Color(0xFF9C27B0) }
        ElfSection.SHT_NOBITS -> { text = "NOBITS"; color = Color(0xFFFF9800) }
        ElfSection.SHT_RELA -> { text = "RELA"; color = Color(0xFFF44336) }
        else -> { text = "TYPE_$type"; color = Color.Gray }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
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

@Composable
private fun SymbolsList(
    symbols: List<ElfSymbol>,
    onSymbolClick: (ElfSymbol) -> Unit,
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
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(
            items = symbols.take(500), // 限制显示数量，避免性能问题
            key = { "${it.name}_${it.address}" }
        ) { symbol ->
            SymbolRow(
                symbol = symbol,
                onClick = { onSymbolClick(symbol) }
            )
        }

        if (symbols.size > 500) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "仅显示前 500 条，共 ${symbols.size} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SymbolRow(
    symbol: ElfSymbol,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 符号类型图标
        SymbolTypeBadge(type = symbol.type)

        Spacer(modifier = Modifier.width(8.dp))

        // 符号名
        Text(
            text = symbol.name.ifBlank { "<unnamed>" },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 地址
        Text(
            text = "0x${symbol.address.toString(16).uppercase().padStart(16, '0')}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 大小
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
private fun SymbolTypeBadge(type: Byte) {
    val text: String
    val color: Color
    when (type) {
        ElfSymbol.STT_FUNC -> { text = "FUNC"; color = Color(0xFF4CAF50) }
        ElfSymbol.STT_OBJECT -> { text = "OBJ"; color = Color(0xFF2196F3) }
        ElfSymbol.STT_SECTION -> { text = "SECT"; color = Color(0xFFFF9800) }
        ElfSymbol.STT_FILE -> { text = "FILE"; color = Color.Gray }
        else -> { text = "?"; color = Color.Gray }
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
