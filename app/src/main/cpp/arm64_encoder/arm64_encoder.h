#pragma once
#include <cstdint>
#include <string>
#include <functional>
#include <unordered_map>
#include <vector>

namespace fler {
namespace arm64 {

/**
 * ARM64 指令编码器。
 *
 * 采用注册式设计：每个指令由一个编码函数实现，
 * 通过寄存器在运行时查找。新增指令只需注册即可，无需修改框架。
 *
 * 使用方式：
 * ```
 * auto& enc = Arm64Encoder::instance();
 * enc.registerInstruction("ADD", encodeADD);
 * uint32_t encoding;
 * enc.encode("ADD", "x0, x1, #4", encoding);
 * ```
 */
class Arm64Encoder {
public:
    using EncoderFn = std::function<bool(const char* args, uint32_t& encoding)>;

    static Arm64Encoder& instance();

    /**
     * 注册指令编码器。
     * @param name 指令名称，如 "ADD", "BL", "MOV"
     * @param fn 编码函数
     * @return 是否注册成功
     */
    bool registerInstruction(const char* name, EncoderFn fn);

    /**
     * 编码指令。
     * @param name 指令名称
     * @param args 操作数字符串，如 "x0, x1, #4" 或 "0x1234"
     * @param encoding 输出的 4 字节机器码
     * @return 是否编码成功
     */
    bool encode(const char* name, const char* args, uint32_t& encoding);

    /**
     * 列出所有已注册指令。
     */
    std::vector<std::string> listInstructions() const;

private:
    Arm64Encoder();
    ~Arm64Encoder() = default;
    Arm64Encoder(const Arm64Encoder&) = delete;
    Arm64Encoder& operator=(const Arm64Encoder&) = delete;

    std::unordered_map<std::string, EncoderFn> registry_;

    void registerBuiltins();
};

// ========== 辅助函数 ==========

/**
 * 解析寄存器名（x0-x30, w0-w30, sp, wzr, xzr）。
 * 返回寄存器编号 (0-30)，-1 表示解析失败。
 */
int parseRegister(const char* reg);

/**
 * 解析立即数（支持 #42, 0x2A, 42 等格式）。
 */
bool parseImmediate(const char* str, int64_t& value);

/**
 * 解析 BL/B 指令的偏移。
 * args 格式: "0x1234" 或 "label"（PC 相对偏移由调用者提供）。
 */
bool parseBranchTarget(const char* args, uint64_t& target);

// ========== 编码工具 ==========

/**
 * 编码 ADD (立即数): ADD Xd, Xn, #imm12
 * 格式: sf=1, op=0, S=0, 1 0 0 0 1 0 | imm12 | Rn | Rd
 */
uint32_t encodeADDImmediate(uint32_t rd, uint32_t rn, uint32_t imm12);

/**
 * 编码 BL (带链接): BL target
 * 格式: 100101 | imm26
 * imm26 = (target - pc) >> 2
 */
uint32_t encodeBL(int64_t offset);

/**
 * 编码 B (无条件跳转): B target
 * 格式: 000101 | imm26
 */
uint32_t encodeB(int64_t offset);

} // namespace arm64
} // namespace fler
