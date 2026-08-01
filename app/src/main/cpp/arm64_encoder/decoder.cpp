#include "decoder.h"
#include <cstdio>
#include <cstring>
#include <cstdint>

namespace fler {
namespace arm64 {

namespace {

// 取指令字段
inline uint32_t bits(uint32_t insn, int lo, int hi) {
    uint32_t mask = ((1u << (hi - lo + 1)) - 1) << lo;
    return (insn & mask) >> lo;
}
inline int64_t sext(uint32_t val, int bits) {
    int shift = 32 - bits;
    return static_cast<int64_t>(static_cast<int32_t>(val << shift)) >> shift;
}
inline std::string regName(uint32_t idx, bool w) {
    char buf[8];
    snprintf(buf, sizeof(buf), "%s%u", w ? "w" : "x", idx);
    return buf;
}

struct Result {
    bool known = false;
    std::string mn;
    std::string ops;
};

// 加载/存储：无符号立即数偏移 LDR/STR/LDRB/STRH 等
Result decodeLoadStore(uint32_t insn) {
    Result r;
    uint32_t size = bits(insn, 30, 31);
    uint32_t opc = bits(insn, 22, 23);
    uint32_t v = bits(insn, 26, 26);
    if (v != 0) return r; // SIMD，跳过
    uint32_t imm = bits(insn, 10, 21);
    uint32_t rn = bits(insn, 5, 9);
    uint32_t rt = bits(insn, 0, 4);
    bool w = size != 3; // 32-bit for size<3

    char buf[64];
    if (opc == 0) { // LDR
        if (size == 0) r.mn = "ldrb";
        else if (size == 1) r.mn = "ldrh";
        else if (size == 2) r.mn = "ldr";
        else r.mn = "ldr";
    } else if (opc == 1) { // LDRSW (size==2)
        r.mn = "ldrsw";
        w = false;
    } else if (opc == 2) { // STR
        if (size == 0) r.mn = "strb";
        else if (size == 1) r.mn = "strh";
        else r.mn = "str";
    } else {
        return r;
    }
    // 64-bit LDR/STR 用 x 寄存器；32-bit 用 w；size 为 3 时 64-bit
    bool is64 = (size == 3 && (opc == 0 || opc == 2));
    snprintf(buf, sizeof(buf), "%s, [%s, #%u]", regName(rt, !is64).c_str(), regName(rn, false).c_str(), imm);
    r.ops = buf;
    r.known = true;
    return r;
}

// 分支与链接
Result decodeBranch(uint32_t insn) {
    Result r;
    uint32_t top = insn & 0xFC000000u;
    if (top == 0x14000000u || top == 0x94000000u) {
        int64_t off = sext(bits(insn, 0, 25), 26) << 2;
        r.mn = (top == 0x94000000u) ? "bl" : "b";
        char buf[32];
        snprintf(buf, sizeof(buf), "#0x%llx", (unsigned long long)off);
        r.ops = buf;
        r.known = true;
        return r;
    }
    // CBZ/CBNZ: 0x34000000/0xB4000000 (cbz), 0x35000000/0xB5000000 (cbnz)
    uint32_t sf = bits(insn, 31, 31);
    uint32_t op = bits(insn, 24, 24);
    if ((insn & 0x7E000000u) == 0x34000000u) {
        int64_t off = sext(bits(insn, 5, 23), 19) << 2;
        uint32_t rt = bits(insn, 0, 4);
        r.mn = (op == 0) ? "cbz" : "cbnz";
        char buf[64];
        snprintf(buf, sizeof(buf), "%s, #0x%llx", regName(rt, sf == 0).c_str(), (unsigned long long)off);
        r.ops = buf;
        r.known = true;
        return r;
    }
    // TBZ/TBNZ: 0x36000000/0x37000000
    if ((insn & 0x7E000000u) == 0x36000000u) {
        uint32_t b5 = bits(insn, 31, 31);
        uint32_t bit = (b5 << 5) | bits(insn, 19, 23);
        uint32_t rt = bits(insn, 0, 4);
        int64_t off = sext(bits(insn, 5, 18), 14) << 2;
        r.mn = (bits(insn, 24, 24) == 0) ? "tbz" : "tbnz";
        char buf[64];
        snprintf(buf, sizeof(buf), "%s, #%u, #0x%llx", regName(rt, b5 == 0).c_str(), bit, (unsigned long long)off);
        r.ops = buf;
        r.known = true;
        return r;
    }
    return r;
}

// BR/BLR/RET 及 NOP/HLT
Result decodeSystem(uint32_t insn) {
    Result r;
    uint32_t base = insn & 0xFFFFFC1Fu;
    uint32_t rn = bits(insn, 5, 9);
    if (base == 0xD61F0000u) { r.mn = "br"; r.ops = regName(rn, false); r.known = true; }
    else if (base == 0xD63F0000u) { r.mn = "blr"; r.ops = regName(rn, false); r.known = true; }
    else if (base == 0xD65F0000u) { r.mn = "ret"; r.ops = (rn == 30) ? "" : regName(rn, false); r.known = true; }
    else if (insn == 0xD503201Fu) { r.mn = "nop"; r.known = true; }
    else if ((insn & 0xFFE0001Fu) == 0xD4200000u) { r.mn = "hlt"; r.known = true; }
    return r;
}

// 整数/逻辑立即数（ADD/SUB/CMP/CMN/AND/MOVZ 族）
Result decodeDataProcessingImm(uint32_t insn) {
    Result r;
    uint32_t sf = bits(insn, 31, 31);
    uint32_t opc = bits(insn, 29, 30);
    uint32_t op2 = bits(insn, 23, 24);
    if (op2 == 2) { // 100010 add/sub immediate
        uint32_t rn = bits(insn, 5, 9);
        uint32_t rd = bits(insn, 0, 4);
        uint32_t imm12 = bits(insn, 10, 21);
        bool w = sf == 0;
        char buf[64];
        if (opc == 0) { r.mn = "add"; }
        else if (opc == 1) { r.mn = "adds"; }
        else if (opc == 2) { r.mn = "sub"; }
        else { r.mn = "subs"; }
        if (rd == 31 && (opc == 1 || opc == 3)) {
            // CMP/CMN 别名
            r.mn = (opc == 1) ? "cmn" : "cmp";
            snprintf(buf, sizeof(buf), "%s, #%u", regName(rn, w).c_str(), imm12);
        } else {
            snprintf(buf, sizeof(buf), "%s, %s, #%u", regName(rd, w).c_str(), regName(rn, w).c_str(), imm12);
        }
        r.ops = buf;
        r.known = true;
        return r;
    }
    if (op2 == 3) { // MOVZ/MOVN/MOVK 100101
        uint32_t hw = bits(insn, 21, 22);
        uint32_t imm16 = bits(insn, 5, 20);
        uint32_t rd = bits(insn, 0, 4);
        bool w = sf == 0;
        char buf[64];
        if (opc == 0) r.mn = "movz";
        else if (opc == 1) r.mn = "movn";
        else r.mn = "movk";
        if (hw == 0) {
            snprintf(buf, sizeof(buf), "%s, #%u", regName(rd, w).c_str(), imm16);
        } else {
            snprintf(buf, sizeof(buf), "%s, #%u, lsl #%u", regName(rd, w).c_str(), imm16, hw * 16);
        }
        r.ops = buf;
        r.known = true;
        return r;
    }
    // ADR/ADRP 10000
    if (bits(insn, 24, 27) == 0 && bits(insn, 28, 28) == 0 && bits(insn, 31, 31) == 1 &&
        bits(insn, 23, 23) == 1 && bits(insn, 29, 30) == 0 && bits(insn, 26, 26) == 1 && bits(insn, 27, 27) == 0) {
        uint32_t rd = bits(insn, 0, 4);
        int64_t immlo = bits(insn, 29, 30) & 3;
        int64_t immhi = sext(bits(insn, 5, 23), 19) << 2;
        uint32_t op = bits(insn, 31, 31);
        r.mn = (op == 1) ? "adrp" : "adr";
        int64_t imm = (op == 1) ? ((immhi << 2) | immlo) : (immhi | immlo);
        char buf[64];
        snprintf(buf, sizeof(buf), "%s, #0x%llx", regName(rd, false).c_str(), (unsigned long long)imm);
        r.ops = buf;
        r.known = true;
    }
    return r;
}

// 寄存器逻辑/移位（ORR/AND/EOR/BIC/MOV 别名）
Result decodeLogicalReg(uint32_t insn) {
    Result r;
    if (bits(insn, 28, 28) != 0 || bits(insn, 29, 30) == 3) return r; // 仅 0101xxxx 逻辑类
    uint32_t opc = bits(insn, 29, 30);
    uint32_t n = bits(insn, 21, 21);
    uint32_t rm = bits(insn, 16, 20);
    uint32_t imm6 = bits(insn, 10, 15);
    uint32_t rn = bits(insn, 5, 9);
    uint32_t rd = bits(insn, 0, 4);
    bool w = bits(insn, 31, 31) == 0;
    char buf[96];
    const char* mn;
    if (opc == 0) mn = n ? "bic" : "and";
    else if (opc == 1) mn = n ? "orn" : "orr";
    else if (opc == 2) mn = n ? "eon" : "eor";
    else return r;
    if (opc == 1 && rn == 31) {
        // MOV 别名
        r.mn = "mov";
        snprintf(buf, sizeof(buf), "%s, %s", regName(rd, w).c_str(), regName(rm, w).c_str());
    } else {
        r.mn = mn;
        if (imm6 == 0) {
            snprintf(buf, sizeof(buf), "%s, %s, %s", regName(rd, w).c_str(), regName(rn, w).c_str(), regName(rm, w).c_str());
        } else {
            snprintf(buf, sizeof(buf), "%s, %s, %s, lsl #%u", regName(rd, w).c_str(), regName(rn, w).c_str(), regName(rm, w).c_str(), imm6);
        }
    }
    r.ops = buf;
    r.known = true;
    return r;
}

// STP/LDP（签名立即数，pre/post index 简化只识别 signed offset）
Result decodePair(uint32_t insn) {
    Result r;
    uint32_t base = insn & 0x7FC00000u;
    bool ldp = (base == 0x29400000u || base == 0xA9400000u);
    bool stp = (base == 0x29000000u || base == 0xA9000000u);
    if (!ldp && !stp) return r;
    bool w = bits(insn, 31, 31) == 0;
    uint32_t rt1 = bits(insn, 0, 4);
    uint32_t rn = bits(insn, 5, 9);
    uint32_t rt2 = bits(insn, 10, 14);
    int64_t imm = sext(bits(insn, 15, 21), 7) << 3;
    r.mn = ldp ? "ldp" : "stp";
    char buf[64];
    snprintf(buf, sizeof(buf), "%s, %s, [%s, #%lld]", regName(rt1, w).c_str(), regName(rt2, w).c_str(), regName(rn, false).c_str(), (long long)imm);
    r.ops = buf;
    r.known = true;
    return r;
}

// 条件选择（CSEL/CSINC/CSET 等，近似）
Result decodeCondSelect(uint32_t insn) {
    Result r;
    if ((insn & 0x1FE00000u) != 0x1A800000u) return r;
    uint32_t cond = bits(insn, 12, 15);
    uint32_t op = bits(insn, 10, 11);
    uint32_t rn = bits(insn, 5, 9);
    uint32_t rd = bits(insn, 0, 4);
    bool w = bits(insn, 31, 31) == 0;
    static const char* conds[] = {"eq","ne","hs","lo","mi","pl","vs","vc","hi","ls","ge","lt","gt","le","al","nv"};
    const char* cc = (cond < 16) ? conds[cond] : "?";
    char buf[64];
    if (op == 0) {
        uint32_t rm = bits(insn, 16, 20);
        snprintf(buf, sizeof(buf), "%s, %s, %s, %s", regName(rd, w).c_str(), regName(rn, w).c_str(), regName(rm, w).c_str(), cc);
        r.mn = "csel";
    } else if (op == 1) {
        if (rn == 31 && bits(insn, 16, 20) == 31) {
            r.mn = "cset";
            snprintf(buf, sizeof(buf), "%s, %s", regName(rd, w).c_str(), cc);
        } else {
            uint32_t rm = bits(insn, 16, 20);
            snprintf(buf, sizeof(buf), "%s, %s, %s, %s", regName(rd, w).c_str(), regName(rn, w).c_str(), regName(rm, w).c_str(), cc);
            r.mn = "csinc";
        }
    } else {
        return r;
    }
    r.ops = buf;
    r.known = true;
    return r;
}

// 数据处理 - 寄存器（ADD/SUB/MUL/NEG/ADC/SBC/MADD/MSUB 等寄存器形式）
// 覆盖 0x0B/0x1B 系列：ADD/SUB (shifted register)、ADD/SUB (extended register)、
// ADC/SBC、MUL/UMULH/SMULH、MADD/MSUB
Result decodeDataProcessingReg(uint32_t insn) {
    Result r;
    // 识别 01011 / 11011 系列（bits[28:24] == 0b01011）
    uint32_t op2 = bits(insn, 24, 28);
    if (op2 != 0b01011) return r;

    uint32_t sf = bits(insn, 31, 31);
    uint32_t opc = bits(insn, 29, 30);
    uint32_t rd = bits(insn, 0, 4);
    uint32_t rn = bits(insn, 5, 9);
    uint32_t rm = bits(insn, 16, 20);
    bool w = sf == 0;
    char buf[96];

    // bit 21 = 0: shifted register; bit 21 = 1: extended register
    uint32_t bit6 = bits(insn, 21, 21); // extended register 标志
    uint32_t shift = bits(insn, 22, 23); // 00 LSL 01 LSR 10 ASR 11 reserved
    uint32_t imm6 = bits(insn, 10, 15);
    uint32_t s = bits(insn, 29, 29); // S 位（看 opc 低 bit）

    // ADD/SUB shifted register: bit21=0
    if (bit6 == 0 && (opc == 0 || opc == 1 || opc == 2 || opc == 3)) {
        const char* mn;
        if (opc == 0) mn = "add";
        else if (opc == 1) mn = "adds";
        else if (opc == 2) mn = "sub";
        else mn = "subs";
        // CMP/CMN 别名：rd == 31 且 S=1
        if (rd == 31 && (opc == 1 || opc == 3)) {
            r.mn = (opc == 1) ? "cmn" : "cmp";
            if (imm6 == 0) {
                snprintf(buf, sizeof(buf), "%s, %s", regName(rn, w).c_str(), regName(rm, w).c_str());
            } else {
                static const char* shifts[] = {"lsl", "lsr", "asr", "ror"};
                snprintf(buf, sizeof(buf), "%s, %s, %s, #%u",
                    regName(rn, w).c_str(), regName(rm, w).c_str(),
                    shifts[shift & 3], imm6);
            }
        } else if (opc == 1 && rd == 31 && rn == 31) {
            // NEG 别名（SUBS Xd, XZR, Xm）
            r.mn = "neg";
            if (imm6 == 0) snprintf(buf, sizeof(buf), "%s, %s", regName(rd, w).c_str(), regName(rm, w).c_str());
            else {
                static const char* shifts[] = {"lsl", "lsr", "asr", "ror"};
                snprintf(buf, sizeof(buf), "%s, %s, %s, #%u", regName(rd, w).c_str(), regName(rm, w).c_str(), shifts[shift & 3], imm6);
            }
        } else {
            if (imm6 == 0) {
                snprintf(buf, sizeof(buf), "%s, %s, %s", regName(rd, w).c_str(), regName(rn, w).c_str(), regName(rm, w).c_str());
            } else {
                static const char* shifts[] = {"lsl", "lsr", "asr", "ror"};
                snprintf(buf, sizeof(buf), "%s, %s, %s, %s, #%u",
                    regName(rd, w).c_str(), regName(rn, w).c_str(), regName(rm, w).c_str(),
                    shifts[shift & 3], imm6);
            }
        }
        r.ops = buf;
        r.known = true;
        return r;
    }

    // ADC/SBC: bits[30:29]=00 → ADC, 10 → SBC, 01 → ADCS, 11 → SBCS
    // 这些是 11010000 系列（bit 24=0, bit 21=0, bit 28:24 = 11011）
    // 实际上 ADC/SBC 编码：11010000 Rm 000000 Rn Rd
    if ((insn & 0x7F200000u) == 0x1A000000u) {
        const char* mn;
        if (opc == 0) mn = "adc";
        else if (opc == 1) mn = "adcs";
        else if (opc == 2) mn = "sbc";
        else mn = "sbcs";
        snprintf(buf, sizeof(buf), "%s, %s, %s", regName(rd, w).c_str(), regName(rn, w).c_str(), regName(rm, w).c_str());
        r.mn = mn;
        r.ops = buf;
        r.known = true;
        return r;
    }

    // MUL/MSUB/MADD: Data Processing - 3 source
    // 11011 000 Rm 0 o1 Ra Rn Rd  (0x1B000000 系列，o1=0 → MADD, o1=1 → MSUB)
    if ((insn & 0x7F000000u) == 0x1B000000u) {
        uint32_t o1 = bits(insn, 15, 15);
        uint32_t ra = bits(insn, 10, 14);
        if (ra == 31) {
            // MUL Xd, Xn, Xm
            r.mn = "mul";
            snprintf(buf, sizeof(buf), "%s, %s, %s", regName(rd, w).c_str(), regName(rn, w).c_str(), regName(rm, w).c_str());
        } else {
            r.mn = o1 ? "msub" : "madd";
            snprintf(buf, sizeof(buf), "%s, %s, %s, %s", regName(rd, w).c_str(), regName(rn, w).c_str(), regName(rm, w).c_str(), regName(ra, w).c_str());
        }
        r.ops = buf;
        r.known = true;
        return r;
    }

    return r;
}

// 数据处理 - 1 source（NEG/NEGS/REV/CLZ/RBIT 等）
// 01011010110 xxxx 000000 Rn Rd  (0x5AC00000 系列，sf=0; 0xDAC00000 sf=1)
Result decodeDataProcessing1Source(uint32_t insn) {
    Result r;
    // sf 0 0 110101100 xxxx 000000 Rn Rd
    if ((insn & 0x5FFC0000u) != 0x5AC00000u) return r;
    uint32_t sf = bits(insn, 31, 31);
    uint32_t opcode2 = bits(insn, 10, 15);
    uint32_t rn = bits(insn, 5, 9);
    uint32_t rd = bits(insn, 0, 4);
    bool w = sf == 0;
    const char* mn = nullptr;
    switch (opcode2) {
        case 0b000000: mn = "rev16"; break;
        case 0b000001: mn = "rev32"; break;
        case 0b000010: mn = (sf == 1) ? "rev64" : "rev32"; break; // rev / rev64
        case 0b000100: mn = "clz"; break;
        case 0b000101: mn = "cls"; break;
        case 0b000110: mn = "rbit"; break;
        default: return r;
    }
    char buf[64];
    snprintf(buf, sizeof(buf), "%s, %s", regName(rd, w).c_str(), regName(rn, w).c_str());
    r.mn = mn;
    if (opcode2 == 0b000010 && sf == 1) r.mn = "rev";
    r.ops = buf;
    r.known = true;
    return r;
}

} // namespace

std::vector<DecodedInstruction> disassemble(const uint8_t* code, size_t size, uint64_t baseAddr) {
    std::vector<DecodedInstruction> result;
    size_t count = size / 4;
    result.reserve(count);

    for (size_t i = 0; i < count; ++i) {
        uint32_t insn = static_cast<uint32_t>(code[i * 4]) |
                        (static_cast<uint32_t>(code[i * 4 + 1]) << 8) |
                        (static_cast<uint32_t>(code[i * 4 + 2]) << 16) |
                        (static_cast<uint32_t>(code[i * 4 + 3]) << 24);

        DecodedInstruction di;
        di.address = baseAddr + i * 4;
        di.raw = insn;

        Result r;
        if (!r.known) r = decodeSystem(insn);
        if (!r.known) r = decodeBranch(insn);
        if (!r.known) r = decodeLoadStore(insn);
        if (!r.known) r = decodePair(insn);
        if (!r.known) r = decodeDataProcessingImm(insn);
        if (!r.known) r = decodeDataProcessingReg(insn);
        if (!r.known) r = decodeDataProcessing1Source(insn);
        if (!r.known) r = decodeLogicalReg(insn);
        if (!r.known) r = decodeCondSelect(insn);

        if (r.known) {
            di.mnemonic = r.mn;
            di.operands = r.ops;
        } else {
            di.mnemonic = ".word";
            char buf[16];
            snprintf(buf, sizeof(buf), "0x%08x", insn);
            di.operands = buf;
        }
        result.push_back(di);
    }
    return result;
}

} // namespace arm64
} // namespace fler
