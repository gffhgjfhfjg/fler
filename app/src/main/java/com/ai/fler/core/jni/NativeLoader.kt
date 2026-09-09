package com.ai.fler.core.jni

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 原生库加载器。
 *
 * libfler_jni.so 已按组件拆分为多个 so（见 app/src/main/cpp/CMakeLists.txt）：
 * - fler_jni     核心（elf_parser + blutter），App 启动即加载
 * - fler_asm     capstone + keystone（反汇编/汇编），懒加载
 * - fler_rizin   rizin（SO 分析），懒加载
 * - fler_unicorn unicorn（仿真），懒加载
 * - fler_frida   frida 客户端（动态插桩），懒加载
 * - fler_capstone capstone 共享库，是 asm/rizin 的 DT_NEEDED 依赖，
 *                 由 linker 自动加载，无需在此显式加载
 *
 * 各组件 *Bindings 在首个 native 调用前通过 [ensureComponent] / [tryLoadComponent]
 * 懒加载对应 so；未用到的组件不进内存（降低 RSS）。
 */
object NativeLoader {

    private const val TAG = "NativeLoader"

    /** 原生组件。[libName] 与 System.loadLibrary 名称一致。 */
    enum class Component(val libName: String) {
        CORE("fler_jni"),
        ASM("fler_asm"),
        RIZIN("fler_rizin"),
        UNICORN("fler_unicorn"),
        FRIDA("fler_frida"),
    }

    /** libName -> 是否已成功加载。成功后走无锁快路径。 */
    private val loaded = ConcurrentHashMap<String, Boolean>()

    /** 加载失败已被 [tryLoadComponent] 记录的组件，避免可用性探测反复重试。 */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /**
     * 加载核心组件（elf_parser + blutter）。
     * 幂等操作，可安全多次调用；失败抛 [UnsatisfiedLinkError]（与拆分前行为一致）。
     */
    fun load() {
        ensureComponent(Component.CORE)
    }

    /**
     * 确保组件 so 已加载，未加载则同步加载。
     * 已成功加载则立即返回；失败抛 [UnsatisfiedLinkError]。
     */
    fun ensureComponent(component: Component) {
        if (loaded[component.libName] == true) return
        synchronized(this) {
            if (loaded[component.libName] == true) return
            System.loadLibrary(component.libName)
            loaded[component.libName] = true
            failed.remove(component.libName)
            Log.i(TAG, "Native component '${component.libName}' loaded")
        }
    }

    /**
     * 尝试加载组件 so；失败（so 缺失/损坏）不抛异常，返回 false 并缓存失败结果。
     * 供各 Bindings 的可用性探测 / 降级返回路径使用。
     */
    fun tryLoadComponent(component: Component): Boolean {
        if (loaded[component.libName] == true) return true
        if (component.libName in failed) return false
        return try {
            ensureComponent(component)
            true
        } catch (e: UnsatisfiedLinkError) {
            failed.add(component.libName)
            Log.e(TAG, "Native component '${component.libName}' load failed", e)
            false
        }
    }
}
