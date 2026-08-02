cmake_minimum_required(VERSION 3.22.1)

project("fler")

# ========== 编译选项 ==========
add_compile_options(
    -std=c++20
    -fvisibility=hidden
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

# Capstone 静态库：build-rizin workflow 产物（libcapstone.a + include/capstone），
# 静态链接进 fler_jni，SO 编辑器反汇编不再依赖引擎包 libcapstone.so
add_library(capstone STATIC IMPORTED)
set_target_properties(capstone PROPERTIES
    IMPORTED_LOCATION "${FLER_LIBS_DIR}/libcapstone.a"
)

# Rizin 静态库（librz_*.a）：build-rizin workflow 产物。cs_open 等符号在 .a 中为
# undefined，链接期由上面的 capstone 解析。当前 fler_jni 尚无 rizin 引用，普通
# 链接不会被提取；等 RizinEngine JNI 就位后自动生效。
file(GLOB RIZIN_STATIC_LIBS "${FLER_LIBS_DIR}/librz_*.a")

# ========== 查找 Android 系统库 ==========
find_library(log-lib log)
find_library(android-lib android)

# ========== 原生静态库 (供其他模块链接) ==========
# 解码/编码：自研编码器、自研解码器、capstone、elf_writer 均移除；编码仅用 Keystone。
add_library(fler_native STATIC
    elf_parser/elf_parser.cpp
)

target_link_libraries(fler_native
    ${log-lib}
)

# ========== JNI 桥接库 ==========
add_library(fler_jni SHARED
    jni_bridge/blutter_jni.cpp
    jni_bridge/elf_parser_jni.cpp
    jni_bridge/capstone_jni.cpp
    jni_bridge/keystone_jni.cpp
    jni_bridge/rizin_jni.cpp
)

target_link_libraries(fler_jni
    fler_native
    keystone
    capstone
    ${RIZIN_STATIC_LIBS}
    ${log-lib}
    ${android-lib}
)

# 导出符号可见性
set_target_properties(fler_jni PROPERTIES
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN ON
)
