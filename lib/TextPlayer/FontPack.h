#pragma once

#include <array>
#include <cstdint>
#include <cstring>

#include "WireProtocol.h"

namespace textplayer {

using FontGeneration = std::array<uint8_t, 16>;
constexpr size_t FONT_HEADER_BYTES = 32;
constexpr size_t FONT_FACE_BYTES = 8;
constexpr size_t MAX_FONT_FACES = 16;
constexpr size_t MAX_FONT_DIRECTORY_BYTES = FONT_HEADER_BYTES + MAX_FONT_FACES * FONT_FACE_BYTES;
constexpr uint32_t MAX_FONT_PACK_BYTES = 64 * 1024 * 1024;
constexpr uint32_t MAX_FACE_GLYPHS = 65'536;
constexpr size_t FONT_GLYPH_METRICS_BYTES = 8;
constexpr size_t FONT_GLYPH_OVERHEAD = FONT_GLYPH_METRICS_BYTES + sizeof(uint32_t);
constexpr size_t MAX_FONT_INK_BYTES = 1024;
constexpr size_t MAX_FONT_RECORD_BYTES = MAX_FONT_INK_BYTES + FONT_GLYPH_OVERHEAD;

struct FontGlyphView {
  uint16_t width;
  uint16_t height;
  int16_t left;
  int16_t top;
  uint16_t stride;
  const uint8_t* ink;
};

inline FontGlyphView viewFontGlyph(const uint8_t* record) {
  const uint16_t width = readLe16(record);
  return {width,
          readLe16(record + 2),
          static_cast<int16_t>(readLe16(record + 4)),
          static_cast<int16_t>(readLe16(record + 6)),
          static_cast<uint16_t>((width + 7u) / 8u),
          record + FONT_GLYPH_METRICS_BYTES};
}

inline bool parseFontGlyph(const uint8_t* record, const size_t bytes, FontGlyphView& glyph) {
  if (bytes < FONT_GLYPH_OVERHEAD || bytes > MAX_FONT_RECORD_BYTES) return false;
  const auto candidate = viewFontGlyph(record);
  const uint32_t inkBytes = static_cast<uint32_t>(candidate.stride) * candidate.height;
  if (inkBytes > MAX_FONT_INK_BYTES || inkBytes > bytes - FONT_GLYPH_OVERHEAD) return false;
  if ((candidate.width == 0 || candidate.height == 0) &&
      (candidate.width != 0 || candidate.height != 0 || candidate.left != 0 || candidate.top != 0)) {
    return false;
  }
  const size_t checkedBytes = FONT_GLYPH_METRICS_BYTES + inkBytes;
  if (readLe32(record + checkedBytes) != crc32(record, checkedBytes)) return false;
  glyph = candidate;
  return true;
}

class FontPackIndex {
 public:
  static size_t directoryBytes(const uint8_t* header, const size_t bytes) {
    if (bytes < FONT_HEADER_BYTES || std::memcmp(header, "TPF1", 4) != 0) return 0;
    const uint16_t count = readLe16(header + 22);
    return count > 0 && count <= MAX_FONT_FACES ? FONT_HEADER_BYTES + count * FONT_FACE_BYTES : 0;
  }

  bool parse(const uint8_t* directory, const size_t bytes, const uint64_t fileBytes) {
    faceCount_ = 0;
    const size_t expected = directoryBytes(directory, bytes);
    if (expected == 0 || bytes != expected || fileBytes > MAX_FONT_PACK_BYTES ||
        fileBytes != readLe32(directory + 24)) {
      return false;
    }
    uint32_t crc = crc32Update(0xFFFFFFFFUL, directory, 28);
    crc = crc32Update(crc, directory + FONT_HEADER_BYTES, bytes - FONT_HEADER_BYTES) ^ 0xFFFFFFFFUL;
    if (crc != readLe32(directory + 28)) return false;
    std::memcpy(generation_.data(), directory + 4, generation_.size());
    pixelSize_ = readLe16(directory + 20);
    if (generation_ == FontGeneration{} || pixelSize_ == 0) return false;

    const uint16_t count = readLe16(directory + 22);
    uint32_t offset = bytes;
    for (uint16_t face = 0; face < count; ++face) {
      const uint8_t* entry = directory + FONT_HEADER_BYTES + face * FONT_FACE_BYTES;
      const uint32_t glyphs = readLe32(entry);
      const uint16_t stride = readLe16(entry + 4);
      if (glyphs == 0 || glyphs > MAX_FACE_GLYPHS || stride < FONT_GLYPH_OVERHEAD || stride > MAX_FONT_RECORD_BYTES ||
          readLe16(entry + 6) != 0) {
        return false;
      }
      const uint32_t faceBytes = glyphs * stride;
      if (offset > fileBytes || faceBytes > fileBytes - offset) return false;
      faces_[face] = {offset, glyphs, stride};
      offset += faceBytes;
    }
    if (offset != fileBytes) return false;
    faceCount_ = count;
    return true;
  }

  bool locate(const uint8_t face, const uint16_t glyph, uint32_t& offset, uint16_t& bytes) const {
    if (face >= faceCount_ || glyph >= faces_[face].glyphs) return false;
    const auto& table = faces_[face];
    bytes = table.stride;
    offset = table.offset + static_cast<uint32_t>(glyph) * table.stride;
    return true;
  }

  const FontGeneration& generation() const { return generation_; }
  uint16_t pixelSize() const { return pixelSize_; }

 private:
  struct Face {
    uint32_t offset;
    uint32_t glyphs;
    uint16_t stride;
  };

  std::array<Face, MAX_FONT_FACES> faces_{};
  FontGeneration generation_{};
  uint16_t faceCount_ = 0;
  uint16_t pixelSize_ = 0;
};

}  // namespace textplayer
