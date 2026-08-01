#include "arm64_encoder.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <cctype>
#include <string>

namespace fler {
namespace arm64 {

// ========== 辅助函数实现 ==========

/**
 * 把 "x0,x1,#4" 规范化为 "x0, x1, #4"（逗号后补空格），让 sscanf %s 能正确分隔。
 *
 * sscanf 的 %s 以空白为分隔符，遇到 "x0," 会把逗号一起读入 rt，
 * 导致 parseRegister("x0,") 失败。本函数在逗号后补一个空格修复该问题。
 */
static std::string normalizeArgs(const char* args) {
    if (!args) return "";
    std::string s(args);
    std::string out;
    out.reserve(s.size() + 8);
    for (size_t i = 0; i < s.size(); ++i) {
        char c = s[i];
        out.push_back(c);
        // 逗号后若无空白则补一个空格
        if (c == ',' && i + 1 < s.size() && !std::isspace(s[i + 1])) {
            out.push_back(' ');
        }
    }
    return out;
}

int parseRegister(const char* reg) {
    if (!reg || !*reg) return -1;

    // sp / xzr / wzr
    std::string s(reg);
    // 去除空白
    while (!s.empty() && std::isspace(s.front())) s.erase(s.begin());
    while (!s.empty() && std::isspace(s.back())) s.pop_back();

    if (s == "sp" || s == "xzr" || s == "wzr") return 31;

    // x0-x30, w0-w30
    if ((s[0] == 'x' || s[0] == 'w') && s.size() > 1) {
        int num = std::atoi(s.c_str() + 1);
        if (num >= 0 && num <= 30) return num;
    }
    return -1;
}

bool parseImmediate(const char* str, int64_t& value) {
    if (!str) return false;
    // 跳过空白
    while (*str && std::isspace(*str)) str++;
    if (*str == '#') str++;
    if (*str == 0) return false;

    // 16 进制
    if ((str[0] == '0' && (str[1] == 'x' || str[1] == 'X'))) {
        value = std::strtoll(str, nullptr, 16);
    } else {
        value = std::strtoll(str, nullptr, 10);
    }
    return true;
}

bool parseBranchTarget(const char* args, uint64_t& target) {
    if (!args) return false;
    // 跳过空白
    while (*args && std::isspace(*args)) args++;

    // 尝试解析为数字（绝对地址或 PC 相对偏移）
    char* end = nullptr;
    uint64_t val = 0;
    if (args[0] == '0' && (args[1] == 'x' || args[1] == 'X')) {
        val = std::strtoull(args, &end, 16);
    } else if (args[0] == '-') {
        val = std::strtoull(args, &end, 10);
        // 负数处理
        int64_t signedVal = std::strtoll(args, nullptr, 10);
        target = static_cast<uint64_t>(signedVal);
        return true;
    } else {
        val = std::strtoull(args, &end, 10);
    }

    if (end == args) return false;
    target = val;
    return true;
}

// ========== 编码工具 ==========

uint32_t encodeADDImmediate(uint32_t rd, uint32_t rn, uint32_t imm12) {
    // sf=1, op=0, S=0, 10001 | sh | imm12 | Rn | Rd  (ADD immediate, 64-bit)
    // 64-bit ADD imm: 1001 0001 .... .... .... .... .... ....
    return (1u << 31) |
           (0b001 << 28) | (0b001 << 24) |  // 10010001
           ((imm12 & 0xFFF) << 10) |
           ((rn & 0x1F) << 5) |
           (rd & 0x1F);
}

uint32_t encodeBL(int64_t offset) {
    // 100101 | imm26
    int64_t imm26 = offset >> 2;
    return (0b100101u << 26) | (imm26 & 0x03FFFFFF);
}

uint32_t encodeB(int64_t offset) {
    // 000101 | imm26
    int64_t imm26 = offset >> 2;
    return (0b000101u << 26) | (imm26 & 0x03FFFFFF);
}

// ========== Arm64Encoder 实现 ==========

Arm64Encoder::Arm64Encoder() {
    registerBuiltins();
}

Arm64Encoder& Arm64Encoder::instance() {
    static Arm64Encoder inst;
    return inst;
}

bool Arm64Encoder::registerInstruction(const char* name, EncoderFn fn) {
    if (!name || !fn) return false;
    registry_[name] = std::move(fn);
    return true;
}

bool Arm64Encoder::encode(const char* name, const char* args, uint32_t& encoding) {
    auto it = registry_.find(name);
    if (it == registry_.end()) return false;
    // 规范化操作数：逗号后补空格，让 sscanf %s 不把逗号当字符读入
    std::string normalized = normalizeArgs(args);
    return it->second(normalized.c_str(), encoding);
}

std::vector<std::string> Arm64Encoder::listInstructions() const {
    std::vector<std::string> result;
    result.reserve(registry_.size());
    for (auto& kv : registry_) {
        result.push_back(kv.first);
    }
    std::sort(result.begin(), result.end());
    return result;
}

// ========== 内置指令编码函数 ==========
// 参考 ARM Architecture Reference Manual for A-profile architecture
// 所有 64-bit 寄存器指令 sf=1（除明确指定 W 寄存器的）

// --- 加载/存储 (Load/Store) ---

static bool encodeLDR(const char* args, uint32_t& encoding) {
    // LDR Xt, [Xn, #imm12]  (64-bit, unsigned offset)
    // 1111 1001 01.. .... .... .... .... ....  (0xF940_0000 base)
    char rt[8], rn[8];
    int imm = 0;
    if (std::sscanf(args, "%7s, %7s, #%d", rt, rn, &imm) >= 2) {
        uint32_t rd = parseRegister(rt);
        uint32_t base = parseRegister(rn);
        if (rd > 31 || base > 31) return false;
        if (imm < 0 || imm > 4095) return false;
        // 64-bit LDR unsigned offset: size=11, opc=01
        encoding = (0xF9u << 24) | (1u << 22) | ((imm & 0xFFF) << 10) | (base << 5) | rd;
        return true;
    }
    // LDR Xt, [Xn] (no offset)
    if (std::sscanf(args, "%7s, %7s", rt, rn) >= 2) {
        uint32_t rd = parseRegister(rt);
        uint32_t base = parseRegister(rn);
        if (rd > 31 || base > 31) return false;
        encoding = (0xF9u << 24) | (1u << 22) | (0u << 10) | (base << 5) | rd;
        return true;
    }
    return false;
}

static bool encodeSTR(const char* args, uint32_t& encoding) {
    // STR Xt, [Xn, #imm12]  (64-bit, unsigned offset)
    // 1111 1001 00.. .... .... .... .... ....  (0xF900_0000 base)
    char rt[8], rn[8];
    int imm = 0;
    if (std::sscanf(args, "%7s, %7s, #%d", rt, rn, &imm) >= 2) {
        uint32_t rd = parseRegister(rt);
        uint32_t base = parseRegister(rn);
        if (rd > 31 || base > 31) return false;
        if (imm < 0 || imm > 4095) return false;
        // 64-bit STR unsigned offset: size=11, opc=00
        encoding = (0xF9u << 24) | (0u << 22) | ((imm & 0xFFF) << 10) | (base << 5) | rd;
        return true;
    }
    if (std::sscanf(args, "%7s, %7s", rt, rn) >= 2) {
        uint32_t rd = parseRegister(rt);
        uint32_t base = parseRegister(rn);
        if (rd > 31 || base > 31) return false;
        encoding = (0xF9u << 24) | (0u << 22) | (0u << 10) | (base << 5) | rd;
        return true;
    }
    return false;
}

static bool encodeLDRB(const char* args, uint32_t& encoding) {
    // LDRB Wt, [Xn, #imm12]  - 8-bit
    char rt[8], rn[8];
    int imm = 0;
    if (std::sscanf(args, "%7s, %7s, #%d", rt, rn, &imm) >= 2) {
        uint32_t rd = parseRegister(rt);
        uint32_t base = parseRegister(rn);
        if (rd > 31 || base > 31) return false;
        if (imm < 0 || imm > 4095) return false;
        // 8-bit LDR unsigned offset: size=00, opc=01
        encoding = (0x39u << 24) | (1u << 22) | ((imm & 0xFFF) << 10) | (base << 5) | rd;
        return true;
    }
    if (std::sscanf(args, "%7s, %7s", rt, rn) >= 2) {
        uint32_t rd = parseRegister(rt);
        uint32_t base = parseRegister(rn);
        if (rd > 31 || base > 31) return false;
        encoding = (0x39u << 24) | (1u << 22) | (0u << 10) | (base << 5) | rd;
        return true;
    }
    return false;
}

static bool encodeSTRB(const char* args, uint32_t& encoding) {
    char rt[8], rn[8];
    int imm = 0;
    if (std::sscanf(args, "%7s, %7s, #%d", rt, rn, &imm) >= 2) {
        uint32_t rd = parseRegister(rt);
        uint32_t base = parseRegister(rn);
        if (rd > 31 || base > 31) return false;
        if (imm < 0 || imm > 4095) return false;
        // 8-bit STR unsigned offset: size=00, opc=00
        encoding = (0x39u << 24) | (0u << 22) | ((imm & 0xFFF) << 10) | (base << 5) | rd;
        return true;
    }
    if (std::sscanf(args, "%7s, %7s", rt, rn) >= 2) {
        uint32_t rd = parseRegister(rt);
        uint32_t base = parseRegister(rn);
        if (rd > 31 || base > 31) return false;
        encoding = (0x39u << 24) | (0u << 22) | (0u << 10) | (base << 5) | rd;
        return true;
    }
    return false;
}

static bool encodeLDRH(const char* args, uint32_t& encoding) {
    char rt[8], rn[8];
    int imm = 0;
    if (std::sscanf(args, "%7s, %7s, #%d", rt, rn, &imm) >= 2) {
        uint32_t rd = parseRegister(rt);
        uint32_t base = parseRegister(rn);
        if (rd > 31 || base > 31) return false;
        if (imm < 0 || imm > 4095) return false;
        // 16-bit LDR unsigned offset: size=01, opc=01
        encoding = (0x79u << 24) | (1u << 22) | ((imm & 0xFFF) << 10) | (base << 5) | rd;
        return true;
    }
    if (std::sscanf(args, "%7s, %7s", rt, rn) >= 2) {
        uint32_t rd = parseRegister(rt);
        uint32_t base = parseRegister(rn);
        if (rd > 31 || base > 31) return false;
        encoding = (0x79u << 24) | (1u << 22) | (0u << 10) | (base << 5) | rd;
        return true;
    }
    return false;
}

static bool encodeSTRH(const char* args, uint32_t& encoding) {
    char rt[8], rn[8];
    int imm = 0;
    if (std::sscanf(args, "%7s, %7s, #%d", rt, rn, &imm) >= 2) {
        uint32_t rd = parseRegister(rt);
        uint32_t base = parseRegister(rn);
        if (rd > 31 || base > 31) return false;
        if (imm < 0 || imm > 4095) return false;
        // 16-bit STR unsigned offset: size=01, opc=00
        encoding = (0x79u << 24) | (0u << 22) | ((imm & 0xFFF) << 10) | (base << 5) | rd;
        return true;
    }
    if (std::sscanf(args, "%7s, %7s", rt, rn) >= 2) {
        uint32_t rd = parseRegister(rt);
        uint32_t base = parseRegister(rn);
        if (rd > 31 || base > 31) return false;
        encoding = (0x79u << 24) | (0u << 22) | (0u << 10) | (base << 5) | rd;
        return true;
    }
    return false;
}

static bool encodeLDP(const char* args, uint32_t& encoding) {
    // LDP Xt1, Xt2, [Xn, #imm7]
    // 1010 1001 0.. .... .... .... .... .... (64-bit, signed offset, imm7 scaled by 8)
    char rt1[8], rt2[8], rn[8];
    int imm = 0;
    if (std::sscanf(args, "%7s, %7s, %7s, #%d", rt1, rt2, rn, &imm) >= 3) {
        uint32_t t1 = parseRegister(rt1);
        uint32_t t2 = parseRegister(rt2);
        uint32_t base = parseRegister(rn);
        if (t1 > 31 || t2 > 31 || base > 31) return false;
        // imm7 是有符号，以 8 字节为单位
        int32_t imm7 = imm / 8;
        if (imm7 < -64 || imm7 > 63) return false;
        encoding = (0xA9u << 24) | (0u << 22) | ((imm7 & 0x7F) << 15) | (t2 << 10) | (base << 5) | t1;
        return true;
    }
    return false;
}

static bool encodeSTP(const char* args, uint32_t& encoding) {
    // STP Xt1, Xt2, [Xn, #imm7]
    // 1010 1001 0.. .... .... .... .... .... (64-bit, signed offset, imm7 scaled by 8)
    char rt1[8], rt2[8], rn[8];
    int imm = 0;
    if (std::sscanf(args, "%7s, %7s, %7s, #%d", rt1, rt2, rn, &imm) >= 3) {
        uint32_t t1 = parseRegister(rt1);
        uint32_t t2 = parseRegister(rt2);
        uint32_t base = parseRegister(rn);
        if (t1 > 31 || t2 > 31 || base > 31) return false;
        int32_t imm7 = imm / 8;
        if (imm7 < -64 || imm7 > 63) return false;
        encoding = (0xA9u << 24) | (0u << 22) | ((imm7 & 0x7F) << 15) | (t2 << 10) | (base << 5) | t1;
        return true;
    }
    return false;
}

// --- 整数运算 (Data Processing - Immediate) ---

static bool encodeADD(const char* args, uint32_t& encoding) {
    // ADD (immediate): sf=1, op=0, S=0, 100010 sh imm12 Rn Rd
    // 1001 0001 0... .... .... .... .... ....
    char rd[8], rn[8];
    int imm = 0;
    char shift[16] = {};
    if (std::sscanf(args, "%7s, %7s, #%d %15s", rd, rn, &imm, shift) >= 3 ||
        std::sscanf(args, "%7s, %7s, #%d", rd, rn, &imm) >= 3) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        if (r > 31 || n > 31 || imm < 0 || imm > 4095) return false;
        encoding = encodeADDImmediate(r, n, imm);
        return true;
    }
    return false;
}

static bool encodeADDS(const char* args, uint32_t& encoding) {
    // ADDS (immediate): sf=1, op=0, S=1, 100010 sh imm12 Rn Rd
    char rd[8], rn[8];
    int imm = 0;
    if (std::sscanf(args, "%7s, %7s, #%d", rd, rn, &imm) >= 3) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        if (r > 31 || n > 31 || imm < 0 || imm > 4095) return false;
        encoding = (1u << 31) | (0b001 << 29) | (0b10001 << 24) |
                   ((imm & 0xFFF) << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeSUB(const char* args, uint32_t& encoding) {
    // SUB (immediate): sf=1, op=1, S=0, 100010 sh imm12 Rn Rd
    char rd[8], rn[8];
    int imm = 0;
    if (std::sscanf(args, "%7s, %7s, #%d", rd, rn, &imm) >= 3) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        if (r > 31 || n > 31 || imm < 0 || imm > 4095) return false;
        encoding = (1u << 31) | (0b1 << 30) | (0b001 << 29) | (0b10001 << 24) |
                   ((imm & 0xFFF) << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeSUBS(const char* args, uint32_t& encoding) {
    char rd[8], rn[8];
    int imm = 0;
    if (std::sscanf(args, "%7s, %7s, #%d", rd, rn, &imm) >= 3) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        if (r > 31 || n > 31 || imm < 0 || imm > 4095) return false;
        encoding = (1u << 31) | (0b1 << 30) | (0b011 << 29) | (0b10001 << 24) |
                   ((imm & 0xFFF) << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeCMP(const char* args, uint32_t& encoding) {
    // CMP Xn, #imm  =>  SUBS XZR, Xn, #imm
    char rn[8];
    int imm = 0;
    if (std::sscanf(args, "%7s, #%d", rn, &imm) >= 2) {
        uint32_t n = parseRegister(rn);
        if (n > 31 || imm < 0 || imm > 4095) return false;
        encoding = (1u << 31) | (0b1 << 30) | (0b011 << 29) | (0b10001 << 24) |
                   ((imm & 0xFFF) << 10) | (n << 5) | 31;
        return true;
    }
    return false;
}

static bool encodeCMN(const char* args, uint32_t& encoding) {
    // CMN Xn, #imm  =>  ADDS XZR, Xn, #imm
    char rn[8];
    int imm = 0;
    if (std::sscanf(args, "%7s, #%d", rn, &imm) >= 2) {
        uint32_t n = parseRegister(rn);
        if (n > 31 || imm < 0 || imm > 4095) return false;
        encoding = (1u << 31) | (0b001 << 29) | (0b10001 << 24) |
                   ((imm & 0xFFF) << 10) | (n << 5) | 31;
        return true;
    }
    return false;
}

// --- 逻辑运算 (register) ---

static bool encodeAND(const char* args, uint32_t& encoding) {
    // AND Xd, Xn, Xm (shifted register): sf=1, 00 01010 shift N Rm imm6 Rn Rd
    char rd[8], rn[8], rm[8];
    if (std::sscanf(args, "%7s, %7s, %7s", rd, rn, rm) >= 3) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        uint32_t m = parseRegister(rm);
        if (r > 31 || n > 31 || m > 31) return false;
        // 64-bit: 1010 1010 00.. .... .... .... .... ....
        encoding = (1u << 31) | (0u << 30) | (0u << 29) | (0b01010 << 24) |
                   (0u << 22) | (0u << 21) | (m << 16) | (0u << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeORR(const char* args, uint32_t& encoding) {
    // ORR Xd, Xn, Xm: 1010 1010 00.. .... .... .... .... ....
    char rd[8], rn[8], rm[8];
    if (std::sscanf(args, "%7s, %7s, %7s", rd, rn, rm) >= 3) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        uint32_t m = parseRegister(rm);
        if (r > 31 || n > 31 || m > 31) return false;
        encoding = (1u << 31) | (0b01 << 29) | (0b01010 << 24) |
                   (m << 16) | (0u << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeEOR(const char* args, uint32_t& encoding) {
    // EOR Xd, Xn, Xm: 1100 1010 00.. .... .... .... .... ....
    char rd[8], rn[8], rm[8];
    if (std::sscanf(args, "%7s, %7s, %7s", rd, rn, rm) >= 3) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        uint32_t m = parseRegister(rm);
        if (r > 31 || n > 31 || m > 31) return false;
        encoding = (1u << 31) | (0b10 << 29) | (0b01010 << 24) |
                   (m << 16) | (0u << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeBIC(const char* args, uint32_t& encoding) {
    // BIC Xd, Xn, Xm: 1010 1010 00.. .... .... .... .... .... (N=1)
    char rd[8], rn[8], rm[8];
    if (std::sscanf(args, "%7s, %7s, %7s", rd, rn, rm) >= 3) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        uint32_t m = parseRegister(rm);
        if (r > 31 || n > 31 || m > 31) return false;
        encoding = (1u << 31) | (0b01010 << 24) | (1u << 21) |
                   (m << 16) | (0u << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeORN(const char* args, uint32_t& encoding) {
    // ORN Xd, Xn, Xm: 1010 1010 01.. .... .... .... .... .... (N=1)
    char rd[8], rn[8], rm[8];
    if (std::sscanf(args, "%7s, %7s, %7s", rd, rn, rm) >= 3) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        uint32_t m = parseRegister(rm);
        if (r > 31 || n > 31 || m > 31) return false;
        encoding = (1u << 31) | (0b01 << 29) | (0b01010 << 24) | (1u << 21) |
                   (m << 16) | (0u << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeMVN(const char* args, uint32_t& encoding) {
    // MVN Xd, Xm  =>  ORN Xd, XZR, Xm
    char rd[8], rm[8];
    if (std::sscanf(args, "%7s, %7s", rd, rm) >= 2) {
        uint32_t r = parseRegister(rd);
        uint32_t m = parseRegister(rm);
        if (r > 31 || m > 31) return false;
        encoding = (1u << 31) | (0b01 << 29) | (0b01010 << 24) | (1u << 21) |
                   (m << 16) | (0u << 10) | (31u << 5) | r;
        return true;
    }
    return false;
}

// --- 移位 (UBFM/UBFM/UBFM/EXTR aliases) ---

static bool encodeLSL(const char* args, uint32_t& encoding) {
    // LSL Xd, Xn, #shift  =>  UBFM Xd, Xn, #(-shift MOD 64), #(63-shift)
    // UBFM: sf=1, 10 11010 0 N imms Rn Rd  (0xD3400000 base)
    char rd[8], rn[8];
    int shift = 0;
    if (std::sscanf(args, "%7s, %7s, #%d", rd, rn, &shift) >= 3) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        if (r > 31 || n > 31 || shift < 0 || shift > 63) return false;
        // UBFM with imms=shift-1 (if shift>0) else 63, immr=-shift MOD 64 = (64-shift) MOD 64
        uint32_t imms = (shift == 0) ? 63 : (shift - 1);
        uint32_t immr = (64 - shift) & 0x3F;
        encoding = (1u << 31) | (0b10 << 29) | (0b11010100 << 21) | (0u << 22) |
                   (immr << 16) | (imms << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeLSR(const char* args, uint32_t& encoding) {
    // LSR Xd, Xn, #shift  =>  UBFM Xd, Xn, #shift, #63
    char rd[8], rn[8];
    int shift = 0;
    if (std::sscanf(args, "%7s, %7s, #%d", rd, rn, &shift) >= 3) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        if (r > 31 || n > 31 || shift < 0 || shift > 63) return false;
        uint32_t imms = 63;
        uint32_t immr = shift & 0x3F;
        encoding = (1u << 31) | (0b10 << 29) | (0b11010100 << 21) | (0u << 22) |
                   (immr << 16) | (imms << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeASR(const char* args, uint32_t& encoding) {
    // ASR Xd, Xn, #shift  =>  SBFM Xd, Xn, #shift, #63
    // SBFM: sf=1, 00 11010 0 N imms Rn Rd
    char rd[8], rn[8];
    int shift = 0;
    if (std::sscanf(args, "%7s, %7s, #%d", rd, rn, &shift) >= 3) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        if (r > 31 || n > 31 || shift < 0 || shift > 63) return false;
        uint32_t imms = 63;
        uint32_t immr = shift & 0x3F;
        encoding = (1u << 31) | (0b00 << 29) | (0b11010100 << 21) | (0u << 22) |
                   (immr << 16) | (imms << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeROR(const char* args, uint32_t& encoding) {
    // ROR Xd, Xn, #shift  =>  EXTR Xd, Xn, Xn, #shift
    // EXTR: sf=1, 00 100111 N0 Rm imms Rn Rd
    char rd[8], rn[8];
    int shift = 0;
    if (std::sscanf(args, "%7s, %7s, #%d", rd, rn, &shift) >= 3) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        if (r > 31 || n > 31 || shift < 0 || shift > 63) return false;
        encoding = (1u << 31) | (0b00 << 29) | (0b10011100 << 21) | (0u << 22) |
                   (n << 16) | ((shift & 0x3F) << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

// --- 扩展 (SXTB/SXTH/SXTW = SBFM aliases; UXTB/UXTH = UBFM aliases) ---

static bool encodeSXTB(const char* args, uint32_t& encoding) {
    // SXTB Xd, Wn  =>  SBFM Xd, Rn, #0, #7
    char rd[8], rn[8];
    if (std::sscanf(args, "%7s, %7s", rd, rn) >= 2) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        if (r > 31 || n > 31) return false;
        encoding = (1u << 31) | (0b00 << 29) | (0b11010100 << 21) |
                   (0u << 16) | (7u << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeSXTH(const char* args, uint32_t& encoding) {
    // SXTH Xd, Wn  =>  SBFM Xd, Rn, #0, #15
    char rd[8], rn[8];
    if (std::sscanf(args, "%7s, %7s", rd, rn) >= 2) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        if (r > 31 || n > 31) return false;
        encoding = (1u << 31) | (0b00 << 29) | (0b11010100 << 21) |
                   (0u << 16) | (15u << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeSXTW(const char* args, uint32_t& encoding) {
    // SXTW Xd, Wn  =>  SBFM Xd, Rn, #0, #31
    char rd[8], rn[8];
    if (std::sscanf(args, "%7s, %7s", rd, rn) >= 2) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        if (r > 31 || n > 31) return false;
        encoding = (1u << 31) | (0b00 << 29) | (0b11010100 << 21) |
                   (0u << 16) | (31u << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeUXTB(const char* args, uint32_t& encoding) {
    // UXTB Xd, Wn  =>  UBFM Xd, Rn, #0, #7
    char rd[8], rn[8];
    if (std::sscanf(args, "%7s, %7s", rd, rn) >= 2) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        if (r > 31 || n > 31) return false;
        encoding = (1u << 31) | (0b10 << 29) | (0b11010100 << 21) |
                   (0u << 16) | (7u << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeUXTH(const char* args, uint32_t& encoding) {
    // UXTH Xd, Wn  =>  UBFM Xd, Rn, #0, #15
    char rd[8], rn[8];
    if (std::sscanf(args, "%7s, %7s", rd, rn) >= 2) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        if (r > 31 || n > 31) return false;
        encoding = (1u << 31) | (0b10 << 29) | (0b11010100 << 21) |
                   (0u << 16) | (15u << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

// --- 移动 ---

static bool encodeMOV(const char* args, uint32_t& encoding) {
    // MOV Xd, Xm  =>  ORR Xd, XZR, Xm
    char rd[8], rm[8];
    int imm = 0;
    // MOV Xd, #imm  =>  MOVZ Xd, #imm
    if (std::sscanf(args, "%7s, #%d", rd, &imm) >= 2) {
        uint32_t r = parseRegister(rd);
        if (r > 31) return false;
        if (imm < 0 || imm > 0xFFFF) return false;
        // MOVZ: sf=1, opc=10, hw, imm16, Rd
        encoding = (1u << 31) | (0b10 << 29) | (0b100101 << 23) | (0u << 21) |
                   ((imm & 0xFFFF) << 5) | r;
        return true;
    }
    // MOV Xd, Xm  =>  ORR Xd, XZR, Xm
    if (std::sscanf(args, "%7s, %7s", rd, rm) >= 2) {
        uint32_t r = parseRegister(rd);
        uint32_t m = parseRegister(rm);
        if (r > 31 || m > 31) return false;
        encoding = (1u << 31) | (0b01 << 29) | (0b01010 << 24) |
                   (m << 16) | (0u << 10) | (31u << 5) | r;
        return true;
    }
    return false;
}

static bool encodeMOVZ(const char* args, uint32_t& encoding) {
    char rd[8];
    int imm = 0;
    int hw = 0;
    if (std::sscanf(args, "%7s, #%d, LSL #%d", rd, &imm, &hw) >= 2 ||
        std::sscanf(args, "%7s, #%d", rd, &imm) >= 2) {
        uint32_t r = parseRegister(rd);
        if (r > 31) return false;
        if (imm < 0 || imm > 0xFFFF) return false;
        encoding = (1u << 31) | (0b10 << 29) | (0b100101 << 23) |
                   ((hw & 0x3u) << 21) | ((imm & 0xFFFF) << 5) | r;
        return true;
    }
    return false;
}

static bool encodeMOVN(const char* args, uint32_t& encoding) {
    char rd[8];
    int imm = 0;
    int hw = 0;
    if (std::sscanf(args, "%7s, #%d, LSL #%d", rd, &imm, &hw) >= 2 ||
        std::sscanf(args, "%7s, #%d", rd, &imm) >= 2) {
        uint32_t r = parseRegister(rd);
        if (r > 31) return false;
        if (imm < 0 || imm > 0xFFFF) return false;
        encoding = (1u << 31) | (0b00 << 29) | (0b100101 << 23) |
                   ((hw & 0x3u) << 21) | ((imm & 0xFFFF) << 5) | r;
        return true;
    }
    return false;
}

static bool encodeMOVK(const char* args, uint32_t& encoding) {
    char rd[8];
    int imm = 0;
    int hw = 0;
    if (std::sscanf(args, "%7s, #%d, LSL #%d", rd, &imm, &hw) >= 2 ||
        std::sscanf(args, "%7s, #%d", rd, &imm) >= 2) {
        uint32_t r = parseRegister(rd);
        if (r > 31) return false;
        if (imm < 0 || imm > 0xFFFF) return false;
        encoding = (1u << 31) | (0b11 << 29) | (0b100101 << 23) |
                   ((hw & 0x3u) << 21) | ((imm & 0xFFFF) << 5) | r;
        return true;
    }
    return false;
}

// --- 地址 ---

static bool encodeADRP(const char* args, uint32_t& encoding) {
    // ADRP Xd, label
    // 简化实现：把 target 当作 PC 相对的页地址，immhi+immlo 由调用者预计算
    char rd[8];
    uint64_t target = 0;
    if (std::sscanf(args, "%7s, %lu", rd, &target) >= 2 ||
        std::sscanf(args, "%7s, 0x%lx", rd, &target) >= 2) {
        uint32_t r = parseRegister(rd);
        if (r > 31) return false;
        // ADRP: 1 immlo 10000 immhi Rd
        uint64_t page = target >> 12;
        uint32_t immlo = page & 0x3;
        uint32_t immhi = (page >> 2) & 0x7FFFF;
        encoding = (1u << 31) | (immlo << 29) | (0b10000 << 24) | (immhi << 5) | r;
        return true;
    }
    return false;
}

static bool encodeADR(const char* args, uint32_t& encoding) {
    // ADR Xd, label  (PC-relative byte address)
    char rd[8];
    uint64_t target = 0;
    if (std::sscanf(args, "%7s, %lu", rd, &target) >= 2 ||
        std::sscanf(args, "%7s, 0x%lx", rd, &target) >= 2) {
        uint32_t r = parseRegister(rd);
        if (r > 31) return false;
        // ADR: 0 immlo 10000 immhi Rd
        uint32_t immlo = target & 0x3;
        uint32_t immhi = (target >> 2) & 0x7FFFF;
        encoding = (0u << 31) | (immlo << 29) | (0b10000 << 24) | (immhi << 5) | r;
        return true;
    }
    return false;
}

// --- 分支 ---

static bool encodeBL_wrap(const char* args, uint32_t& encoding) {
    // 支持 "#0x20", "0x20", "#32", "32" 等形式（带或不带 # 前缀）
    const char* p = args;
    while (p && *p && std::isspace(*p)) p++;
    if (!p || !*p) return false;
    if (*p == '#') p++;
    if (!*p) return false;

    int base = 10;
    if (p[0] == '0' && (p[1] == 'x' || p[1] == 'X')) {
        base = 16;
        p += 2;
    }
    char* end = nullptr;
    int64_t offset = std::strtoll(p, &end, base);
    if (end == p) return false;
    encoding = encodeBL(offset);
    return true;
}

static bool encodeB_wrap(const char* args, uint32_t& encoding) {
    const char* p = args;
    while (p && *p && std::isspace(*p)) p++;
    if (!p || !*p) return false;
    if (*p == '#') p++;
    if (!*p) return false;

    int base = 10;
    if (p[0] == '0' && (p[1] == 'x' || p[1] == 'X')) {
        base = 16;
        p += 2;
    }
    char* end = nullptr;
    int64_t offset = std::strtoll(p, &end, base);
    if (end == p) return false;
    encoding = encodeB(offset);
    return true;
}

static bool encodeBR(const char* args, uint32_t& encoding) {
    // BR Xn — 1101 0110 0001 1111 0000 00nn nnn0 0000
    char rn[8];
    if (std::sscanf(args, "%7s", rn) >= 1) {
        uint32_t n = parseRegister(rn);
        if (n > 31) return false;
        encoding = 0xD61F0000u | (n << 5);
        return true;
    }
    return false;
}

static bool encodeBLR(const char* args, uint32_t& encoding) {
    // BLR Xn — 1101 0110 0011 1111 0000 00nn nnn0 0000
    char rn[8];
    if (std::sscanf(args, "%7s", rn) >= 1) {
        uint32_t n = parseRegister(rn);
        if (n > 31) return false;
        encoding = 0xD63F0000u | (n << 5);
        return true;
    }
    return false;
}

static bool encodeRET(const char* args, uint32_t& encoding) {
    // RET Xn — 1101 0110 0101 1111 0000 00nn nnn0 0000（默认 X30）
    char rn[8] = "x30";
    if (args && *args) {
        std::sscanf(args, "%7s", rn);
    }
    uint32_t n = parseRegister(rn);
    if (n > 31) return false;
    encoding = 0xD65F0000u | (n << 5);
    return true;
}

static bool encodeCBZ(const char* args, uint32_t& encoding) {
    // CBZ Xt, target — sf=1, 011010 0 imm19 Rt  (0xB4 base)
    char rt[8];
    uint64_t target = 0;
    int64_t offset = 0;
    if (std::sscanf(args, "%7s, %ld", rt, &offset) >= 2 ||
        std::sscanf(args, "%7s, 0x%lx", rt, &target) >= 2) {
        uint32_t t = parseRegister(rt);
        if (t > 31) return false;
        if (offset == 0 && target != 0) offset = static_cast<int64_t>(target);
        int64_t imm19 = offset >> 2;
        encoding = (0b011010 << 25) | (1u << 31) | ((imm19 & 0x7FFFF) << 5) | t;
        return true;
    }
    return false;
}

static bool encodeCBNZ(const char* args, uint32_t& encoding) {
    // CBNZ Xt, target — sf=1, 011010 1 imm19 Rt  (0xB5 base)
    char rt[8];
    uint64_t target = 0;
    int64_t offset = 0;
    if (std::sscanf(args, "%7s, %ld", rt, &offset) >= 2 ||
        std::sscanf(args, "%7s, 0x%lx", rt, &target) >= 2) {
        uint32_t t = parseRegister(rt);
        if (t > 31) return false;
        if (offset == 0 && target != 0) offset = static_cast<int64_t>(target);
        int64_t imm19 = offset >> 2;
        encoding = (1u << 31) | (0b0110101 << 25) | ((imm19 & 0x7FFFF) << 5) | t;
        return true;
    }
    return false;
}

static bool encodeTBZ(const char* args, uint32_t& encoding) {
    // TBZ Xt, #bit, target — b5 0 b40 imm14 Rt  (0x36 base for 32-bit; 64-bit uses bit 63 of b5)
    char rt[8];
    int bit = 0;
    int64_t offset = 0;
    if (std::sscanf(args, "%7s, #%d, %ld", rt, &bit, &offset) >= 3) {
        uint32_t t = parseRegister(rt);
        if (t > 31 || bit < 0 || bit > 63) return false;
        int64_t imm14 = offset >> 2;
        // b5 = bit >> 5, b40 = bit & 0x1F
        uint32_t b5 = (bit >> 5) & 1;
        uint32_t b40 = bit & 0x1F;
        encoding = (b5 << 31) | (0b0110110 << 25) | (b40 << 19) | ((imm14 & 0x3FFF) << 5) | t;
        return true;
    }
    return false;
}

static bool encodeTBNZ(const char* args, uint32_t& encoding) {
    char rt[8];
    int bit = 0;
    int64_t offset = 0;
    if (std::sscanf(args, "%7s, #%d, %ld", rt, &bit, &offset) >= 3) {
        uint32_t t = parseRegister(rt);
        if (t > 31 || bit < 0 || bit > 63) return false;
        int64_t imm14 = offset >> 2;
        uint32_t b5 = (bit >> 5) & 1;
        uint32_t b40 = bit & 0x1F;
        encoding = (b5 << 31) | (0b0110111 << 25) | (b40 << 19) | ((imm14 & 0x3FFF) << 5) | t;
        return true;
    }
    return false;
}

// --- 条件选择 ---

static uint32_t parseCond(const char* cond) {
    if (strcmp(cond, "eq") == 0) return 0x0;
    if (strcmp(cond, "ne") == 0) return 0x1;
    if (strcmp(cond, "cs") == 0 || strcmp(cond, "hs") == 0) return 0x2;
    if (strcmp(cond, "cc") == 0 || strcmp(cond, "lo") == 0) return 0x3;
    if (strcmp(cond, "mi") == 0) return 0x4;
    if (strcmp(cond, "pl") == 0) return 0x5;
    if (strcmp(cond, "vs") == 0) return 0x6;
    if (strcmp(cond, "vc") == 0) return 0x7;
    if (strcmp(cond, "hi") == 0) return 0x8;
    if (strcmp(cond, "ls") == 0) return 0x9;
    if (strcmp(cond, "ge") == 0) return 0xA;
    if (strcmp(cond, "lt") == 0) return 0xB;
    if (strcmp(cond, "gt") == 0) return 0xC;
    if (strcmp(cond, "le") == 0) return 0xD;
    if (strcmp(cond, "al") == 0) return 0xE;
    return 0xE; // 默认 al
}

static bool encodeCSEL(const char* args, uint32_t& encoding) {
    // CSEL Xd, Xn, Xm, cond — sf=1, 00 11010100 Rm cond 00 Rn Rd  (0x9A800000 base)
    char rd[8], rn[8], rm[8], cond[8];
    if (std::sscanf(args, "%7s, %7s, %7s, %7s", rd, rn, rm, cond) >= 4) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        uint32_t m = parseRegister(rm);
        if (r > 31 || n > 31 || m > 31) return false;
        uint32_t condCode = parseCond(cond);
        encoding = (1u << 31) | (0b0011010 << 24) | (1u << 21) |
                   (m << 16) | (condCode << 12) | (0u << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeCSINC(const char* args, uint32_t& encoding) {
    // CSINC Xd, Xn, Xm, cond — sf=1, 00 11010100 Rm cond 01 Rn Rd
    char rd[8], rn[8], rm[8], cond[8];
    if (std::sscanf(args, "%7s, %7s, %7s, %7s", rd, rn, rm, cond) >= 4) {
        uint32_t r = parseRegister(rd);
        uint32_t n = parseRegister(rn);
        uint32_t m = parseRegister(rm);
        if (r > 31 || n > 31 || m > 31) return false;
        uint32_t condCode = parseCond(cond);
        encoding = (1u << 31) | (0b0011010 << 24) | (1u << 21) |
                   (m << 16) | (condCode << 12) | (1u << 10) | (n << 5) | r;
        return true;
    }
    return false;
}

static bool encodeCSET(const char* args, uint32_t& encoding) {
    // CSET Xd, cond  =>  CSINC Xd, XZR, XZR, invert(cond)
    char rd[8], cond[8];
    if (std::sscanf(args, "%7s, %7s", rd, cond) >= 2) {
        uint32_t r = parseRegister(rd);
        if (r > 31) return false;
        uint32_t condCode = parseCond(cond) ^ 1; // invert
        encoding = (1u << 31) | (0b0011010 << 24) | (1u << 21) |
                   (31u << 16) | (condCode << 12) | (1u << 10) | (31u << 5) | r;
        return true;
    }
    return false;
}

static bool encodeCSETM(const char* args, uint32_t& encoding) {
    // CSETM Xd, cond  =>  CSETM Xd, cond  =>  CSINV Xd, XZR, XZR, invert(cond)
    char rd[8], cond[8];
    if (std::sscanf(args, "%7s, %7s", rd, cond) >= 2) {
        uint32_t r = parseRegister(rd);
        if (r > 31) return false;
        uint32_t condCode = parseCond(cond) ^ 1;
        encoding = (1u << 31) | (0b0011010 << 24) | (1u << 21) |
                   (31u << 16) | (condCode << 12) | (0u << 10) | (31u << 5) | r;
        return true;
    }
    return false;
}

// --- 系统 ---

static bool encodeNOP(const char* /*args*/, uint32_t& encoding) {
    // NOP — 1101 0101 0000 0011 0010 0000 0001 1111
    encoding = 0xD503201Fu;
    return true;
}

static bool encodeHLT(const char* args, uint32_t& encoding) {
    // HLT #imm16 — 1101 0100 0100 .... .... .... ...0 0000
    int imm = 0;
    if (std::sscanf(args, "#%d", &imm) >= 1) {
        if (imm < 0 || imm > 0xFFFF) return false;
        encoding = (0xD4u << 24) | (0b010 << 21) | ((imm & 0xFFFF) << 5) | (0u);
        return true;
    }
    return false;
}

// ========== 注册内置指令 ==========

void Arm64Encoder::registerBuiltins() {
    // 加载/存储
    registerInstruction("LDR", encodeLDR);
    registerInstruction("LDRB", encodeLDRB);
    registerInstruction("LDRH", encodeLDRH);
    registerInstruction("LDRSW", encodeLDR); // 简化（应使用 32-bit 符号扩展版本）
    registerInstruction("STR", encodeSTR);
    registerInstruction("STRB", encodeSTRB);
    registerInstruction("STRH", encodeSTRH);
    registerInstruction("LDP", encodeLDP);
    registerInstruction("STP", encodeSTP);

    // 整数运算
    registerInstruction("ADD", encodeADD);
    registerInstruction("ADDS", encodeADDS);
    registerInstruction("SUB", encodeSUB);
    registerInstruction("SUBS", encodeSUBS);
    registerInstruction("CMP", encodeCMP);
    registerInstruction("CMN", encodeCMN);
    registerInstruction("AND", encodeAND);
    registerInstruction("ORR", encodeORR);
    registerInstruction("EOR", encodeEOR);
    registerInstruction("BIC", encodeBIC);
    registerInstruction("ORN", encodeORN);
    registerInstruction("MVN", encodeMVN);

    // 移位/扩展
    registerInstruction("LSL", encodeLSL);
    registerInstruction("LSR", encodeLSR);
    registerInstruction("ASR", encodeASR);
    registerInstruction("ROR", encodeROR);
    registerInstruction("SXTB", encodeSXTB);
    registerInstruction("SXTH", encodeSXTH);
    registerInstruction("SXTW", encodeSXTW);
    registerInstruction("UXTB", encodeUXTB);
    registerInstruction("UXTH", encodeUXTH);

    // 移动
    registerInstruction("MOV", encodeMOV);
    registerInstruction("MOVZ", encodeMOVZ);
    registerInstruction("MOVN", encodeMOVN);
    registerInstruction("MOVK", encodeMOVK);

    // 地址
    registerInstruction("ADRP", encodeADRP);
    registerInstruction("ADR", encodeADR);

    // 分支
    registerInstruction("B", encodeB_wrap);
    registerInstruction("BL", encodeBL_wrap);
    registerInstruction("BLR", encodeBLR);
    registerInstruction("BR", encodeBR);
    registerInstruction("RET", encodeRET);
    registerInstruction("CBZ", encodeCBZ);
    registerInstruction("CBNZ", encodeCBNZ);
    registerInstruction("TBZ", encodeTBZ);
    registerInstruction("TBNZ", encodeTBNZ);

    // 条件选择
    registerInstruction("CSEL", encodeCSEL);
    registerInstruction("CSINC", encodeCSINC);
    registerInstruction("CSET", encodeCSET);
    registerInstruction("CSETM", encodeCSETM);

    // 系统
    registerInstruction("NOP", encodeNOP);
    registerInstruction("HLT", encodeHLT);
}

} // namespace arm64
} // namespace fler
