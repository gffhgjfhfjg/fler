cmake_minimum_required(VERSION 3.22.1)

project("fler")

# ========== 编译选项 ==========
add_compile_options(
    -std=c++20
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -Wall
    -Wextra
    -fPIC
)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_definitions(DEBUG)
    add_compile_options(-O0 -g)
else()
    add_compile_options(-O2)
endif()

# ========== 头文件路径 ==========
include_directories(
    .
    keystone_include
    include                # include/capstone、include/rizin（workflow 产物解压至此）
    include/frida          # include/frida/frida-core.h（frida-core-devkit 产物）
    include/rizin          # Rizin 内部 #include <rz_types.h> 不带前缀，需直接暴露 rizin/ 目录
    include/rizin/rz_util  # rz_util 内部子头文件
    include/rizin/sdb      # sdb 头文件
)

# ========== 静态库目录（GitHub Actions/workflow 交叉编译产物） ==========
set(FLER_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/arm64-v8a")

# Keystone 静态库：由 app/build.gradle.kts 的 fetchKeystone 任务下载到 libs/
add_library(keystone STATIC IMPORTED)
set_target_properties(keystone PROPERTIES
    IMPORTED_LOCATION "${FLER_LIBS_DIR}/libkeystone.a"
)

# Capstone 静态库：build-rizin workflow 产物（libcapstone.a + include/capstone）。
# 由下方 fler_capstone 目标包装为共享库 libfler_capstone.so，
# libfler_asm.so 与 libfler_rizin.so 共享同一份 capstone 代码。
add_library(capstone STATIC IMPORTED)
set_target_properties(capstone PROPERTIES
    IMPORTED_LOCATION "${FLER_LIBS_DIR}/libcapstone.a"
)

# Rizin 静态库（librz_*.a）：build-rizin workflow 产物。cs_open 等符号在 .a 中为
# undefined，链接期由 libfler_capstone.so 解析（DT_NEEDED，运行时 linker 自动加载）。
file(GLOB RIZIN_STATIC_LIBS "${FLER_LIBS_DIR}/librz_*.a")

# Unicorn 仿真引擎静态库：build-unicorn workflow 产物（libunicorn.a + include/unicorn）。
# ENABLE_UNICORN=OFF 或库文件缺失时优雅降级：不链接、不定义宏，
# unicorn_jni.cpp 退化为安全 stub（isAvailable=false），构建保持可用。
option(ENABLE_UNICORN "Build with Unicorn emulation engine" ON)
set(UNICORN_LIB "${FLER_LIBS_DIR}/libunicorn.a")
set(FLER_HAS_UNICORN OFF)
if(ENABLE_UNICORN AND EXISTS "${UNICORN_LIB}")
    set(FLER_HAS_UNICORN ON)
    add_library(unicorn STATIC IMPORTED)
    set_target_properties(unicorn PROPERTIES
        IMPORTED_LOCATION "${UNICORN_LIB}"
    )
elseif(ENABLE_UNICORN)
    message(WARNING "libunicorn.a 不存在（${UNICORN_LIB}），Unicorn 仿真禁用；"
        "运行 build-unicorn workflow 并把产物放入 app/libs/arm64-v8a/")
endif()

# Frida 动态插桩静态库：frida-core-devkit 产物（libfrida-core.a + include/frida/frida-core.h），
# 由 app/build.gradle.kts 的 fetchFridaDevkit 任务下载解压。libfrida-core.a 体积约 260MB，
# 静态链接为 libfler_frida.so 的 frida-client（对端是设备端 root 常驻的 frida-server）。
# 库缺失时优雅降级：不链接、不定义宏，frida_jni.cpp 退化为安全 stub（isAvailable=false）。
option(ENABLE_FRIDA "Build with Frida dynamic instrumentation client" ON)
set(FRIDA_LIB "${FLER_LIBS_DIR}/libfrida-core.a")
set(FLER_HAS_FRIDA OFF)
if(ENABLE_FRIDA AND EXISTS "${FRIDA_LIB}")
    set(FLER_HAS_FRIDA ON)
    add_library(frida_core STATIC IMPORTED)
    set_target_properties(frida_core PROPERTIES
        IMPORTED_LOCATION "${FRIDA_LIB}"
    )
elseif(ENABLE_FRIDA)
    message(WARNING "libfrida-core.a 不存在（${FRIDA_LIB}），Frida 动态插桩禁用；"
        "运行 app/build.gradle.kts :fler:fetchFridaDevkit 任务下载产物")
endif()

# ========== 查找 Android 系统库 ==========
find_library(log-lib log)
find_library(android-lib android)

# ========== 组件 so 布局 ==========
# 原单一 libfler_jni.so 按组件拆分（目的：未用到的组件 so 不加载，降低运行内存）：
#   libfler_jni.so      核心：elf_parser + blutter 桥（App 启动即加载，很小）
#   libfler_capstone.so capstone 共享库（libfler_asm/libfler_rizin 的 NEEDED 依赖，
#                       linker 自动加载，无需 Kotlin 显式加载）
#   libfler_asm.so      capstone + keystone 桥（反汇编/汇编，懒加载）
#   libfler_rizin.so    rizin 桥（SO 分析，懒加载）
#   libfler_unicorn.so  unicorn 桥（仿真，懒加载）
#   libfler_frida.so    frida 桥（动态插桩客户端，懒加载）
# Kotlin 侧见 core/jni/NativeLoader.kt：各 *Bindings 首次调用前 ensureComponent 懒加载。

# 所有 JNI 桥 so 的公共链接设置：
# - 隐藏全部符号，仅保留 Java_* JNI 导出（visibility hidden + exclude-libs）
# - --gc-sections 裁剪未引用函数/数据段（配合 -ffunction-sections/-fdata-sections）
function(fler_apply_jni_link_settings target)
    set_target_properties(${target} PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON
    )
    target_link_options(${target} PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
    )
endfunction()

# ========== 核心：libfler_jni.so（启动即加载） ==========
add_library(fler_native STATIC
    elf_parser/elf_parser.cpp
)

target_link_libraries(fler_native
    ${log-lib}
)

add_library(fler_jni SHARED
    jni_bridge/blutter_jni.cpp
    jni_bridge/elf_parser_jni.cpp
)

target_link_libraries(fler_jni
    fler_native
    ${log-lib}
    ${android-lib}
)

fler_apply_jni_link_settings(fler_jni)

# ========== Capstone 共享库：libfler_capstone.so ==========
# libcapstone.a 的动态包装：
# - libfler_asm.so（capstone_jni.cpp）与 libfler_rizin.so（librz_*.a 引用 cs_*）
#   共享同一份 capstone 代码，避免静态拷贝两份
# - --whole-archive 拉入全部归档成员（共享库语义：对外完整 API）
# - 版本脚本 capstone_export.map 仅导出 cs_* 公共 API，内部符号 local，
#   避免跨 so 符号污染
add_library(fler_capstone SHARED
    capstone_wrapper.cpp
)

target_link_libraries(fler_capstone
    -Wl,--whole-archive
    capstone
    -Wl,--no-whole-archive
    ${log-lib}
)

target_link_options(fler_capstone PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/capstone_export.map"
)

# ========== 汇编组件：libfler_asm.so（懒加载） ==========
add_library(fler_asm SHARED
    jni_bridge/capstone_jni.cpp
    jni_bridge/keystone_jni.cpp
)

target_link_libraries(fler_asm
    keystone
    fler_capstone
    ${log-lib}
    ${android-lib}
)

fler_apply_jni_link_settings(fler_asm)

# ========== Rizin 组件：libfler_rizin.so（懒加载） ==========
add_library(fler_rizin SHARED
    jni_bridge/rizin_jni.cpp
)

target_link_libraries(fler_rizin
    ${RIZIN_STATIC_LIBS}
    fler_capstone
    ${log-lib}
    ${android-lib}
)

fler_apply_jni_link_settings(fler_rizin)

# ========== Unicorn 组件：libfler_unicorn.so（懒加载） ==========
add_library(fler_unicorn SHARED
    jni_bridge/unicorn_jni.cpp
)

target_link_libraries(fler_unicorn
    ${log-lib}
    ${android-lib}
)

fler_apply_jni_link_settings(fler_unicorn)

if(FLER_HAS_UNICORN)
    target_compile_definitions(fler_unicorn PRIVATE FLER_ENABLE_UNICORN)
    target_link_libraries(fler_unicorn unicorn)
endif()

# ========== Frida 组件：libfler_frida.so（懒加载） ==========
add_library(fler_frida SHARED
    jni_bridge/frida_jni.cpp
)

target_link_libraries(fler_frida
    ${log-lib}
    ${android-lib}
)

fler_apply_jni_link_settings(fler_frida)

if(FLER_HAS_FRIDA)
    target_compile_definitions(fler_frida PRIVATE FLER_ENABLE_FRIDA)
    target_link_libraries(fler_frida frida_core)
endif()
