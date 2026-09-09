package com.ai.fler.core.gadget

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.HiddenApiRestriction
import org.jf.dexlib2.Opcode.CONST_STRING
import org.jf.dexlib2.Opcode.INVOKE_STATIC
import org.jf.dexlib2.Opcode.RETURN_VOID
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.MethodParameter
import org.jf.dexlib2.iface.Annotation
import org.jf.dexlib2.iface.instruction.formats.Instruction21c
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import java.io.File

/**
 * classes.dex 注入器：把 `System.loadLibrary("<libName>")` 插到入口类
 * （Application / launcher Activity）的 `<clinit>` 最前面。
 *
 * 选 `<clinit>` 而非 attachBaseContext/onCreate 的原因：
 * - 静态初始化在该类任何实例方法前执行（早于 Application.onCreate / 签名自校验）
 * - 无参数寄存器，插入 v0 指令不受寄存器布局影响（仅需保证 registerCount>=1）
 * - 类缺失 `<clinit>` 时可安全新建
 *
 * gadget 在 clinit 阶段加载：listen(wait) 模式在用户 attach 前冻结 app 启动，
 * script 模式先跑反对抗脚本再放行——都早于业务代码。
 *
 * 幂等：clinit 已含 loadLibrary("<libName>") 则原样返回（alreadyInjected=true）。
 */
object DexGadgetInjector {

    data class Result(
        val dexBytes: ByteArray,
        val targetClass: String,
        val method: String,
        val createdClinit: Boolean,
        val alreadyInjected: Boolean,
    )

    /** 类名（com.example.App）→ dex 类型（Lcom/example/App;）。 */
    fun toClassType(className: String): String = "L${className.replace('.', '/')};"

    /**
     * 注入 [dexBytes] 中 [classType]（Lcom/x/Y; 形式）的 clinit。
     *
     * @return null = 该 dex 中找不到目标类（调用方应继续尝试下一个 classesN.dex）
     */
    fun inject(dexBytes: ByteArray, classType: String, libName: String): Result? {
        val dex = loadDex(dexBytes)
        val clazz = dex.classes.firstOrNull { it.type == classType } ?: return null

        val clinit = clazz.directMethods.firstOrNull { it.name == "<clinit>" }
        val already = clinit?.containsLoadLibrary(libName) ?: false

        val newDexBytes: ByteArray
        val created: Boolean
        when {
            already -> {
                newDexBytes = dexBytes
                created = false
            }
            clinit != null -> {
                val newMethod = injectIntoClinit(clinit, libName)
                newDexBytes = rewriteDex(dex, clazz, newMethod)
                created = false
            }
            else -> {
                val newMethod = newClinit(classType, libName)
                newDexBytes = rewriteDex(dex, clazz, newMethod)
                created = true
            }
        }
        return Result(
            dexBytes = newDexBytes,
            targetClass = classType,
            method = "<clinit>",
            createdClinit = created,
            alreadyInjected = already,
        )
    }

    // ------------------------------------------------------------------

    private fun injectIntoClinit(clinit: Method, libName: String): ImmutableMethod {
        val impl = clinit.implementation
            ?: throw DexInjectException("clinit 无 code item（抽象？）")
        val mutable = MutableMethodImplementation(impl)
        mutable.addInstruction(0, constString(libName))
        mutable.addInstruction(1, invokeStaticLoadLibrary())
        val registerCount = maxOf(mutable.registerCount, 1)
        val newImpl = ImmutableMethodImplementation(
            registerCount, mutable.instructions, mutable.tryBlocks, mutable.debugItems
        )
        return ImmutableMethod(
            clinit.definingClass, clinit.name, clinit.parameters, clinit.returnType,
            clinit.accessFlags, clinit.annotations, clinit.hiddenApiRestrictions, newImpl,
        )
    }

    private fun newClinit(classType: String, libName: String): ImmutableMethod {
        val impl = MutableMethodImplementation(1)
        impl.addInstruction(constString(libName))
        impl.addInstruction(invokeStaticLoadLibrary())
        impl.addInstruction(BuilderInstruction10x(RETURN_VOID))
        return ImmutableMethod(
            classType, "<clinit>",
            emptyList<MethodParameter>(), "V",
            AccessFlags.STATIC.getValue() or AccessFlags.CONSTRUCTOR.getValue(),
            emptySet<Annotation>(), emptySet<HiddenApiRestriction>(),
            ImmutableMethodImplementation.of(impl),
        )
    }

    private fun constString(libName: String) = BuilderInstruction21c(
        CONST_STRING, 0, ImmutableStringReference(libName)
    )

    private fun invokeStaticLoadLibrary() = BuilderInstruction35c(
        INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
        ImmutableMethodReference("Ljava/lang/System;", "loadLibrary", listOf("Ljava/lang/String;"), "V"),
    )

    private fun rewriteDex(dex: DexFile, clazz: ClassDef, newMethod: Method): ByteArray {
        val newDirectMethods = clazz.directMethods.toMutableList()
        val idx = newDirectMethods.indexOfFirst { it.name == "<clinit>" }
        if (idx >= 0) newDirectMethods[idx] = newMethod else newDirectMethods.add(newMethod)
        val newClass = ImmutableClassDef(
            clazz.type, clazz.accessFlags, clazz.superclass, clazz.interfaces, clazz.sourceFile,
            clazz.annotations, clazz.staticFields, clazz.instanceFields,
            newDirectMethods, clazz.virtualMethods,
        )
        val newClasses = dex.classes.map { if (it.type == clazz.type) newClass else it }
        val newDex = ImmutableDexFile(dex.opcodes, newClasses)
        return writeDex(newDex)
    }

    // ------------------------------------------------------------------

    private fun Method.containsLoadLibrary(libName: String): Boolean {
        val impl = implementation ?: return false
        return impl.instructions.any { ins ->
            ins is Instruction21c && ins.opcode == CONST_STRING &&
                (ins.reference as? StringReference)?.string == libName
        }
    }

    private fun loadDex(bytes: ByteArray): DexFile {
        if (bytes.size < 8) throw DexInjectException("dex 过短")
        val magic = String(bytes, 4, 3, Charsets.US_ASCII)
        val f = File.createTempFile("fler-inject", ".dex")
        try {
            f.writeBytes(bytes)
            return DexFileFactory.loadDexFile(f, opcodesFor(magic))
        } catch (e: Exception) {
            throw DexInjectException("dex 解析失败（magic=0x$magic）: ${e.message}", e)
        } finally {
            f.delete()
        }
    }

    private fun writeDex(dex: DexFile): ByteArray {
        val f = File.createTempFile("fler-inject-out", ".dex")
        try {
            DexFileFactory.writeDexFile(f.absolutePath, dex)
            return f.readBytes()
        } catch (e: Exception) {
            throw DexInjectException("dex 写出失败: ${e.message}", e)
        } finally {
            f.delete()
        }
    }

    /** dex 版本号 → Opcodes（加载期校验指令集与版本匹配）。 */
    private fun opcodesFor(magic: String): Opcodes = when (magic) {
        "035" -> Opcodes.forApi(23)
        "036" -> Opcodes.forApi(24)
        "037" -> Opcodes.forApi(26)
        "038" -> Opcodes.forApi(27)
        "039" -> Opcodes.forApi(28)
        else -> Opcodes.getDefault()
    }

    class DexInjectException(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)
}
