#include "elf_writer.h"
#include <cstring>

namespace fler {
namespace elf {

ElfWriter::ElfWriter(ElfParser& parser)
    : parser_(parser), dirty_(false) {
}

ElfWriter::~ElfWriter() = default;

bool ElfWriter::writeBytes(uint64_t offset, const uint8_t* data, size_t size) {
    if (!parser_.isValid()) return false;
    bool ok = parser_.writeBytes(offset, data, size);
    if (ok) dirty_ = true;
    return ok;
}

bool ElfWriter::flush() {
    if (!dirty_) return true;
    bool ok = parser_.flush();
    if (ok) dirty_ = false;
    return ok;
}

std::vector<uint8_t> ElfWriter::readBytes(uint64_t offset, size_t size) const {
    return parser_.readBytes(offset, size);
}

uint32_t ElfWriter::computeCRC32(const uint8_t* data, size_t size) {
    // 复用 ElfParser::computeCRC32 的逻辑
    // 为方便独立调用，在此重新实现
    static uint32_t table[256];
    static bool initialized = false;
    if (!initialized) {
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t crc = i;
            for (int j = 0; j < 8; ++j) {
                crc = (crc & 1) ? (crc >> 1) ^ 0xEDB88320 : (crc >> 1);
            }
            table[i] = crc;
        }
        initialized = true;
    }

    uint32_t crc = 0xFFFFFFFF;
    for (size_t i = 0; i < size; ++i) {
        crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    }
    return crc ^ 0xFFFFFFFF;
}

bool ElfWriter::patchInstruction(uint64_t offset, uint32_t newInstruction) {
    uint8_t bytes[4];
    // ARM64 是小端
    bytes[0] = (newInstruction >> 0) & 0xFF;
    bytes[1] = (newInstruction >> 8) & 0xFF;
    bytes[2] = (newInstruction >> 16) & 0xFF;
    bytes[3] = (newInstruction >> 24) & 0xFF;
    return writeBytes(offset, bytes, 4);
}

std::vector<uint8_t> ElfWriter::backupBytes(uint64_t offset, size_t size) const {
    return parser_.readBytes(offset, size);
}

bool ElfWriter::restoreBytes(uint64_t offset, const std::vector<uint8_t>& backup) {
    return writeBytes(offset, backup.data(), backup.size());
}

} // namespace elf
} // namespace fler
