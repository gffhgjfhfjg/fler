package com.ai.fler.core.gadget

import java.io.ByteArrayOutputStream

/**
 * 二进制 AndroidManifest.xml（AXML）解析与重写。
 *
 * 纯 Kotlin、无 Android 依赖，可在 JVM 单测中验证。
 *
 * 用途：
 * 1. 解析 manifest 提取注入所需信息（包名 / Application 类 / launcher Activity /
 *    extractNativeLibs 现状）
 * 2. 重写 android:extractNativeLibs=true —— gadget .so / config / script 必须
 *    被安装器解压到 nativeLibraryDir 才能被 System.loadLibrary 和 gadget 的
 *    同目录 config 发现逻辑找到（源 APK 常见 extractNativeLibs=false 直加载）。
 *
 * 实现方式：完整解析 AXML（字符串池 / 资源 id 表 / 节点流）为内存模型，
 * 修改后全量重序列化。原始字符串顺序保持不变（索引稳定），新增字符串
 * 追加到池尾并同步扩展资源 id 表，保证框架按资源 id 识别新属性。
 */
object Axml {

    // ---- chunk 类型 ----
    private const val CHUNK_XML_TREE = 0x0003
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_RESOURCE_MAP = 0x0180
    private const val CHUNK_START_NAMESPACE = 0x0100
    private const val CHUNK_END_NAMESPACE = 0x0101
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val CHUNK_END_ELEMENT = 0x0103
    private const val CHUNK_CDATA = 0x0104

    // ---- 字符串池标志位 ----
    private const val FLAG_UTF8 = 0x100

    // ---- 数据类型（typedValue.dataType）----
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_BOOLEAN = 0x12

    /** android:extractNativeLibs 的资源 id（AOSP attrs_manifest；夹具测试校验）。 */
    const val ATTR_EXTRACT_NATIVE_LIBS_RES_ID = 0x010104ea

    const val ANDROID_NS_URI = "http://schemas.android.com/apk/res/android"

    // ==================================================================
    // 内存模型
    // ==================================================================

    /** 解析后的 AXML 文档。字符串池顺序保持原样，新字符串只追加。 */
    internal class Document(
        val strings: MutableList<String>,
        val utf8: Boolean,
        /** 资源 id 表：第 i 项 = 第 i 个字符串作为属性名时的资源 id（0 = 非属性）。 */
        val resourceIds: MutableList<Int>,
        internal val nodes: MutableList<Node>,
    ) {
        internal val androidNsIdx: Int? by lazy {
            nodes.filterIsInstance<StartNamespace>()
                .firstOrNull { str(it.uri) == ANDROID_NS_URI }?.uri
        }

        fun str(idx: Int): String? = if (idx < 0 || idx >= strings.size) null else strings[idx]

        /** 追加字符串（已存在则复用），返回索引。 */
        internal fun intern(s: String): Int {
            val i = strings.indexOf(s)
            if (i >= 0) return i
            strings.add(s)
            return strings.size - 1
        }

        /** 查找名称为 [name] 的 manifest 一级子元素。 */
        internal fun topLevelElements(name: String): List<StartElement> {
            val out = ArrayList<StartElement>()
            var depth = 0
            for (n in nodes) {
                when (n) {
                    is StartElement -> {
                        if (depth == 1 && n.nameStr(this) == name) out.add(n)
                        depth++
                    }
                    is EndElement -> depth--
                    else -> {}
                }
            }
            return out
        }

        // --------------------------------------------------------------

        /** manifest 根元素（<manifest>）。 */
        private fun rootManifest(): StartElement =
            nodes.filterIsInstance<StartElement>().firstOrNull { it.nameStr(this) == "manifest" }
                ?: throw AxmlException("未找到 <manifest> 根元素")

        /** manifest 摘要（注入所需）。 */
        fun manifestInfo(): ManifestInfo {
            val manifest = rootManifest()
            val pkg = manifest.attrString(this, null, "package")
            val application = topLevelElements("application").firstOrNull()
            val appAttr = application?.attrString(this, ANDROID_NS_URI, "name")
            val extract = application?.attrBoolean(this, "extractNativeLibs")
            return ManifestInfo(
                packageName = pkg,
                applicationName = appAttr?.let { resolveClassName(pkg, it) },
                extractNativeLibs = extract,
                launcherActivities = collectLauncherActivities(pkg),
            )
        }

        /** 收集 launcher Activity 类名（activity 与 activity-alias 的 target）。 */
        private fun collectLauncherActivities(pkg: String?): List<String> {
            val result = LinkedHashSet<String>()
            val open = ArrayDeque<StartElement>() // LIFO 栈
            var launcherCandidate: String? = null
            var inIntentFilter = false
            var hasMain = false
            var hasLauncher = false

            fun closeCandidate() {
                val name = launcherCandidate
                if (name != null && hasMain && hasLauncher) {
                    result.add(resolveClassName(pkg, name))
                }
                launcherCandidate = null
                hasMain = false
                hasLauncher = false
            }

            // 栈深（入栈后）：manifest=1 application=2 activity=3 intent-filter=4 action=5
            for (n in nodes) {
                when (n) {
                    is StartElement -> {
                        val local = n.nameStr(this)
                        open.addLast(n)
                        when {
                            open.size == 5 && inIntentFilter &&
                                (local == "action" || local == "category") -> {
                                val v = n.attrString(this, ANDROID_NS_URI, "name")
                                if (v == "android.intent.action.MAIN") hasMain = true
                                if (v == "android.intent.category.LAUNCHER") hasLauncher = true
                            }
                            open.size == 4 && local == "intent-filter" -> inIntentFilter = true
                            open.size == 3 && (local == "activity" || local == "activity-alias") -> {
                                closeCandidate()
                                launcherCandidate = n.attrString(this, ANDROID_NS_URI, "name")
                                    ?: n.attrString(this, ANDROID_NS_URI, "targetActivity")
                            }
                        }
                    }
                    is EndElement -> {
                        val local = open.removeLastOrNull()?.nameStr(this)
                        when {
                            open.size == 3 && local == "intent-filter" -> inIntentFilter = false
                            open.size == 2 && (local == "activity" || local == "activity-alias") ->
                                closeCandidate()
                        }
                    }
                    else -> {}
                }
            }
            return result.toList()
        }

        /**
         * 设置 application 的 android:extractNativeLibs。
         *
         * 属性存在 → 原地改值；不存在 → 在属性表末尾追加（保持 idIndex/classIndex/
         * styleIndex 位置语义），新增字符串追加池尾并扩展资源 id 表。
         */
        fun setExtractNativeLibs(value: Boolean) {
            val application = topLevelElements("application").firstOrNull()
                ?: throw AxmlException("未找到 <application> 元素，无法设置 extractNativeLibs")
            val nsIdx = androidNsIdx
                ?: throw AxmlException("manifest 中无 android 命名空间，无法追加属性")
            val nameIdx = intern("extractNativeLibs")
            // 确保资源 id 表覆盖到新属性名的字符串索引
            while (resourceIds.size <= nameIdx) resourceIds.add(0)
            resourceIds[nameIdx] = ATTR_EXTRACT_NATIVE_LIBS_RES_ID
            val trueIdx = intern("true")

            val existing = application.attrs.firstOrNull {
                it.name == nameIdx && str(nameIdx) == "extractNativeLibs"
            }
            if (existing != null) {
                existing.typedValue.data = if (value) -0x1L else 0x0L
                existing.typedValue.dataType = TYPE_INT_BOOLEAN
                existing.rawValue = trueIdx
                return
            }
            application.attrs.add(
                Attribute(ns = nsIdx, name = nameIdx, rawValue = trueIdx,
                    typedValue = TypedValue(8, 0, TYPE_INT_BOOLEAN, if (value) -0x1L else 0x0L))
            )
        }

        // --------------------------------------------------------------

        /** 重序列化为二进制 AXML。 */
        fun toBytes(): ByteArray {
            val out = Writer()
            out.u16(CHUNK_XML_TREE)
            out.u16(8) // 树级头大小
            val sizePos = out.reserveU32()

            writeStringPool(out)

            // 资源 id 表（仅在有内容时写出）
            if (resourceIds.isNotEmpty()) {
                out.u16(CHUNK_RESOURCE_MAP)
                out.u16(8)
                out.u32(8 + 4L * resourceIds.size)
                resourceIds.forEach { out.u32(it.toLong() and 0xffffffffL) }
            }

            for (n in nodes) writeNode(out, n)
            out.patchU32(sizePos, out.size)

            val bytes = out.toByteArray()
            if (bytes.size and 0x3 != 0) throw AxmlException("AXML 总长度未 4 字节对齐: ${bytes.size}")
            return bytes
        }

        private fun writeStringPool(out: Writer) {
            val data = ByteArrayOutputStream()
            val offsets = IntArray(strings.size)
            var pos = 0
            for ((i, s) in strings.withIndex()) {
                if (pos and 0x3 != 0) { // 每个字符串 4 字节起始（与 aapt 行为一致）
                    val pad = 4 - (pos and 0x3)
                    repeat(pad) { data.write(0) }
                    pos += pad
                }
                offsets[i] = pos
                val b = if (utf8) encodeUtf8(s) else encodeUtf16(s)
                data.write(b)
                pos += b.size
            }
            var region = data.toByteArray()
            if (region.isEmpty()) region = ByteArray(4)
            val pad = (4 - (region.size and 0x3)) and 0x3
            if (pad > 0) region = region.copyOf(region.size + pad)

            val poolHeaderSize = 28
            val offsetsSize = 4 * strings.size
            val stringsStart = poolHeaderSize + offsetsSize
            val chunkSize = stringsStart + region.size

            out.u16(CHUNK_STRING_POOL)
            out.u16(poolHeaderSize)
            out.u32(chunkSize)
            out.u32(strings.size)
            out.u32(0) // styleCount（manifest 无样式，解析期已校验）
            out.u32(if (utf8) FLAG_UTF8.toLong() else 0L)
            out.u32(stringsStart)
            out.u32(0) // stylesStart
            offsets.forEach { out.u32(it.toLong()) }
            out.bytes(region)
        }

        private fun writeNode(out: Writer, n: Node) {
            when (n) {
                is StartNamespace -> {
                    out.u16(CHUNK_START_NAMESPACE)
                    out.u16(16)
                    out.u32(24)
                    out.u32(n.lineNumber)
                    out.u32(n.comment)
                    out.u32(n.prefix)
                    out.u32(n.uri)
                }
                is EndNamespace -> {
                    out.u16(CHUNK_END_NAMESPACE)
                    out.u16(16)
                    out.u32(24)
                    out.u32(n.lineNumber)
                    out.u32(n.comment)
                    out.u32(n.prefix)
                    out.u32(n.uri)
                }
                is StartElement -> {
                    out.u16(CHUNK_START_ELEMENT)
                    out.u16(16)
                    out.u32(36 + 20L * n.attrs.size)
                    out.u32(n.lineNumber)
                    out.u32(n.comment)
                    out.u32(n.ns)
                    out.u32(n.name)
                    out.u16(n.attributeStart)
                    out.u16(n.attributeSize)
                    out.u16(n.attrs.size)
                    out.u16(n.idIndex)
                    out.u16(n.classIndex)
                    out.u16(n.styleIndex)
                    n.attrs.forEach { writeAttribute(out, it) }
                }
                is EndElement -> {
                    out.u16(CHUNK_END_ELEMENT)
                    out.u16(16)
                    out.u32(24)
                    out.u32(n.lineNumber)
                    out.u32(n.comment)
                    out.u32(n.ns)
                    out.u32(n.name)
                }
                is CData -> {
                    out.u16(CHUNK_CDATA)
                    out.u16(16)
                    out.u32(24)
                    out.u32(n.lineNumber)
                    out.u32(n.comment)
                    out.u32(n.data)
                    writeTypedValue(out, n.typedValue)
                }
            }
        }

        private fun writeAttribute(out: Writer, a: Attribute) {
            out.u32(a.ns)
            out.u32(a.name)
            out.u32(a.rawValue)
            writeTypedValue(out, a.typedValue)
        }

        private fun writeTypedValue(out: Writer, v: TypedValue) {
            out.u16(v.size)
            out.u8(v.res0)
            out.u8(v.dataType)
            out.u32(v.data)
        }

        private fun encodeUtf8(s: String): ByteArray {
            val bytes = s.toByteArray(Charsets.UTF_8)
            val chars = s.length
            val out = ByteArrayOutputStream(bytes.size + 5)
            writeUtf8Length(out, chars)
            writeUtf8Length(out, bytes.size)
            out.write(bytes)
            out.write(0)
            return out.toByteArray()
        }

        private fun writeUtf8Length(out: ByteArrayOutputStream, v: Int) {
            if (v > 0x7f) {
                out.write(((v ushr 8) or 0x80) and 0xff)
                out.write(v and 0xff)
            } else {
                out.write(v and 0xff)
            }
        }

        private fun encodeUtf16(s: String): ByteArray {
            val units = s.toCharArray()
            val out = ByteArrayOutputStream(units.size * 2 + 6)
            if (units.size >= 0x8000) {
                // 首 u16 = 0x8000 | (len >> 16)，次 u16 = len & 0xFFFF
                out.write((units.size ushr 16) and 0xff)
                out.write(0x80 or ((units.size ushr 24) and 0x7f))
                out.write(units.size and 0xff)
                out.write((units.size ushr 8) and 0xff)
            } else {
                out.write(units.size and 0xff)
                out.write((units.size ushr 8) and 0xff)
            }
            for (u in units) {
                out.write(u.code and 0xff)
                out.write((u.code ushr 8) and 0xff)
            }
            out.write(0)
            out.write(0)
            return out.toByteArray()
        }
    }

    // ==================================================================
    // 节点与属性
    // ==================================================================

    internal sealed interface Node

    internal class StartNamespace(
        val lineNumber: Int, val comment: Int, val prefix: Int, val uri: Int,
    ) : Node

    internal class EndNamespace(
        val lineNumber: Int, val comment: Int, val prefix: Int, val uri: Int,
    ) : Node

    internal class StartElement(
        val lineNumber: Int,
        val comment: Int,
        val ns: Int,
        val name: Int,
        val attributeStart: Int,
        val attributeSize: Int,
        val idIndex: Int,
        val classIndex: Int,
        val styleIndex: Int,
        val attrs: MutableList<Attribute>,
    ) : Node {
        fun nameStr(doc: Document): String? = doc.str(name)

        fun attrString(doc: Document, ns: String?, localName: String): String? {
            val a = findAttr(doc, ns, localName) ?: return null
            a.rawValue?.let { return doc.str(it) }
            if (a.typedValue.dataType == TYPE_STRING) return doc.str(a.typedValue.data.toInt())
            return null
        }

        fun attrBoolean(doc: Document, localName: String): Boolean? {
            val a = findAttr(doc, ANDROID_NS_URI, localName) ?: return null
            if (a.typedValue.dataType == TYPE_INT_BOOLEAN) return a.typedValue.data != 0L
            return null
        }

        private fun findAttr(doc: Document, ns: String?, localName: String): Attribute? =
            attrs.firstOrNull {
                it.name >= 0 && doc.str(it.name) == localName &&
                    (ns == null || (it.ns >= 0 && doc.str(it.ns) == ns))
            }
    }

    internal class EndElement(
        val lineNumber: Int, val comment: Int, val ns: Int, val name: Int,
    ) : Node

    internal class CData(
        val lineNumber: Int,
        val comment: Int,
        val data: Int,
        val typedValue: TypedValue,
    ) : Node

    internal class Attribute(
        val ns: Int,
        val name: Int,
        var rawValue: Int,
        var typedValue: TypedValue,
    )

    /** Res_value：size u16 + res0 u8 + dataType u8 + data u32。 */
    internal class TypedValue(
        val size: Int,
        val res0: Int,
        var dataType: Int,
        var data: Long,
    )

    /** manifest 摘要。 */
    internal data class ManifestInfo(
        val packageName: String?,
        val applicationName: String?,
        val extractNativeLibs: Boolean?,
        val launcherActivities: List<String>,
    ) {
        /** 注入入口类：优先自定义 Application，其次 launcher Activity。 */
        val entryClassName: String? get() = applicationName ?: launcherActivities.firstOrNull()
    }

    internal class AxmlException(message: String) : RuntimeException(message)

    // ==================================================================
    // 解析
    // ==================================================================

    internal fun parse(bytes: ByteArray): Document {
        if (bytes.size < 8) throw AxmlException("AXML 过短")
        if (readU16(bytes, 0) != CHUNK_XML_TREE) throw AxmlException("非 AXML 文件（头类型错误）")
        val total = readU32(bytes, 4)
        if (total != bytes.size.toLong()) {
            throw AxmlException("AXML 头声明长度 $total 与实际 ${bytes.size} 不一致")
        }
        var off = 8

        var strings: MutableList<String>? = null
        var utf8 = false
        var resourceIds: MutableList<Int>? = null
        val nodes = ArrayList<Node>()

        while (off + 8 <= bytes.size) {
            val type = readU16(bytes, off)
            val headerSize = readU16(bytes, off + 2)
            val size = readU32(bytes, off + 4).toInt()
            if (size < 8 || off + size > bytes.size) {
                throw AxmlException("AXML chunk 异常: type=0x${type.toString(16)} size=$size off=$off")
            }
            when (type) {
                CHUNK_STRING_POOL -> {
                    if (strings != null) throw AxmlException("重复的字符串池")
                    strings = parseStringPool(bytes, off, headerSize, size).also {
                        utf8 = (readU32(bytes, off + 12) and FLAG_UTF8.toLong()) != 0L
                    }
                }
                CHUNK_RESOURCE_MAP -> {
                    val n = (size - 8) / 4
                    val ids = ArrayList<Int>(n)
                    for (i in 0 until n) ids.add(readU32(bytes, off + 8 + 4 * i).toInt())
                    resourceIds = ids
                }
                CHUNK_START_NAMESPACE ->
                    nodes.add(StartNamespace(
                        readU32(bytes, off + 8).toInt(), readU32(bytes, off + 12).toInt(),
                        readU32(bytes, off + 16).toInt(), readU32(bytes, off + 20).toInt()))
                CHUNK_END_NAMESPACE ->
                    nodes.add(EndNamespace(
                        readU32(bytes, off + 8).toInt(), readU32(bytes, off + 12).toInt(),
                        readU32(bytes, off + 16).toInt(), readU32(bytes, off + 20).toInt()))
                CHUNK_START_ELEMENT ->
                    nodes.add(parseStartElement(bytes, off, headerSize))
                CHUNK_END_ELEMENT ->
                    nodes.add(EndElement(
                        readU32(bytes, off + 8).toInt(), readU32(bytes, off + 12).toInt(),
                        readU32(bytes, off + 16).toInt(), readU32(bytes, off + 20).toInt()))
                CHUNK_CDATA ->
                    nodes.add(CData(
                        readU32(bytes, off + 8).toInt(), readU32(bytes, off + 12).toInt(),
                        readU32(bytes, off + 16).toInt(),
                        parseTypedValue(bytes, off + 20)))
                else -> throw AxmlException(
                    "不支持的 AXML chunk 类型 0x${type.toString(16)} @ $off")
            }
            off += size
        }
        if (off != bytes.size) throw AxmlException("AXML 尾部残留 ${bytes.size - off} 字节")

        return Document(
            strings ?: throw AxmlException("缺少字符串池"),
            utf8,
            resourceIds ?: ArrayList(),
            nodes,
        )
    }

    private fun parseStringPool(bytes: ByteArray, chunkOff: Int, headerSize: Int, size: Int): MutableList<String> {
        if (headerSize < 28) throw AxmlException("字符串池头过短: $headerSize")
        val stringCount = readU32(bytes, chunkOff + 8).toInt()
        val styleCount = readU32(bytes, chunkOff + 12).toInt()
        if (styleCount > 0) throw AxmlException("含 style 的字符串池不支持（manifest 不应出现）")
        val flags = readU32(bytes, chunkOff + 16)
        val utf8 = (flags and FLAG_UTF8.toLong()) != 0L
        val stringsStart = readU32(bytes, chunkOff + 20).toInt()
        val out = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val offset = readU32(bytes, chunkOff + 28 + 4 * i).toInt()
            val pos = chunkOff + stringsStart + offset
            out.add(if (utf8) readUtf8(bytes, pos) else readUtf16(bytes, pos))
        }
        return out
    }

    private fun readUtf8(bytes: ByteArray, pos: Int): String {
        var p = pos
        val charLen = readUtf8Length(bytes, p).also { p += it.second }
        val byteLen = readUtf8Length(bytes, p).also { p += it.second }
        val end = p + byteLen.first
        if (end > bytes.size) throw AxmlException("UTF-8 字符串越界")
        return String(bytes, p, byteLen.first, Charsets.UTF_8)
    }

    private fun readUtf8Length(bytes: ByteArray, pos: Int): Pair<Int, Int> {
        val b0 = bytes[pos].toInt() and 0xff
        return if (b0 and 0x80 != 0) {
            val b1 = bytes[pos + 1].toInt() and 0xff
            (((b0 and 0x7f) shl 8) or b1) to 2
        } else b0 to 1
    }

    private fun readUtf16(bytes: ByteArray, pos: Int): String {
        var p = pos
        val w0 = readU16(bytes, p)
        val len = if (w0 and 0x8000 != 0) {
            p += 2
            ((w0 and 0x7fff) shl 16) or readU16(bytes, p)
        } else w0
        p += 2
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            sb.append(readU16(bytes, p + 2 * i).toChar())
        }
        return sb.toString()
    }

    private fun parseStartElement(bytes: ByteArray, chunkOff: Int, headerSize: Int): StartElement {
        if (headerSize < 16) throw AxmlException("元素头过短")
        val ext = chunkOff + 16
        val ns = readU32(bytes, ext).toInt()
        val name = readU32(bytes, ext + 4).toInt()
        val attributeStart = readU16(bytes, ext + 8)
        val attributeSize = readU16(bytes, ext + 10)
        val attributeCount = readU16(bytes, ext + 12)
        val idIndex = readU16(bytes, ext + 14)
        val classIndex = readU16(bytes, ext + 16)
        val styleIndex = readU16(bytes, ext + 18)
        if (attributeSize != 0 && attributeSize != 20) {
            throw AxmlException("属性大小异常: $attributeSize")
        }
        val attrs = ArrayList<Attribute>(attributeCount)
        // 属性区起点 = attrExt 起始 + attributeStart（attributeStart 相对于 attrExt 结构）
        val attrBase = ext + attributeStart
        for (i in 0 until attributeCount) {
            val a = attrBase + i * 20
            if (a + 20 > bytes.size) throw AxmlException("属性越界")
            attrs.add(Attribute(
                ns = readU32(bytes, a).toInt(),
                name = readU32(bytes, a + 4).toInt(),
                rawValue = readU32(bytes, a + 8).toInt(),
                typedValue = parseTypedValue(bytes, a + 12),
            ))
        }
        return StartElement(
            lineNumber = readU32(bytes, chunkOff + 8).toInt(),
            comment = readU32(bytes, chunkOff + 12).toInt(),
            ns = ns, name = name,
            attributeStart = attributeStart, attributeSize = attributeSize,
            idIndex = idIndex, classIndex = classIndex, styleIndex = styleIndex,
            attrs = attrs,
        )
    }

    private fun parseTypedValue(bytes: ByteArray, pos: Int): TypedValue = TypedValue(
        size = readU16(bytes, pos),
        res0 = bytes[pos + 2].toInt() and 0xff,
        dataType = bytes[pos + 3].toInt() and 0xff,
        data = readU32(bytes, pos + 4),
    )

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 相对类名（.Foo / Foo）→ 绝对类名。 */
    fun resolveClassName(pkg: String?, name: String): String = when {
        name.startsWith('.') -> pkg?.let { it + name } ?: name
        name.contains('.') -> name
        else -> pkg?.let { "$it.$name" } ?: name
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun readU32(b: ByteArray, off: Int): Long =
        (readU16(b, off).toLong() and 0xffffffffL) or (readU16(b, off + 2).toLong() shl 16)

    /** 小端字节输出 + u32 回填（仅文件内使用）。 */
    private class Writer {
        private val out = ByteArrayOutputStream()

        val size: Int get() = out.size()

        fun u8(v: Int) = out.write(v and 0xff)
        fun u16(v: Int) {
            out.write(v and 0xff)
            out.write((v ushr 8) and 0xff)
        }
        fun u32(v: Int) {
            u16(v and 0xffff)
            u16((v ushr 16) and 0xffff)
        }
        fun u32(v: Long) {
            u16((v and 0xffffL).toInt())
            u16(((v ushr 16) and 0xffffL).toInt())
        }
        fun bytes(b: ByteArray) = out.write(b)

        /** 预留 4 字节，返回回填位置。 */
        fun reserveU32(): Int {
            val pos = out.size()
            u32(0)
            return pos
        }

        fun patchU32(pos: Int, value: Int) {
            val b = out.toByteArray()
            b[pos] = (value and 0xff).toByte()
            b[pos + 1] = ((value ushr 8) and 0xff).toByte()
            b[pos + 2] = ((value ushr 16) and 0xff).toByte()
            b[pos + 3] = ((value ushr 24) and 0xff).toByte()
            out.reset()
            out.write(b)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
