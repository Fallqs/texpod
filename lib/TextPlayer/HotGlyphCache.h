#pragma once

#include <array>
#include <cstdint>
#include <cstring>

#include "FontPack.h"

namespace textplayer {

// The activity lends its existing glyph pool; only this index is new RAM.
template <size_t Capacity>
class HotGlyphCache {
 public:
  static_assert(Capacity > 0);

  void reset(uint8_t* pool = nullptr, uint16_t poolBytes = 0) {
    pool_ = pool;
    poolBytes_ = poolBytes;
    occupied_ = 0;
    used_ = 0;
  }

  bool lookup(uint8_t face, uint16_t id, FontGlyphView& glyph) {
    for (size_t i = 0; i < occupied_; ++i) {
      const Entry hit = entries_[i];
      if (hit.face != face || hit.id != id) continue;
      glyph = viewFontGlyph(pool_ + hit.offset);
      for (size_t j = i + 1; j < occupied_; ++j) entries_[j - 1] = entries_[j];
      entries_[occupied_ - 1] = hit;
      return true;
    }
    return false;
  }

  // Insert only a validated record, without its checksum or on-disk padding.
  // Caller has already missed lookup() and consumes the scratch view first.
  void insert(uint8_t face, uint16_t id, const uint8_t* record, const FontGlyphView& glyph) {
    const uint16_t bytes = FONT_GLYPH_METRICS_BYTES + glyph.stride * glyph.height;
    if (pool_ == nullptr || bytes > poolBytes_) return;
    while (occupied_ == Capacity || bytes > poolBytes_ - used_) evict();
    std::memcpy(pool_ + used_, record, bytes);
    entries_[occupied_++] = {id, used_, bytes, face};
    used_ += bytes;
  }

  size_t size() const { return occupied_; }

 private:
  struct Entry {
    uint16_t id;
    uint16_t offset;
    uint16_t bytes;
    uint8_t face;
  };

  void evict() {
    const Entry oldest = entries_[0];
    const uint16_t end = oldest.offset + oldest.bytes;
    std::memmove(pool_ + oldest.offset, pool_ + end, used_ - end);
    used_ -= oldest.bytes;
    for (size_t i = 1; i < occupied_; ++i) {
      Entry entry = entries_[i];
      if (entry.offset > oldest.offset) entry.offset -= oldest.bytes;
      entries_[i - 1] = entry;
    }
    --occupied_;
  }

  std::array<Entry, Capacity> entries_{};
  uint8_t* pool_ = nullptr;
  size_t occupied_ = 0;
  uint16_t poolBytes_ = 0;
  uint16_t used_ = 0;
};

}  // namespace textplayer
