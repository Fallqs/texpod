#pragma once

#include <algorithm>
#include <array>
#include <cstring>

#include "GridPage.h"
#include "WireProtocol.h"

namespace textplayer {

constexpr uint8_t GLYPH_POSITIONED = 0x10;
constexpr uint8_t GLYPH_ADVANCE = 0x20;
constexpr uint8_t GLYPH_NEWLINE = 0x21;
constexpr size_t GLYPH_PAGE_HEADER_BYTES = 10;
constexpr size_t MAX_GLYPH_COMMAND_BYTES = 7;

class GlyphPageStream {
 public:
  void reset(const GridLayout& layout, const uint16_t commandLimit) {
    layout_ = layout;
    limit_ = commandLimit;
    cursor_ = 0;
    remaining_ = 0;
    used_ = 0;
    need_ = GLYPH_PAGE_HEADER_BYTES;
    state_ = layout.isValid() ? State::Header : State::Invalid;
  }

  template <typename Target>
  bool write(const uint8_t* data, size_t length, Target& target) {
    while (length != 0) {
      switch (state_) {
        case State::Header:
          break;
        case State::Commands:
          if (used_ == 0) {
            need_ = commandBytes(*data);
            if (remaining_ == 0 || need_ == 0) return reject();
          }
          break;
        case State::Invalid:
          return false;
      }
      const size_t take = std::min<size_t>(need_ - used_, length);
      std::memcpy(buffer_.data() + used_, data, take);
      used_ += take;
      data += take;
      length -= take;
      if (used_ != need_) continue;
      used_ = 0;
      if (state_ == State::Header) {
        remaining_ = readLe16(buffer_.data() + 8);
        if (remaining_ > limit_ || !target.begin(readLe32(buffer_.data()), readLe32(buffer_.data() + 4))) {
          return reject();
        }
        state_ = State::Commands;
        continue;
      }
      if (!execute(target)) return reject();
      --remaining_;
    }
    return state_ != State::Invalid;
  }

  // Must also receive AssemblyStatus::Complete before refreshing the display.
  bool complete() const { return state_ == State::Commands && remaining_ == 0 && used_ == 0; }

 private:
  enum class State : uint8_t { Header, Commands, Invalid };

  static uint8_t commandBytes(const uint8_t opcode) {
    if (opcode < GLYPH_POSITIONED || opcode == GLYPH_ADVANCE) return 3;
    if (opcode < GLYPH_ADVANCE) return MAX_GLYPH_COMMAND_BYTES;
    return opcode == GLYPH_NEWLINE ? 1 : 0;
  }

  template <typename Target>
  bool execute(Target& target) {
    const uint8_t opcode = buffer_[0];
    switch (opcode) {
      case GLYPH_ADVANCE:
        return advance(readLe16(buffer_.data() + 1));
      case GLYPH_NEWLINE:
        return GridLayout::advance(GRID_NEWLINE, layout_.columns, layout_.rows, cursor_);
      default:
        break;
    }
    int32_t x;
    int32_t y;
    if (!layout_.anchor(cursor_, x, y)) return false;
    if (opcode >= GLYPH_POSITIONED) {
      const auto dx = static_cast<int16_t>(readLe16(buffer_.data() + 3));
      const auto dy = static_cast<int16_t>(readLe16(buffer_.data() + 5));
      const int64_t px = static_cast<int64_t>(x) + dx;
      const int64_t py = static_cast<int64_t>(y) + dy;
      if (px > std::numeric_limits<int32_t>::max() || py > std::numeric_limits<int32_t>::max()) return false;
      return target.draw(opcode & 0x0f, readLe16(buffer_.data() + 1), static_cast<int32_t>(px),
                         static_cast<int32_t>(py));
    }
    return advance(1) && target.draw(opcode, readLe16(buffer_.data() + 1), x, y);
  }

  bool advance(const uint16_t cells) { return GridLayout::advanceCells(cells, layout_.columns, layout_.rows, cursor_); }

  bool reject() {
    state_ = State::Invalid;
    return false;
  }

  GridLayout layout_{};
  uint64_t cursor_ = 0;
  std::array<uint8_t, GLYPH_PAGE_HEADER_BYTES> buffer_{};
  uint16_t remaining_ = 0;
  uint16_t limit_ = 0;
  uint8_t used_ = 0;
  uint8_t need_ = 0;
  State state_ = State::Invalid;
};

}  // namespace textplayer
