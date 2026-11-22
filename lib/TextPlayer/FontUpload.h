#pragma once

#include <algorithm>
#include <array>
#include <cstring>

#include "FontPack.h"

namespace textplayer {

class FontUpload {
 public:
  bool begin(const FontGeneration& generation, uint32_t bytes, uint32_t crc) {
    reset();
    if (generation == FontGeneration{} || bytes < FONT_HEADER_BYTES + FONT_FACE_BYTES + FONT_GLYPH_OVERHEAD ||
        bytes > MAX_FONT_PACK_BYTES) {
      return false;
    }
    generation_ = generation;
    total_ = bytes;
    expectedCrc_ = crc;
    return true;
  }

  void reset() {
    total_ = 0;
    written_ = 0;
    committed_ = 0;
    crc_ = 0xFFFFFFFFUL;
    chunkOpen_ = false;
  }

  bool beginChunk() {
    if (!active() || chunkOpen_ || written_ == total_) return false;
    offsetBytes_ = 0;
    chunkOpen_ = true;
    return true;
  }

  template <typename Sink>
  bool write(const uint8_t* data, size_t length, Sink& sink) {
    if (!chunkOpen_) return false;
    if (offsetBytes_ != offset_.size()) {
      const size_t take = std::min(length, offset_.size() - offsetBytes_);
      if (take == 0) return true;
      std::memcpy(offset_.data() + offsetBytes_, data, take);
      offsetBytes_ += take;
      length -= take;
      data += take;
      if (offsetBytes_ == offset_.size() && readLe32(offset_.data()) != committed_) return false;
    }
    if (length == 0) return true;
    if (length > total_ - written_ || length > MAX_FONT_CHUNK_BYTES - (written_ - committed_) ||
        !sink.write(data, length)) {
      return false;
    }
    written_ += length;
    crc_ = crc32Update(crc_, data, length);
    return true;
  }

  // Only call after the wire object CRC succeeds. On any failure discard the whole upload.
  bool endChunk() {
    if (!chunkOpen_ || written_ == committed_) return false;
    committed_ = written_;
    chunkOpen_ = false;
    return true;
  }

  bool complete() const {
    return active() && !chunkOpen_ && committed_ == total_ && (crc_ ^ 0xFFFFFFFFUL) == expectedCrc_;
  }
  bool active() const { return total_ != 0; }
  uint32_t committed() const { return committed_; }
  const FontGeneration& generation() const { return generation_; }

 private:
  FontGeneration generation_{};
  std::array<uint8_t, sizeof(uint32_t)> offset_{};
  size_t offsetBytes_ = 0;
  uint32_t total_ = 0;
  uint32_t written_ = 0;
  uint32_t committed_ = 0;
  uint32_t crc_ = 0xFFFFFFFFUL;
  uint32_t expectedCrc_ = 0;
  bool chunkOpen_ = false;
};

}  // namespace textplayer
