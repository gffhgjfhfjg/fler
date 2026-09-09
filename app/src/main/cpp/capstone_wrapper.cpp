// libfler_capstone.so 的占位翻译单元。
//
// CMake 要求 SHARED 目标至少有一个源文件；本库的实际代码全部来自
// --whole-archive 拉入的 libcapstone.a（见 CMakeLists.txt 的 fler_capstone 目标），
// 对外仅导出 cs_* 公共 API（capstone_export.map）。
