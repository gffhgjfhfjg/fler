#pragma once
#include "elf_parser.h"
#include <vector>
#include <cstdint>
#include <string>

namespace fler {
namespace elf {

/**
 * ELF 写入辅助。
 *
 * 与 ElfParser 配合使用：
 * 1. 通过 ElfParser 打开并解析文件
 * 2. 通过 ElfWriter 修改指定偏移的字节
 * 3. flush() 写回磁盘
 *
 * 使用 mmap + pwrite，支持就地修改。
 */
class ElfWriter {
public:
    explicit ElfWriter(ElfParser& parser);
    ~ElfWriter();

    /**
     * 写入字节到指定文件偏移。
     * @param offset 文件偏移
     * @param data 数据指针
     * @param size 数据大小
     * @return 是否成功
     */
    bool writeBytes(uint64_t offset, const uint8_t* data, size_t size);

    /**
     * 将 mmap 缓存刷回磁盘。
     */
    bool flush();

    /**
     * 从指定偏移读取字节（走 mmap 缓存）。
     */
    std::vector<uint8_t> readBytes(uint64_t offset, size_t size) const;

    /**
     * 计算 CRC32 校验（用于补丁前校验）。
     */
    static uint32_t computeCRC32(const uint8_t* data, size_t size);

    /**
     * 便捷方法：修改指定偏移的 4 字节（ARM64 指令补丁常用）。
     */
    bool patchInstruction(uint64_t offset, uint32_t newInstruction);

    /**
     * 备份指定范围的原始字节。
     */
    std::vector<uint8_t> backupBytes(uint64_t offset, size_t size) const;

    /**
     * 从备份恢复字节。
     */
    bool restoreBytes(uint64_t offset, const std::vector<uint8_t>& backup);

private:
    ElfParser& parser_;
    bool dirty_ = false;
};

} // namespace elf
} // namespace fler
