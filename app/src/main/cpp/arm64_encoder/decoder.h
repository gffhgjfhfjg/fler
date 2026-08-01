#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace fler {
namespace arm64 {

struct DecodedInstruction {
    uint64_t address = 0;
    uint32_t size = 4;
    uint32_t raw = 0;
    std::string mnemonic;
    std::string operands;
};

/**
 * 反汇编一段 ARM64 代码。
 *
 * 覆盖与 arm64_encoder 对应的常用指令集（加载/存储、整数运算、移动、地址、分支、
 * 条件选择、系统指令）。未识别的指令输出 ".word 0xXXXXXXXX"，保证不丢指令。
 *
 * @param code 机器码字节
 * @param size 字节数（应为 4 的倍数）
 * @param baseAddr 首条指令的虚拟地址
 * @return 解码后的指令列表
 */
std::vector<DecodedInstruction> disassemble(const uint8_t* code, size_t size, uint64_t baseAddr);

} // namespace arm64
} // namespace fler
