#pragma once

#include <HalStorage.h>

#include "FontUpload.h"

namespace textplayer {

class SdFontStore {
 public:
  bool select(const FontGeneration& generation);
  bool readGlyph(uint8_t face, uint16_t id, uint8_t* scratch, size_t capacity, FontGlyphView& glyph);
  void close();

  bool beginUpload(const FontGeneration& generation, uint32_t bytes, uint32_t crc);
  bool beginChunk() { return upload_.beginChunk(); }
  bool writeChunk(const uint8_t* data, size_t length);
  bool endChunk() { return upload_.endChunk(); }
  bool finishUpload();
  void abortUpload();

  bool uploading() const { return upload_.active(); }
  uint32_t uploadedBytes() const { return upload_.committed(); }
  uint16_t pixelSize() const { return index_.pixelSize(); }

 private:
  static constexpr int SLOT_COUNT = 2;
  static constexpr const char* SLOT_PATHS[SLOT_COUNT] = {"/.crosspoint/texpod-font-0.tpf",
                                                         "/.crosspoint/texpod-font-1.tpf"};
  static constexpr const char* BACKUP_PATHS[SLOT_COUNT] = {"/.crosspoint/texpod-font-0.bak",
                                                           "/.crosspoint/texpod-font-1.bak"};
  static constexpr const char* UPLOAD_PATH = "/.crosspoint/texpod-font.part";

  bool openDirectory(HalFile& file, const char* path, FontPackIndex& index);
  bool selectPath(const char* path, const FontGeneration& generation, int slot);
  bool selectInstalled(const FontGeneration& generation);
  int uploadSlot(const FontGeneration& generation);
  bool installInto(int slot);

  HalFile font_;
  HalFile uploadFile_;
  FontPackIndex index_;
  FontUpload upload_;
  std::array<uint8_t, MAX_FONT_DIRECTORY_BYTES> directory_{};
  int preferredSlot_ = -1;
};

}  // namespace textplayer
