#include "elf_parser.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <cstring>
#include <algorithm>

namespace fler {
namespace elf {

// ELF 64-bit 结构体
struct Elf64_Ehdr {
    uint8_t  e_ident[16];
    uint16_t e_type;
    uint16_t e_machine;
    uint32_t e_version;
    uint64_t e_entry;
    uint64_t e_phoff;
    uint64_t e_shoff;
    uint32_t e_flags;
    uint16_t e_ehsize;
    uint16_t e_phentsize;
    uint16_t e_phnum;
    uint16_t e_shentsize;
    uint16_t e_shnum;
    uint16_t e_shstrndx;
};

struct Elf64_Shdr {
    uint32_t sh_name;
    uint32_t sh_type;
    uint64_t sh_flags;
    uint64_t sh_addr;
    uint64_t sh_offset;
    uint64_t sh_size;
    uint32_t sh_link;
    uint32_t sh_info;
    uint64_t sh_addralign;
    uint64_t sh_entsize;
};

struct Elf64_Sym {
    uint32_t st_name;
    uint8_t  st_info;
    uint8_t  st_other;
    uint16_t st_shndx;
    uint64_t st_value;
    uint64_t st_size;
};

// ELF 魔数
constexpr uint8_t ELF_MAGIC[] = {0x7f, 'E', 'L', 'F'};
constexpr uint16_t EM_AARCH64 = 183;

ElfParser::ElfParser() = default;

ElfParser::~ElfParser() {
    if (mmap_ != nullptr) {
        ::munmap(mmap_, fileSize_);
        mmap_ = nullptr;
    }
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
}

ElfParser* ElfParser::open(const char* path) {
    auto* parser = new (std::nothrow) ElfParser();
    if (!parser) return nullptr;

    parser->fd_ = ::open(path, O_RDWR);
    if (parser->fd_ < 0) {
        delete parser;
        return nullptr;
    }

    struct stat st;
    if (::fstat(parser->fd_, &st) < 0) {
        delete parser;
        return nullptr;
    }
    parser->fileSize_ = st.st_size;

    if (parser->fileSize_ < sizeof(Elf64_Ehdr)) {
        delete parser;
        return nullptr;
    }

    parser->mmap_ = ::mmap(nullptr, parser->fileSize_, PROT_READ | PROT_WRITE, MAP_SHARED, parser->fd_, 0);
    if (parser->mmap_ == MAP_FAILED) {
        parser->mmap_ = nullptr;
        delete parser;
        return nullptr;
    }

    // 验证 ELF 魔数
    auto* ehdr = reinterpret_cast<const uint8_t*>(parser->mmap_);
    if (std::memcmp(ehdr, ELF_MAGIC, 4) != 0) {
        delete parser;
        return nullptr;
    }

    // 验证是 64-bit ELF（EI_CLASS == ELFCLASS64 == 2）
    if (ehdr[4] != 2) {
        delete parser;
        return nullptr;
    }

    parser->parseElfHeader();
    parser->parseSections();
    parser->parseSymbols();

    parser->parsed_ = true;
    return parser;
}

void ElfParser::parseElfHeader() {
    auto* ehdr = static_cast<Elf64_Ehdr*>(mmap_);
    entry_ = ehdr->e_entry;
    machine_ = ehdr->e_machine;
    version_ = ehdr->e_version;
    flags_ = ehdr->e_flags;
}

void ElfParser::parseSections() {
    auto* ehdr = static_cast<Elf64_Ehdr*>(mmap_);
    if (ehdr->e_shnum == 0 || ehdr->e_shoff == 0) return;

    auto* shdrs = reinterpret_cast<Elf64_Shdr*>(
        static_cast<uint8_t*>(mmap_) + ehdr->e_shoff);

    // 获取 .shstrtab 用于解析节名
    const char* shstrtab = nullptr;
    if (ehdr->e_shstrndx < ehdr->e_shnum) {
        auto& shstrShdr = shdrs[ehdr->e_shstrndx];
        shstrtab = reinterpret_cast<const char*>(
            static_cast<uint8_t*>(mmap_) + shstrShdr.sh_offset);
    }

    sections_.reserve(ehdr->e_shnum);
    for (uint16_t i = 0; i < ehdr->e_shnum; ++i) {
        auto& shdr = shdrs[i];
        Section sec;
        sec.name = shstrtab ? std::string(shstrtab + shdr.sh_name) : std::to_string(i);
        sec.type = shdr.sh_type;
        sec.offset = shdr.sh_offset;
        sec.size = shdr.sh_size;
        sec.address = shdr.sh_addr;
        sec.flags = shdr.sh_flags;
        sec.link = shdr.sh_link;
        sections_.push_back(std::move(sec));
    }
}

void ElfParser::parseSymbols() {
    if (sections_.empty()) return;

    int symtabIdx = -1;
    int dynsymIdx = -1;
    for (int i = 0; i < (int)sections_.size(); ++i) {
        const auto& sec = sections_[i];
        if (sec.type == SHT_SYMTAB && symtabIdx < 0) symtabIdx = i;
        if (sec.type == SHT_DYNSYM && dynsymIdx < 0) dynsymIdx = i;
    }

    // 解析 .symtab（stripped so 中不存在，直接跳过）
    if (symtabIdx >= 0) {
        parseSymbolTable(symtabIdx, symbols_);
    }

    // 解析 .dynsym
    if (dynsymIdx >= 0) {
        parseSymbolTable(dynsymIdx, dynSymbols_);
    }
}

/**
 * 解析单个符号表（.symtab 或 .dynsym）。
 *
 * 符号名字符串表按 sh_link 定位（.symtab→.strtab，.dynsym→.dynstr），
 * 这是 ELF 规范指定的关联方式，比按节顺序/名称猜测可靠：
 * - 对 stripped 的 so（无 .symtab/.strtab，只有 .dynsym/.dynstr）安全，
 *   不会出现 sections_[-1] 越界
 * - 所有偏移/长度均做文件边界防御，避免越界读
 */
void ElfParser::parseSymbolTable(int sectionIndex, std::vector<Symbol>& out) {
    if (sectionIndex < 0 || sectionIndex >= (int)sections_.size()) return;
    const auto& symtabSec = sections_[sectionIndex];
    if (symtabSec.size == 0 || symtabSec.offset == 0) return;

    const char* strtab = nullptr;
    size_t strtabSize = 0;
    const int strIdx = static_cast<int>(symtabSec.link);
    if (strIdx >= 0 && strIdx < (int)sections_.size()) {
        const auto& strSec = sections_[strIdx];
        if (strSec.type == SHT_STRTAB && strSec.offset > 0 && strSec.size > 0 &&
            strSec.offset + strSec.size <= fileSize_) {
            strtab = reinterpret_cast<const char*>(
                static_cast<uint8_t*>(mmap_) + strSec.offset);
            strtabSize = strSec.size;
        }
    }

    // 防御：符号表范围不超过文件
    if (symtabSec.offset + symtabSec.size > fileSize_) return;
    size_t symCount = symtabSec.size / sizeof(Elf64_Sym);
    auto* syms = reinterpret_cast<const Elf64_Sym*>(
        static_cast<uint8_t*>(mmap_) + symtabSec.offset);

    out.reserve(symCount);
    for (size_t i = 0; i < symCount; ++i) {
        Symbol sym;
        if (strtab && syms[i].st_name < strtabSize) {
            // 限制 st_name 到字符串表内，避免越界读到垃圾
            size_t maxLen = strtabSize - syms[i].st_name;
            size_t end = 0;
            while (end < maxLen && strtab[syms[i].st_name + end] != '\0') ++end;
            sym.name.assign(strtab + syms[i].st_name, end);
        }
        sym.address = syms[i].st_value;
        sym.size = syms[i].st_size;
        sym.type = syms[i].st_info & 0xf;
        sym.binding = syms[i].st_info >> 4;
        sym.shndx = syms[i].st_shndx;
        out.push_back(std::move(sym));
    }
}

std::vector<Section> ElfParser::getSections() const {
    return sections_;
}

std::vector<Symbol> ElfParser::getSymbols() const {
    return symbols_;
}

std::vector<Symbol> ElfParser::getDynamicSymbols() const {
    return dynSymbols_;
}

std::vector<uint8_t> ElfParser::getSectionData(const char* name) const {
    for (const auto& sec : sections_) {
        if (sec.name == name) {
            return getSectionDataByIndex(&sec - sections_.data());
        }
    }
    return {};
}

std::vector<uint8_t> ElfParser::getSectionDataByIndex(uint32_t index) const {
    if (index >= sections_.size()) return {};
    const auto& sec = sections_[index];
    if (sec.size == 0 || sec.offset == 0) return {};

    auto base = static_cast<uint8_t*>(mmap_) + sec.offset;
    return std::vector<uint8_t>(base, base + sec.size);
}

uint64_t ElfParser::findSymbolAddress(const char* name) const {
    std::string target(name);
    for (const auto& sym : symbols_) {
        if (sym.name == target) return sym.address;
    }
    for (const auto& sym : dynSymbols_) {
        if (sym.name == target) return sym.address;
    }
    return 0;
}

std::vector<uint8_t> ElfParser::readBytes(uint64_t offset, size_t size) const {
    if (offset + size > fileSize_) return {};
    auto base = static_cast<uint8_t*>(mmap_) + offset;
    return std::vector<uint8_t>(base, base + size);
}

bool ElfParser::writeBytes(uint64_t offset, const uint8_t* data, size_t size) {
    if (offset + size > fileSize_) return false;
    auto base = static_cast<uint8_t*>(mmap_) + offset;
    std::memcpy(base, data, size);
    return true;
}

bool ElfParser::flush() {
    if (mmap_ == nullptr || fd_ < 0) return false;
    // msync 刷新 mmap 缓存回磁盘
    return ::msync(mmap_, fileSize_, MS_SYNC) == 0;
}

// CRC32 查表
static uint32_t crc32Table[256];
static bool crc32TableInit = false;

static void initCRC32Table() {
    if (crc32TableInit) return;
    for (uint32_t i = 0; i < 256; ++i) {
        uint32_t crc = i;
        for (int j = 0; j < 8; ++j) {
            if (crc & 1)
                crc = (crc >> 1) ^ 0xEDB88320;
            else
                crc >>= 1;
        }
        crc32Table[i] = crc;
    }
    crc32TableInit = true;
}

uint32_t ElfParser::computeCRC32(uint64_t offset, size_t size) const {
    if (offset + size > fileSize_) return 0;
    initCRC32Table();

    uint32_t crc = 0xFFFFFFFF;
    auto base = static_cast<uint8_t*>(mmap_) + offset;
    for (size_t i = 0; i < size; ++i) {
        crc = crc32Table[(crc ^ base[i]) & 0xFF] ^ (crc >> 8);
    }
    return crc ^ 0xFFFFFFFF;
}

} // namespace elf
} // namespace fler
