#include <GridPage.h>
#include <gtest/gtest.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace {

TEST(TextPlayerGrid, ParsesDocumentedExample) {
  const std::array<int16_t, 9> tokens{4, 5, textplayer::GRID_SPACE, 6, 7, textplayer::GRID_NEWLINE,
                                      8, 9, textplayer::GRID_EOS};
  textplayer::GridPage<16> page;
  ASSERT_TRUE(page.parse(tokens.data(), tokens.size(), 6, 2));
  EXPECT_EQ(page.glyphCount(), 6u);
  const std::array<uint16_t, 6> expected{4, 5, 6, 7, 8, 9};
  EXPECT_TRUE(std::equal(expected.begin(), expected.end(), page.glyphSlots()));

  uint64_t cursor = 0;
  std::vector<uint64_t> glyphCells;
  for (const int16_t token : tokens) {
    if (token >= 0) {
      glyphCells.push_back(cursor);
    }
    ASSERT_TRUE(textplayer::GridLayout::advance(token, 6, 2, cursor));
  }
  EXPECT_EQ(glyphCells, (std::vector<uint64_t>{0, 1, 3, 4, 6, 7}));
  EXPECT_EQ(cursor, 12u);
}

TEST(TextPlayerGrid, RequiresExactlyOneTerminalEnd) {
  textplayer::GridPage<8> page;
  const std::array<int16_t, 1> glyph{0};
  EXPECT_FALSE(page.parse(glyph.data(), glyph.size(), 2, 2));
  const std::array<int16_t, 3> trailing{0, textplayer::GRID_EOS, textplayer::GRID_SPACE};
  EXPECT_FALSE(page.parse(trailing.data(), trailing.size(), 2, 2));
  const std::array<int16_t, 3> twice{0, textplayer::GRID_EOS, textplayer::GRID_EOS};
  EXPECT_FALSE(page.parse(twice.data(), twice.size(), 2, 2));
}

TEST(TextPlayerGrid, RejectsUnknownTokenAndOverflow) {
  textplayer::GridPage<8> page;
  const std::array<int16_t, 2> unknown{-5, textplayer::GRID_EOS};
  EXPECT_FALSE(page.parse(unknown.data(), unknown.size(), 2, 2));
  const std::array<int16_t, 4> pastEnd{0, 0, 0, textplayer::GRID_EOS};
  EXPECT_FALSE(page.parse(pastEnd.data(), pastEnd.size(), 1, 2));
  const std::array<int16_t, 3> newlinePastEnd{0, textplayer::GRID_NEWLINE, textplayer::GRID_EOS};
  EXPECT_FALSE(page.parse(newlinePastEnd.data(), newlinePastEnd.size(), 1, 1));
}

TEST(TextPlayerGrid, ParsesAsciiRunAsOneGlyphAndMeasuredSpan) {
  const std::array<int16_t, 5> tokens{textplayer::GRID_ASCII_RUN, 7, 2, 0, textplayer::GRID_EOS};
  textplayer::GridPage<8> page;

  ASSERT_TRUE(page.parse(tokens.data(), tokens.size(), 4, 2));
  EXPECT_EQ(page.size(), tokens.size());
  EXPECT_EQ(page.glyphCount(), 2u);
  EXPECT_EQ(page.glyphSlots()[0], 7u);
  EXPECT_EQ(page.glyphSlots()[1], 0u);

  uint64_t cursor = 0;
  ASSERT_TRUE(textplayer::GridLayout::advanceCells(tokens[2], 4, 2, cursor));
  EXPECT_EQ(cursor, 2u);
  ASSERT_TRUE(textplayer::GridLayout::advance(tokens[3], 4, 2, cursor));
  EXPECT_EQ(cursor, 3u);
}

TEST(TextPlayerGrid, RejectsMalformedAsciiRuns) {
  textplayer::GridPage<8> page;
  const std::array<int16_t, 2> truncated{textplayer::GRID_ASCII_RUN, textplayer::GRID_EOS};
  EXPECT_FALSE(page.parse(truncated.data(), truncated.size(), 4, 2));
  const std::array<int16_t, 3> missingEnd{textplayer::GRID_ASCII_RUN, 7, 1};
  EXPECT_FALSE(page.parse(missingEnd.data(), missingEnd.size(), 4, 2));
  const std::array<int16_t, 4> invalidSlot{textplayer::GRID_ASCII_RUN, -1, 1, textplayer::GRID_EOS};
  EXPECT_FALSE(page.parse(invalidSlot.data(), invalidSlot.size(), 4, 2));
  const std::array<int16_t, 4> zeroSpan{textplayer::GRID_ASCII_RUN, 7, 0, textplayer::GRID_EOS};
  EXPECT_FALSE(page.parse(zeroSpan.data(), zeroSpan.size(), 4, 2));
  const std::array<int16_t, 4> wideSpan{textplayer::GRID_ASCII_RUN, 7, 2, textplayer::GRID_EOS};
  EXPECT_FALSE(page.parse(wideSpan.data(), wideSpan.size(), 1, 2));
  const std::array<int16_t, 5> crossesRow{0, textplayer::GRID_ASCII_RUN, 7, 2, textplayer::GRID_EOS};
  EXPECT_FALSE(page.parse(crossesRow.data(), crossesRow.size(), 2, 2));
}

TEST(TextPlayerGrid, AllowsNaturalWrapShortPageAndExplicitBlankLine) {
  textplayer::GridPage<16> page;
  const std::array<int16_t, 4> natural{0, 1, 2, textplayer::GRID_EOS};
  EXPECT_TRUE(page.parse(natural.data(), natural.size(), 2, 2));
  const std::array<int16_t, 2> shortPage{0, textplayer::GRID_EOS};
  EXPECT_TRUE(page.parse(shortPage.data(), shortPage.size(), 4, 4));
  const std::array<int16_t, 4> blankLine{0, textplayer::GRID_NEWLINE, 1, textplayer::GRID_EOS};
  ASSERT_TRUE(page.parse(blankLine.data(), blankLine.size(), 1, 3));
}

TEST(TextPlayerGrid, ValidatesTokensInPlace) {
  textplayer::GridPage<4> page;
  page.loadBuffer()[0] = 7;
  page.loadBuffer()[1] = textplayer::GRID_EOS;
  ASSERT_TRUE(page.parseLoaded(2, 2, 2));
  EXPECT_EQ(page.tokens()[0], 7);
  EXPECT_EQ(page.glyphSlots()[0], 7);
  EXPECT_FALSE(page.parseLoaded(5, 2, 2));
}

TEST(TextPlayerGrid, UsesRuntimeViewportAndSafeBaselineAnchors) {
  textplayer::GridLayout layout;
  layout.originX = 10;
  layout.originY = 20;
  layout.columns = 3;
  layout.rows = 2;
  layout.cellWidth = 30;
  layout.cellHeight = 40;
  layout.baselineOffsetX = 4;
  layout.baselineOffsetY = 28;
  layout.viewportWidth = 480;
  layout.viewportHeight = 800;
  ASSERT_TRUE(layout.isValid());
  EXPECT_TRUE(layout.fitsWithin(10, 20, 90, 80));
  EXPECT_FALSE(layout.fitsWithin(11, 20, 90, 80));
  EXPECT_FALSE(layout.fitsWithin(10, 20, 90, 79));

  int32_t x = 0;
  int32_t y = 0;
  ASSERT_TRUE(layout.anchor(4, x, y));
  EXPECT_EQ(x, 44);
  EXPECT_EQ(y, 88);
  EXPECT_FALSE(layout.anchor(6, x, y));

  layout.originX = 400;
  EXPECT_FALSE(layout.isValid());
  layout.originX = 10;
  layout.baselineOffsetX = -20;
  EXPECT_FALSE(layout.isValid());
}

}  // namespace
