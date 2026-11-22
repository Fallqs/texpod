#pragma once

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <map>
#include <string>
#include <vector>

class HalFile {
 public:
  bool isOpen() const { return bytes_ != nullptr; }
  bool isDirectory() const { return false; }
  uint64_t fileSize64() const { return bytes_->size(); }
  bool prepareRandomReads();
  bool seekSet(size_t pos) {
    if (pos > bytes_->size()) return false;
    position_ = pos;
    return true;
  }
  int read(void* destination, size_t length) {
    length = std::min(length, bytes_->size() - position_);
    if (length != 0) std::memcpy(destination, bytes_->data() + position_, length);
    position_ += length;
    return length;
  }
  size_t write(const uint8_t* source, size_t length) {
    if (!writeable_) return 0;
    if (position_ + length > bytes_->size()) bytes_->resize(position_ + length);
    std::memcpy(bytes_->data() + position_, source, length);
    position_ += length;
    return length;
  }
  bool close() {
    bytes_ = nullptr;
    return true;
  }

 private:
  friend class HalStorage;
  void open(std::vector<uint8_t>* bytes, bool writeable) {
    bytes_ = bytes;
    writeable_ = writeable;
    position_ = 0;
  }
  std::vector<uint8_t>* bytes_ = nullptr;
  bool writeable_ = false;
  size_t position_ = 0;
};

class HalStorage {
 public:
  // Fake implements the production HAL's non-overwriting FAT rename semantics.
  struct StorageLock {
    StorageLock() {}  // NOLINT: side-effectful on firmware, deliberately inert in tests
  };
  std::map<std::string, std::vector<uint8_t>> files;
  std::string failRenameFrom;

  size_t randomReadPreparations = 0;
  bool contiguousFile = true;

  static HalStorage& getInstance() {
    static HalStorage storage;
    return storage;
  }
  bool exists(const char* path) const { return files.count(path) != 0; }
  bool remove(const char* path) { return files.erase(path) != 0; }
  bool rename(const char* from, const char* to) {
    if (failRenameFrom == from || !exists(from) || exists(to)) return false;
    auto file = files.extract(from);
    file.key() = to;
    files.insert(std::move(file));
    return true;
  }
  bool openFileForRead(const char*, const char* path, HalFile& file) {
    const auto existing = files.find(path);
    file.open(existing == files.end() ? nullptr : &existing->second, false);
    return file.isOpen();
  }
  bool openFileForWrite(const char*, const char* path, HalFile& file) {
    auto& bytes = files[path];
    bytes.clear();
    file.open(&bytes, true);
    return true;
  }
};

inline bool HalFile::prepareRandomReads() {
  auto& storage = HalStorage::getInstance();
  ++storage.randomReadPreparations;
  return storage.contiguousFile;
}

#define Storage HalStorage::getInstance()
