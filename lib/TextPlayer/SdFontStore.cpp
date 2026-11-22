#include "SdFontStore.h"

namespace textplayer {

bool SdFontStore::openDirectory(HalFile& file, const char* path, FontPackIndex& index) {
  if (!Storage.exists(path) || !Storage.openFileForRead("TXTP", path, file) || file.isDirectory()) return false;
  if (file.read(directory_.data(), FONT_HEADER_BYTES) != static_cast<int>(FONT_HEADER_BYTES)) return false;
  const size_t bytes = FontPackIndex::directoryBytes(directory_.data(), FONT_HEADER_BYTES);
  if (bytes == 0) return false;
  const size_t faceBytes = bytes - FONT_HEADER_BYTES;
  return file.read(directory_.data() + FONT_HEADER_BYTES, faceBytes) == static_cast<int>(faceBytes) &&
         index.parse(directory_.data(), bytes, file.fileSize64());
}

bool SdFontStore::selectPath(const char* path, const FontGeneration& generation, const int slot) {
  if (!openDirectory(font_, path, index_) || index_.generation() != generation) {
    font_.close();
    return false;
  }
#if !defined(SIMULATOR) && !defined(CROSSPOINT_EMULATED)
  font_.prepareRandomReads();
#endif
  if (slot >= 0) preferredSlot_ = slot;
  return true;
}

bool SdFontStore::select(const FontGeneration& generation) {
  if (font_.isOpen() && index_.generation() == generation) return true;
  if (selectInstalled(generation)) return true;
  constexpr char prefix[] = "/.crosspoint/texpod-fonts/";
  constexpr char hex[] = "0123456789abcdef";
  char path[sizeof(prefix) - 1 + FontGeneration{}.size() * 2 + sizeof(".tpf")];
  std::memcpy(path, prefix, sizeof(prefix) - 1);
  size_t offset = sizeof(prefix) - 1;
  for (const uint8_t byte : generation) {
    path[offset++] = hex[byte >> 4];
    path[offset++] = hex[byte & 0xf];
  }
  std::memcpy(path + offset, ".tpf", sizeof(".tpf"));
  return selectPath(path, generation, -1);
}

bool SdFontStore::selectInstalled(const FontGeneration& generation) {
  font_.close();
  for (int slot = 0; slot < SLOT_COUNT; ++slot) {
    if (selectPath(SLOT_PATHS[slot], generation, slot) || selectPath(BACKUP_PATHS[slot], generation, slot)) return true;
  }
  return false;
}

bool SdFontStore::readGlyph(const uint8_t face, const uint16_t id, uint8_t* scratch, const size_t capacity,
                            FontGlyphView& glyph) {
  uint32_t offset;
  uint16_t bytes;
  if (!font_.isOpen() || !index_.locate(face, id, offset, bytes) || bytes > capacity) return false;
  const HalStorage::StorageLock lock;
  return font_.seekSet(offset) && font_.read(scratch, bytes) == bytes && parseFontGlyph(scratch, bytes, glyph);
}

void SdFontStore::close() {
  abortUpload();
  font_.close();
}

bool SdFontStore::beginUpload(const FontGeneration& generation, const uint32_t bytes, const uint32_t crc) {
  if (uploading()) return false;
  if (!upload_.begin(generation, bytes, crc)) return false;
  if (Storage.openFileForWrite("TXTP", UPLOAD_PATH, uploadFile_)) return true;
  upload_.reset();
  return false;
}

bool SdFontStore::writeChunk(const uint8_t* data, const size_t length) {
  struct Sink {
    HalFile& file;
    bool write(const uint8_t* bytes, const size_t count) { return file.write(bytes, count) == count; }
  } sink{uploadFile_};
  return upload_.write(data, length, sink);
}

int SdFontStore::uploadSlot(const FontGeneration& generation) {
  if (selectInstalled(generation)) return preferredSlot_;
  if (preferredSlot_ >= 0) return 1 - preferredSlot_;
  for (int slot = 0; slot < SLOT_COUNT; ++slot) {
    if (!Storage.exists(SLOT_PATHS[slot]) && !Storage.exists(BACKUP_PATHS[slot])) return slot;
  }
  return 1;
}

bool SdFontStore::installInto(const int slot) {
  // Retain the old slot until the fully received .part has been renamed.
  // If power is lost between renames, select() can open the backup directly.
  const HalStorage::StorageLock lock;
  const char* path = SLOT_PATHS[slot];
  const char* backup = BACKUP_PATHS[slot];
  if (Storage.exists(path)) {
    HalFile old;
    const bool oldValid = openDirectory(old, path, index_);
    if (!old.close()) return false;
    if (oldValid) {
      if ((Storage.exists(backup) && !Storage.remove(backup)) || !Storage.rename(path, backup)) return false;
    } else if (!Storage.remove(path)) {
      return false;
    }
  }
  return Storage.rename(UPLOAD_PATH, path);
}

bool SdFontStore::finishUpload() {
  if (!upload_.complete() || !uploadFile_.close()) return false;
  const FontGeneration generation = upload_.generation();
  const int slot = uploadSlot(generation);
  font_.close();
  {
    HalFile staged;
    if (!openDirectory(staged, UPLOAD_PATH, index_) || index_.generation() != generation ||
        staged.fileSize64() != upload_.committed()) {
      return false;
    }
  }
  if (!installInto(slot) || !selectPath(SLOT_PATHS[slot], generation, slot)) return false;
  upload_.reset();
  if (Storage.exists(BACKUP_PATHS[slot])) Storage.remove(BACKUP_PATHS[slot]);
  return true;
}

void SdFontStore::abortUpload() {
  if (!uploading()) return;
  uploadFile_.close();
  Storage.remove(UPLOAD_PATH);
  upload_.reset();
}

}  // namespace textplayer
