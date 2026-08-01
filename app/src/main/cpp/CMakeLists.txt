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
)

# ========== 查找 Android 系统库 ==========
find_library(log-lib log)
find_library(android-lib android)

# ========== 原生静态库 (供其他模块链接) ==========
add_library(fler_native STATIC
    elf_parser/elf_parser.cpp
    elf_parser/elf_writer.cpp
    arm64_encoder/encoder.cpp
    arm64_encoder/decoder.cpp
)

target_link_libraries(fler_native
    ${log-lib}
)

# ========== JNI 桥接库 ==========
add_library(fler_jni SHARED
    jni_bridge/blutter_jni.cpp
    jni_bridge/elf_parser_jni.cpp
    jni_bridge/arm64_encoder_jni.cpp
    jni_bridge/capstone_jni.cpp
)

target_link_libraries(fler_jni
    fler_native
    ${log-lib}
    ${android-lib}
)

# 导出符号可见性
set_target_properties(fler_jni PROPERTIES
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN ON
)
