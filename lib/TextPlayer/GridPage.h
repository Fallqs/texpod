#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace textplayer {

constexpr int16_t GRID_SPACE = -1;
constexpr int16_t GRID_NEWLINE = -2;
constexpr int16_t GRID_EOS = -3;
constexpr int16_t GRID_ASCII_RUN = -4;

struct GridLayout {
  int32_t originX = 0;
  int32_t originY = 0;
  uint16_t columns = 0;
  uint16_t rows = 0;
  uint16_t cellWidth = 0;
  uint16_t cellHeight = 0;
  int16_t baselineOffsetX = 0;
  int16_t baselineOffsetY = 0;
  int32_t viewportWidth = 0;
  int32_t viewportHeight = 0;

  bool isValid() const {
    if (columns == 0 || rows == 0 || cellWidth == 0 || cellHeight == 0 || originX < 0 || originY < 0 ||
        viewportWidth <= 0 || viewportHeight <= 0) {
      return false;
    }
    if (!fitsWithin(0, 0, viewportWidth, viewportHeight)) {
      return false;
    }
    const int64_t firstX = static_cast<int64_t>(originX) + baselineOffsetX;
    const int64_t firstY = static_cast<int64_t>(originY) + baselineOffsetY;
    const int64_t lastX = firstX + static_cast<int64_t>(columns - 1) * cellWidth;
    const int64_t lastY = firstY + static_cast<int64_t>(rows - 1) * cellHeight;
    return firstX >= 0 && firstY >= 0 && lastX >= 0 && lastY >= 0 && firstX < viewportWidth &&
           firstY < viewportHeight && lastX < viewportWidth && lastY < viewportHeight;
  }

  bool fitsWithin(const int32_t x, const int32_t y, const int32_t width, const int32_t height) const {
    const int64_t right = originX + static_cast<int64_t>(columns) * cellWidth;
    const int64_t bottom = originY + static_cast<int64_t>(rows) * cellHeight;
    return width > 0 && height > 0 && originX >= x && originY >= y && right <= static_cast<int64_t>(x) + width &&
           bottom <= static_cast<int64_t>(y) + height;
  }

  uint64_t cellCount() const { return static_cast<uint64_t>(columns) * rows; }

  bool anchor(const uint64_t cursor, int32_t& x, int32_t& y) const {
    if (!isValid() || cursor >= cellCount()) {
      return false;
    }
    x = originX + static_cast<int32_t>(cursor % columns) * cellWidth + baselineOffsetX;
    y = originY + static_cast<int32_t>(cursor / columns) * cellHeight + baselineOffsetY;
    return true;
  }

  static bool advanceCells(const uint16_t advanceCells, const uint16_t columns, const uint16_t rows, uint64_t& cursor) {
    const uint64_t cells = static_cast<uint64_t>(columns) * rows;
    if (columns == 0 || rows == 0 || advanceCells == 0 || advanceCells > columns || cursor >= cells ||
        cursor % columns + advanceCells > columns || advanceCells > cells - cursor) {
      return false;
    }
    cursor += advanceCells;
    return true;
  }

  static bool advance(const int16_t token, const uint16_t columns, const uint16_t rows, uint64_t& cursor) {
    if (columns == 0 || rows == 0) {
      return false;
    }
    const uint64_t cells = static_cast<uint64_t>(columns) * rows;
    if (token >= 0 || token == GRID_SPACE) {
      return advanceCells(1, columns, rows, cursor);
    }
    if (token == GRID_NEWLINE) {
      const uint64_t next = (cursor / columns + 1) * columns;
      if (next > cells) {
        return false;
      }
      cursor = next;
      return true;
    }
    if (token == GRID_EOS) {
      cursor = cells;
      return true;
    }
    return false;
  }
};

template <size_t MaxTokens>
class GridPage {
  static_assert(MaxTokens > 0 && MaxTokens <= std::numeric_limits<uint16_t>::max());

 public:
  bool parse(const int16_t* tokens, const size_t tokenCount, const uint16_t columns, const uint16_t rows) {
    rawTokenCount = 0;
    glyphTokenCount = 0;
    if (tokens == nullptr || tokenCount == 0 || tokenCount > MaxTokens || columns == 0 || rows == 0) {
      return false;
    }

    uint64_t cursor = 0;
    bool sawEnd = false;
    for (size_t index = 0; index < tokenCount; ++index) {
      const int16_t token = tokens[index];
      if (sawEnd) {
        return reject();
      }
      if (token == GRID_ASCII_RUN) {
        if (index + 2 >= tokenCount) {
          return reject();
        }
        const int16_t slot = tokens[index + 1];
        const int16_t advanceCells = tokens[index + 2];
        if (slot < 0 || advanceCells <= 0 ||
            !GridLayout::advanceCells(static_cast<uint16_t>(advanceCells), columns, rows, cursor)) {
          return reject();
        }
        rawTokens[index] = token;
        rawTokens[index + 1] = slot;
        rawTokens[index + 2] = advanceCells;
        glyphTokens[glyphTokenCount++] = static_cast<uint16_t>(slot);
        index += 2;
        continue;
      }
      if (!GridLayout::advance(token, columns, rows, cursor)) {
        return reject();
      }
      rawTokens[index] = token;
      if (token >= 0) {
        glyphTokens[glyphTokenCount++] = static_cast<uint16_t>(token);
      }
      if (token == GRID_EOS) {
        sawEnd = true;
      }
    }
    if (!sawEnd) {
      return reject();
    }
    rawTokenCount = tokenCount;
    return true;
  }

  int16_t* loadBuffer() { return rawTokens.data(); }
  bool parseLoaded(const size_t tokenCount, const uint16_t columns, const uint16_t rows) {
    return parse(rawTokens.data(), tokenCount, columns, rows);
  }

  size_t size() const { return rawTokenCount; }
  size_t glyphCount() const { return glyphTokenCount; }
  const int16_t* tokens() const { return rawTokens.data(); }
  const uint16_t* glyphSlots() const { return glyphTokens.data(); }

 private:
  bool reject() {
    rawTokenCount = 0;
    glyphTokenCount = 0;
    return false;
  }

  std::array<int16_t, MaxTokens> rawTokens{};
  std::array<uint16_t, MaxTokens> glyphTokens{};
  size_t rawTokenCount = 0;
  size_t glyphTokenCount = 0;
};

}  // namespace textplayer
