#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include <memory>

namespace fler {
namespace elf {

// ELF 节头类型（部分常用值）
constexpr uint32_t SHT_NULL = 0;
constexpr uint32_t SHT_PROGBITS = 1;
constexpr uint32_t SHT_SYMTAB = 2;
constexpr uint32_t SHT_STRTAB = 3;
constexpr uint32_t SHT_NOBITS = 8;
constexpr uint32_t SHT_DYNSYM = 11;

// ELF 节头标志
constexpr uint64_t SHF_WRITE = 0x1;
constexpr uint64_t SHF_ALLOC = 0x2;
constexpr uint64_t SHF_EXECINSTR = 0x4;

// ELF 符号绑定
constexpr uint8_t STB_LOCAL = 0;
constexpr uint8_t STB_GLOBAL = 1;
constexpr uint8_t STB_WEAK = 2;

// ELF 符号类型
constexpr uint8_t STT_NOTYPE = 0;
constexpr uint8_t STT_OBJECT = 1;
constexpr uint8_t STT_FUNC = 2;
constexpr uint8_t STT_SECTION = 3;

struct Section {
    std::string name;
    uint32_t type = 0;
    uint64_t offset = 0;
    uint64_t size = 0;
    uint64_t address = 0;
    uint64_t flags = 0;
    uint32_t link = 0;   // sh_link：符号表的关联字符串表索引（.symtab→.strtab，.dynsym→.dynstr）
};

struct Symbol {
    std::string name;
    uint64_t address = 0;
    uint64_t size = 0;
    uint8_t type = 0;
    uint8_t binding = 0;
    uint16_t shndx = 0;
};

class ElfParser {
public:
    static ElfParser* open(const char* path);
    ~ElfParser();

    // 禁止拷贝
    ElfParser(const ElfParser&) = delete;
    ElfParser& operator=(const ElfParser&) = delete;

    // === 只读接口 ===
    std::vector<Section> getSections() const;
    std::vector<Symbol> getSymbols() const;
    std::vector<Symbol> getDynamicSymbols() const;
    std::vector<uint8_t> getSectionData(const char* name) const;
    std::vector<uint8_t> getSectionDataByIndex(uint32_t index) const;
    std::vector<uint8_t> readBytes(uint64_t offset, size_t size) const;
    bool isValid() const { return mmap_ != nullptr && fileSize_ > 0; }
    uint64_t getFileSize() const { return fileSize_; }

    // === 写入接口 ===
    bool writeBytes(uint64_t offset, const uint8_t* data, size_t size);
    bool flush();

    // 调试
    const std::vector<Section>& sectionsRef() const { return sections_; }

private:
    ElfParser();

    int fd_ = -1;
    void* mmap_ = nullptr;
    size_t fileSize_ = 0;

    // 缓存解析结果
    std::vector<Section> sections_;
    std::vector<Symbol> symbols_;          // .symtab
    std::vector<Symbol> dynSymbols_;      // .dynsym
    bool parsed_ = false;

    void parseIfNeeded() const;
    void parseElfHeader();
    void parseSections();
    void parseSymbols();
    void parseSymbolTable(int sectionIndex, std::vector<Symbol>& out);

    // ELF 头字段
    uint64_t entry_ = 0;
    uint16_t machine_ = 0;
    uint32_t version_ = 0;
    uint64_t flags_ = 0;
};

} // namespace elf
} // namespace fler
