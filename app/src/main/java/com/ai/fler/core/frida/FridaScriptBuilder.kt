package com.ai.fler.core.frida

/**
 * Builds Frida JS scripts that translate a static-analysis vaddr into a runtime
 * Interceptor hook on a target process.
 *
 * Runtime address = module base + vaddr. The module base is resolved INSIDE the
 * script via Process.enumerateModules() to dodge ASLR. This is required because
 * the QJS runtime only exposes a partial Module API (Module.getGlobalExportByName
 * works; Module proxies only expose getExportByName/getSymbolByName/toJSON with no
 * base/baseAddress), whereas enumerateModules() entries do expose .name/.base and
 * even cover packer-style in-memory libapp.so that has no file-backed mapping.
 *
 * Note: margins are stripped with trimMargin() (the "|" pipe prefix), NOT
 * trimIndent(), otherwise the raw "|" characters leak into the JS and cause a
 * parse failure at createScript time.
 */
object FridaScriptBuilder {

    /** decodeDart=false 时的降级描述函数。 */
    const val FALLBACK_READER =
        "function describeDartArg(p) { try { return String(p); } catch (e) { return '?'; } }"

    // 模板占位符常量行匹配（hookTemplateSource 顶部三行）。
    private val MODULE_TPL_RE = Regex("""const\s+MODULE_TPL\s*=\s*'[^']*'""")
    private val VADDR_TPL_RE = Regex("""const\s+VADDR_TPL\s*=\s*0x[0-9a-fA-F]+""")
    private val LABEL_TPL_RE = Regex("""const\s+LABEL_TPL\s*=\s*'[^']*'""")

    /**
     * 模板参数替换：把 [hookTemplateSource] 顶部常量块里的占位符换成真实值。
     *
     * 只替换显式传入的参数（null 不动）；source 中没有对应占位符行时保持不变，
     * 因此对任意自定义脚本调用都是安全的。module/label 中的单引号会被转义。
     *
     * @return 替换后的源码
     */
    fun fillTemplate(
        source: String,
        module: String? = null,
        vaddr: Long? = null,
        label: String? = null,
    ): String {
        var out = source
        if (module != null) {
            val v = module.replace("'", "\\'")
            out = MODULE_TPL_RE.replace(out) { "const MODULE_TPL = '$v'" }
        }
        if (vaddr != null) {
            out = VADDR_TPL_RE.replace(out) { "const VADDR_TPL = 0x${vaddr.toString(16)}" }
        }
        if (label != null) {
            val v = label.replace("'", "\\'")
            out = LABEL_TPL_RE.replace(out) { "const LABEL_TPL = '$v'" }
        }
        return out
    }

    // Dart AOT 对象最佳努力解码：不依赖符号表，纯布局启发式。
    // Dart String 的 length/data 偏移随 SDK 版本浮动，这里遍历候选偏移并校验
    // 「数据全为可打印 ASCII」才接受，避免把随机堆内存误当字符串；失败回退原始指针。
    private val SOURCE_DART_READERS = """
        |function decodeDartString(p) {
        |  try {
        |    if (p === null || p.isNull()) return null;
        |    var candidates = [4, 8, 12, 16];
        |    for (var k = 0; k < candidates.length; k++) {
        |      var off = candidates[k];
        |      var raw;
        |      try { raw = p.add(off).readU32() >> 1; } catch (e) { continue; }
        |      if (raw <= 0 || raw > 65536) continue;
        |      var buf = off + 8;
        |      var good = true;
        |      var out = '';
        |      for (var i = 0; i < raw; i++) {
        |        var c;
        |        try { c = p.add(buf + i).readU8(); } catch (e) { good = false; break; }
        |        if (c === 0) { out += ''; continue; }
        |        if (c < 0x20 || c > 0x7e) { good = false; break; }
        |        out += String.fromCharCode(c);
        |      }
        |      if (good) return out;
        |    }
        |  } catch (e) {}
        |  return null;
        |}
        |function describeDartArg(p) {
        |  try {
        |    var s = decodeDartString(p);
        |    if (s !== null) return JSON.stringify(s);
        |  } catch (e) {}
        |  try {
        |    var b = p.readByteArray(16);
        |    var hex = '';
        |    for (var i = 0; i < b.length; i++) hex += ('0' + (b[i] >>> 0).toString(16)).slice(-2);
        |    return '0x' + hex;
        |  } catch (e) {}
        |  return String(p);
        |}
        """.trimMargin()

    /**
     * Build a script that hooks function [label] at module [module] + [vaddr].
     *
     * @param module target so name (e.g. libapp.so; substring match)
     * @param vaddr  function vaddr (Blutter functionOffset)
     * @param label  log label (ClassName.methodName)
     * @param decodeDart 是否尽力解析 Dart String 参数（best-effort，失败回退原始指针/hex）
     */
    fun hookNative(
        module: String,
        vaddr: Long,
        label: String,
        decodeDart: Boolean = true,
    ): String {
        val hexVaddr = vaddr.toString(16)
        val modJs = module.replace("'", "\\'")
        val labelJs = label.replace("'", "\\'")
        // 解码器块单独拼装（trimMargin 已把每行推到列 0），不再嵌入外层管道前缀，
        // 避免嵌套 trimIndent/trimMargin 后残留 "|" 污染 JS。
        val readers = if (decodeDart) {
            SOURCE_DART_READERS
        } else {
            FALLBACK_READER
        }
        val body = """
            |const mods = Process.enumerateModules();
            |let mod = null;
            |for (let i = 0; i < mods.length; i++) {
            |  if (String(mods[i].name).indexOf('$modJs') >= 0) { mod = mods[i]; break; }
            |}
            |if (mod === null) {
            |  send({ type: 'system', level: 'error', payload: '$modJs not loaded' });
            |  return;
            |}
            |const target = mod.base.add(0x$hexVaddr);
            |send({ type: 'hook', level: 'info', payload: 'attaching $labelJs @ ' + target });
            |Interceptor.attach(target, {
            |  onEnter: function (args) {
            |    const a = [];
            |    for (let i = 0; i < 8; i++) {
            |      try {
            |        a.push(describeDartArg(args[i]));
            |      } catch (err) { break; }
            |    }
            |    send({ type: 'enter', method: '$labelJs', args: a });
            |  },
            |  onLeave: function (retval) {
            |    send({ type: 'leave', method: '$labelJs', retval: (() => { try { return describeDartArg(retval); } catch (e) { return String(retval); } })() });
            |  }
            |});
            |""".trimMargin()
        return """
            |(function () {
            |$readers
            |$body
            |}).call(this);
            """.trimMargin()
    }

    /**
     * 可编辑的 Hook 模板源码（内置预设种子用）。
     *
     * 与 [hookNative] 同一套逻辑（含 Dart 参数解码），但把 module/vaddr/label
     * 提为顶部常量块，用户只需改三处参数即可自建 hook。
     */
    fun hookTemplateSource(): String = """
        |(function () {
        |$SOURCE_DART_READERS
        |  // ===== 待填参数（仅需修改这一段） =====
        |  const MODULE_TPL = 'libapp.so';   // 目标 so 名（子串匹配）
        |  const VADDR_TPL = 0x00000000;     // 函数 vaddr（Blutter functionOffset / get_method）
        |  const LABEL_TPL = 'MyApp.method'; // 事件标签（区分命中来源）
        |  // ====================================
        |  const mods = Process.enumerateModules();
        |  let mod = null;
        |  for (let i = 0; i < mods.length; i++) {
        |    if (String(mods[i].name).indexOf(MODULE_TPL) >= 0) { mod = mods[i]; break; }
        |  }
        |  if (mod === null) {
        |    send({ type: 'system', level: 'error', payload: MODULE_TPL + ' not loaded' });
        |    return;
        |  }
        |  const target = mod.base.add(VADDR_TPL);
        |  send({ type: 'hook', level: 'info', payload: 'attaching ' + LABEL_TPL + ' @ ' + target });
        |  Interceptor.attach(target, {
        |    onEnter: function (args) {
        |      const a = [];
        |      for (let i = 0; i < 8; i++) {
        |        try {
        |          a.push(describeDartArg(args[i]));
        |        } catch (err) { break; }
        |      }
        |      send({ type: 'enter', method: LABEL_TPL, args: a });
        |    },
        |    onLeave: function (retval) {
        |      send({ type: 'leave', method: LABEL_TPL, retval: (() => { try { return describeDartArg(retval); } catch (e) { return String(retval); } })() });
        |    }
        |  });
        |}).call(this);
        """.trimMargin()

    /**
     * 运行时字节级热补丁脚本（Memory.patchCode）。
     *
     * 与静态 patch 等价但进程内生效、可逆（用 [readBytes] 或 patch 回原字节恢复）、
     * 无需重启目标进程。这是「frida Interceptor 改 pc/跳转」不可靠场景的可靠替代。
     *
     * 注意：patch 的目标地址如果恰好被另一个 Interceptor.attach 挂过，frida 会把原指令
     * 改写成 trampoline 跳板，此时 patchCode 会基于跳板字节错误写入 → SIGSEGV。因此
     * 同一地址不要同时 patch + interceptor；需要观察时挂旁路地址或先 detach 再 patch。
     *
     * @param module 目标 so 名（子串匹配，默认 libapp.so）
     * @param vaddr  目标指令 vaddr（Blutter functionOffset）
     * @param bytesHex 要写入的字节，如 "15 00 00 14" 或 "15000014"
     * @param label  事件标签
     */
    fun patchBytes(
        module: String,
        vaddr: Long,
        bytesHex: String,
        label: String,
    ): String {
        val hexVaddr = vaddr.toString(16)
        val cleanHex = bytesHex.replace(" ", "").replace("\t", "").replace("\n", "").replace("0x", "")
        val byteCount = cleanHex.length / 2
        val byteArray = StringBuilder(byteCount * 6)
        for (i in 0 until byteCount) {
            byteArray.append("0x").append(cleanHex.substring(i * 2, i * 2 + 2)).append(", ")
        }
        val arr = byteArray.toString().trimEnd(' ', ',')
        val modJs = module.replace("'", "\\'")
        return """
            |(function () {
            |  const mods = Process.enumerateModules();
            |  let mod = null;
            |  for (let i = 0; i < mods.length; i++) {
            |    if (String(mods[i].name).indexOf('$modJs') >= 0) { mod = mods[i]; break; }
            |  }
            |  if (mod === null) {
            |    send({ type: 'system', level: 'error', payload: '$modJs not loaded' });
            |    return;
            |  }
            |  const target = mod.base.add(0x$hexVaddr);
            |  const toBytes = function (arr) {
            |    const out = [];
            |    for (let i = 0; i < arr.length; i++) out.push(('0' + (arr[i] & 0xff).toString(16)).slice(-2));
            |    return out;
            |  };
            |  let orig = [];
            |  try { orig = toBytes(new Uint8Array(target.readByteArray($byteCount))); } catch (e) {}
            |  send({ type: 'patch_read', at: '0x$hexVaddr', label: '$label', bytes: orig });
            |  Memory.patchCode(target, $byteCount, function (code) {
            |    code.writeByteArray([$arr]);
            |  });
            |  let after = [];
            |  try { after = toBytes(new Uint8Array(target.readByteArray($byteCount))); } catch (e) {}
            |  send({ type: 'patch_done', at: '0x$hexVaddr', label: '$label', bytes: after, ok: after.length > 0 && after.join('') === '$cleanHex' });
            |})();
            """.trimMargin()
    }

    /**
     * 读取目标进程内存字节脚本（读回校验/快照用）。
     * @param module 目标 so 名（子串匹配）
     * @param vaddr  目标 vaddr
     * @param size   字节数 1..64
     * @param label  事件标签
     */
    fun readBytes(
        module: String,
        vaddr: Long,
        size: Int,
        label: String,
    ): String {
        val hexVaddr = vaddr.toString(16)
        val sizeClamped = size.coerceIn(1, 64)
        val modJs = module.replace("'", "\\'")
        return """
            |(function () {
            |  const mods = Process.enumerateModules();
            |  let mod = null;
            |  for (let i = 0; i < mods.length; i++) {
            |    if (String(mods[i].name).indexOf('$modJs') >= 0) { mod = mods[i]; break; }
            |  }
            |  if (mod === null) {
            |    send({ type: 'system', level: 'error', payload: '$modJs not loaded' });
            |    return;
            |  }
            |  const target = mod.base.add(0x$hexVaddr);
            |  let bytes = [];
            |  try {
            |    const raw = new Uint8Array(target.readByteArray($sizeClamped));
            |    for (let i = 0; i < raw.length; i++) bytes.push(('0' + (raw[i] & 0xff).toString(16)).slice(-2));
            |  } catch (e) {
            |    send({ type: 'read_err', at: '0x$hexVaddr', label: '$label', error: String(e) });
            |    return;
            |  }
            |  send({ type: 'read_done', at: '0x$hexVaddr', label: '$label', bytes: bytes });
            |})();
            """.trimMargin()
    }

    /**
     * Basic bootstrap script: print loaded module names at the earliest stage.
     */
    fun bootstrapScan(): String {
        return """
            |(function () {
            |  send({ kind: 'bootstrap', modules: Process.enumerateModules().map(m => m.name) });
            |})();
            """.trimMargin()
    }
}
