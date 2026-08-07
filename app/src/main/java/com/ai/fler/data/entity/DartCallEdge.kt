package com.ai.fler.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Dart 方法调用边（真实交叉引用 = 方法级调用图）。
 *
 * DartCallGraphBuilder 从各方法 src_code 反汇编文本提取到的真实调用关系，
 * 以 [callerMethodId] -> [calleeMethodId] 的边存储。替代早期 search_calls /
 * get_method_callers 的 src_code 文本 LIKE 假命中。
 *
 * 命名为 denormalized（类.方法），便于 MCP / App 编辑器免 join 直接展示与跳转。
 * 坐标：method vaddr 与 ELF 虚拟地址一致（libapp 上 fileOffset == functionOffset）。
 */
@Entity(
    tableName = "dart_call_edges",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = DartMethod::class,
            parentColumns = ["id"],
            childColumns = ["caller_method_id"],
            onDelete = androidx.room.ForeignKey.CASCADE
        ),
        androidx.room.ForeignKey(
            entity = Analysis::class,
            parentColumns = ["id"],
            childColumns = ["analysis_id"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["analysis_id"]),
        Index(value = ["caller_method_id"]),
        Index(value = ["callee_method_id"]),
        Index(value = ["callee_vaddr"])
    ]
)
data class DartCallEdge(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "analysis_id")
    val analysisId: Long,

    @ColumnInfo(name = "caller_method_id")
    val callerMethodId: Long,

    /** 调用方展示名 "类.方法"。 */
    @ColumnInfo(name = "caller_name")
    val callerName: String,

    @ColumnInfo(name = "caller_vaddr")
    val callerVaddr: Long,

    /** 被调方法；解析失败（UNRESOLVED）为 null。 */
    @ColumnInfo(name = "callee_method_id")
    val calleeMethodId: Long? = null,

    /** 被调展示名 "类.方法"；未解析为空串。 */
    @ColumnInfo(name = "callee_name")
    val calleeName: String = "",

    /** 被调目标虚拟地址（未解析为 0）。 */
    @ColumnInfo(name = "callee_vaddr")
    val calleeVaddr: Long = 0,

    /** 边类型：DIRECT_CALL / DIRECT_BRANCH / UNRESOLVED。 */
    @ColumnInfo(name = "callee_kind")
    val calleeKind: String = "DIRECT_CALL",

    /** 该调用点的虚拟地址。 */
    @ColumnInfo(name = "site_vaddr")
    val siteVaddr: Long = 0
)