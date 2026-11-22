#include <FontPack.h>
#include <GlyphPageStream.h>
#include <gtest/gtest.h>

#include <array>
#include <tuple>
#include <vector>

namespace {

using namespace textplayer;

const GridLayout layout{8, 16, 4, 2, 20, 30, 2, 22, 100, 100};
// Also encoded by GlyphPageStreamTest.java.
constexpr std::array<uint8_t, 27> page{7,    0,    0,
                                       0,    4,    3,
                                       2,    1,    5,
                                       0,    1,    0x34,
                                       0x12, 0x12, 0x78,
                                       0x56, 0xfd, 0xff,
                                       5,    0,    GLYPH_ADVANCE,
                                       2,    0,    GLYPH_NEWLINE,
                                       0,    0xff, 0xff};

struct Target {
  std::vector<std::tuple<uint8_t, uint16_t, int32_t, int32_t>> draws;
  GlyphPageStream stream;
  bool found = true;

  bool write(const uint8_t* data, size_t size) { return stream.write(data, size, *this); }
  bool begin(uint32_t request, uint32_t id) { return request == 7 && id == 0x01020304; }
  bool draw(uint8_t face, uint16_t glyph, int32_t x, int32_t y) {
    draws.emplace_back(face, glyph, x, y);
    return found;
  }
};

AssemblyStatus assemble(Target& target, bool corruptCrc) {
  ObjectAssembler assembler;
  const size_t payload = gattPayloadLimit(23);
  std::array<uint8_t, START_METADATA_SIZE> meta{};
  writeLe16(meta.data(), page.size());
  writeLe32(meta.data() + 2, crc32(page.data(), page.size()) ^ (corruptCrc ? 1 : 0));
  FrameView frame{MessageType::PageGlyphs, FRAME_START, 1, 2, 0, meta.data(), meta.size()};
  EXPECT_EQ(assembler.receive(frame, target).status, AssemblyStatus::NeedMore);
  for (size_t offset = 0; offset < page.size(); offset += payload) {
    const size_t size = std::min(payload, page.size() - offset);
    frame.offset = offset;
    frame.payload = page.data() + offset;
    frame.payloadLength = size;
    frame.flags = offset + size == page.size() ? FRAME_END : 0;
    const auto result = assembler.receive(frame, target);
    if (result.status != AssemblyStatus::NeedMore) return result.status;
  }
  return AssemblyStatus::NeedMore;
}

TEST(TextPlayerSdGlyphs, DecodesAtMinimumMtu) {
  Target target;
  target.stream.reset(layout, 5);
  ASSERT_EQ(assemble(target, false), AssemblyStatus::Complete);
  ASSERT_TRUE(target.stream.complete());
  ASSERT_EQ(target.draws.size(), 3u);
  EXPECT_EQ(target.draws[0], std::make_tuple(1, 0x1234, 10, 38));
  EXPECT_EQ(target.draws[1], std::make_tuple(2, 0x5678, 27, 43));
  EXPECT_EQ(target.draws[2], std::make_tuple(0, 0xffff, 10, 68));
}

TEST(TextPlayerSdGlyphs, RenderedCommandsAreNotProofOfIntegrity) {
  Target target;
  target.stream.reset(layout, 5);
  EXPECT_EQ(assemble(target, true), AssemblyStatus::Error);
  EXPECT_TRUE(target.stream.complete());
  EXPECT_EQ(target.draws.size(), 3u);
}

TEST(TextPlayerSdGlyphs, RejectsMalformedIncompleteAndMissingGlyphs) {
  Target target;
  target.stream.reset(layout, 4);
  EXPECT_FALSE(target.write(page.data(), page.size()));
  target.stream.reset(layout, 5);
  ASSERT_TRUE(target.write(page.data(), page.size() - 1));
  EXPECT_FALSE(target.stream.complete());
  target.stream.reset(layout, 5);
  auto malformed = page;
  malformed[21] = 4;
  EXPECT_FALSE(target.write(malformed.data(), malformed.size()));
  target.stream.reset(layout, 5);
  malformed = page;
  malformed[10] = 0xff;
  EXPECT_FALSE(target.write(malformed.data(), malformed.size()));
  target.stream.reset(layout, 5);
  EXPECT_TRUE(target.write(page.data(), page.size()));
  EXPECT_FALSE(target.write(page.data(), 1));
  target.stream.reset(layout, 5);
  target.found = false;
  EXPECT_FALSE(target.write(page.data(), page.size()));
  EXPECT_FALSE(target.stream.complete());
}

using Directory = std::array<uint8_t, FONT_HEADER_BYTES + 2 * FONT_FACE_BYTES>;

void updateDirectoryCrc(Directory& directory) {
  uint32_t crc = crc32Update(0xFFFFFFFFUL, directory.data(), 28);
  crc = crc32Update(crc, directory.data() + FONT_HEADER_BYTES, directory.size() - FONT_HEADER_BYTES);
  writeLe32(directory.data() + 28, crc ^ 0xFFFFFFFFUL);
}

TEST(TextPlayerSdGlyphs, DirectPackLookupValidatesDirectoryAndFileExtents) {
  Directory directory{};
  std::memcpy(directory.data(), "TPF1", 4);
  directory[4] = 0x42;
  writeLe16(directory.data() + 20, 30);
  writeLe16(directory.data() + 22, 2);
  constexpr uint32_t length = 48 + 3 * 32 + 65'536 * 24;
  writeLe32(directory.data() + 24, length);
  writeLe32(directory.data() + 32, 3);
  writeLe16(directory.data() + 36, 32);
  writeLe32(directory.data() + 40, 65'536);
  writeLe16(directory.data() + 44, 24);
  updateDirectoryCrc(directory);

  FontPackIndex index;
  uint32_t offset;
  uint16_t bytes;
  ASSERT_TRUE(index.parse(directory.data(), directory.size(), length));
  EXPECT_EQ(index.generation()[0], 0x42);
  EXPECT_EQ(index.pixelSize(), 30);
  EXPECT_TRUE(index.locate(0, 2, offset, bytes));
  EXPECT_EQ(offset, 112u);
  EXPECT_EQ(bytes, 32);
  EXPECT_TRUE(index.locate(1, 65535, offset, bytes));
  EXPECT_EQ(offset + bytes, length);
  EXPECT_FALSE(index.locate(0, 3, offset, bytes));
  EXPECT_FALSE(index.locate(2, 0, offset, bytes));
  EXPECT_FALSE(index.parse(directory.data(), directory.size(), length - 1));
  EXPECT_FALSE(index.locate(1, 0, offset, bytes));
  directory[4] ^= 1;
  EXPECT_FALSE(index.parse(directory.data(), directory.size(), length));
  writeLe16(directory.data() + 36, 0xffff);
  updateDirectoryCrc(directory);
  EXPECT_FALSE(index.parse(directory.data(), directory.size(), length));
}

TEST(TextPlayerSdGlyphs, ChecksRecordIntegrityAndPackedDimensions) {
  std::array<uint8_t, 24> record{};
  writeLe16(record.data(), 9);
  writeLe16(record.data() + 2, 2);
  writeLe16(record.data() + 4, static_cast<uint16_t>(-3));
  record[8] = 0x84;
  writeLe32(record.data() + 12, crc32(record.data(), 12));
  FontGlyphView glyph{};
  ASSERT_TRUE(parseFontGlyph(record.data(), record.size(), glyph));
  EXPECT_EQ(glyph.stride, 2);
  EXPECT_EQ(glyph.left, -3);
  EXPECT_EQ(glyph.ink[0], 0x84);
  EXPECT_FALSE(parseFontGlyph(record.data(), 15, glyph));
  record[8] ^= 1;
  EXPECT_FALSE(parseFontGlyph(record.data(), record.size(), glyph));
  writeLe16(record.data(), 0xffff);
  writeLe16(record.data() + 2, 0xffff);
  EXPECT_FALSE(parseFontGlyph(record.data(), record.size(), glyph));
}

}  // namespace
