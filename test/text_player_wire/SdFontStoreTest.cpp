#include <HotGlyphCache.h>
#include <SdFontStore.h>
#include <gtest/gtest.h>

#include <array>
#include <vector>

namespace {

using namespace textplayer;

constexpr char SLOT0[] = "/.crosspoint/texpod-font-0.tpf";
constexpr char SLOT1[] = "/.crosspoint/texpod-font-1.tpf";
constexpr char BACKUP0[] = "/.crosspoint/texpod-font-0.bak";
constexpr char PART[] = "/.crosspoint/texpod-font.part";
constexpr size_t FIRST_GLYPH = FONT_HEADER_BYTES + FONT_FACE_BYTES;
constexpr FontGeneration GEN1{1};
constexpr FontGeneration GEN2{2};

std::vector<uint8_t> makePack(const FontGeneration& gen, uint8_t ink) {
  constexpr uint16_t recordSize = FONT_GLYPH_OVERHEAD + 1;
  std::vector<uint8_t> pack(FIRST_GLYPH + recordSize);
  std::memcpy(pack.data(), "TPF1", 4);
  std::memcpy(pack.data() + 4, gen.data(), gen.size());
  writeLe16(pack.data() + 20, 30);
  writeLe16(pack.data() + 22, 1);
  writeLe32(pack.data() + 24, pack.size());
  writeLe32(pack.data() + FONT_HEADER_BYTES, 1);
  writeLe16(pack.data() + FONT_HEADER_BYTES + 4, recordSize);
  const uint32_t crc = crc32Update(0xFFFFFFFFUL, pack.data(), 28);
  writeLe32(pack.data() + 28, crc32Update(crc, pack.data() + FONT_HEADER_BYTES, FONT_FACE_BYTES) ^ 0xFFFFFFFFUL);
  uint8_t* record = pack.data() + FIRST_GLYPH;
  writeLe16(record, 8);
  writeLe16(record + 2, 1);
  record[8] = ink;
  writeLe32(record + 9, crc32(record, 9));
  return pack;
}

bool writeChunk(SdFontStore& store, uint32_t offset, const uint8_t* data, size_t length) {
  std::array<uint8_t, 4> prefix{};
  writeLe32(prefix.data(), offset);
  return store.beginChunk() && store.writeChunk(prefix.data(), 1) && store.writeChunk(prefix.data() + 1, 3) &&
         store.writeChunk(data, length) && store.endChunk();
}

class TextPlayerSdFontStore : public testing::Test {
 protected:
  void SetUp() override { Storage = {}; }

  void stage(const FontGeneration& gen, const std::vector<uint8_t>& pack, uint32_t crcXor = 0) {
    ASSERT_TRUE(store.beginUpload(gen, pack.size(), crc32(pack.data(), pack.size()) ^ crcXor));
    ASSERT_TRUE(writeChunk(store, 0, pack.data(), 7));
    EXPECT_EQ(store.uploadedBytes(), 7u);
    ASSERT_TRUE(writeChunk(store, 7, pack.data() + 7, pack.size() - 7));
    EXPECT_EQ(store.uploadedBytes(), pack.size());
  }

  SdFontStore store;
};

TEST_F(TextPlayerSdFontStore, InstallsFullPackAndReadsGlyphWithoutModifyingStorage) {
  const auto pack = makePack(GEN1, 0xe7);
  ASSERT_FALSE(store.select(GEN1));
  ASSERT_NO_FATAL_FAILURE(stage(GEN1, pack));
  ASSERT_TRUE(store.finishUpload());
  EXPECT_FALSE(Storage.exists(PART));
  EXPECT_EQ(Storage.files.at(SLOT0), pack);
  EXPECT_EQ(store.pixelSize(), 30);
  const auto before = Storage.files;
  std::array<uint8_t, MAX_FONT_RECORD_BYTES> scratch{};
  FontGlyphView glyph{};
  ASSERT_TRUE(store.readGlyph(0, 0, scratch.data(), scratch.size(), glyph));
  EXPECT_EQ(glyph.ink[0], 0xe7);
  EXPECT_FALSE(store.readGlyph(0, 1, scratch.data(), scratch.size(), glyph));
  EXPECT_EQ(Storage.files, before);
  store.close();
  Storage.files.at(SLOT0)[FIRST_GLYPH + 8] ^= 1;
  ASSERT_TRUE(store.select(GEN1));
  EXPECT_FALSE(store.readGlyph(0, 0, scratch.data(), scratch.size(), glyph));
}

TEST_F(TextPlayerSdFontStore, PreparesRandomReadsOnlyOnOpeningMatchingGeneration) {
  Storage.files[SLOT0] = makePack(GEN1, 0x77);
  Storage.files[SLOT1] = makePack(GEN2, 0xe7);
  EXPECT_FALSE(store.select(FontGeneration{3}));
  EXPECT_EQ(Storage.randomReadPreparations, 0u);
  ASSERT_TRUE(store.select(GEN2));
  EXPECT_EQ(Storage.randomReadPreparations, 1u);
  std::array<uint8_t, MAX_FONT_RECORD_BYTES> scratch{};
  FontGlyphView glyph{};
  ASSERT_TRUE(store.readGlyph(0, 0, scratch.data(), scratch.size(), glyph));
  ASSERT_TRUE(store.select(GEN2));
  ASSERT_TRUE(store.readGlyph(0, 0, scratch.data(), scratch.size(), glyph));
  EXPECT_EQ(glyph.ink[0], 0xe7);
  EXPECT_EQ(Storage.randomReadPreparations, 1u);
  store.close();
  ASSERT_TRUE(store.select(GEN2));
  EXPECT_EQ(Storage.randomReadPreparations, 2u);
}

TEST_F(TextPlayerSdFontStore, BakedPacksAreReadOnlyAndMatchingInstalledPacksTakePrecedence) {
  constexpr char baked[] = "/.crosspoint/texpod-fonts/01000000000000000000000000000000.tpf";
  Storage.files[baked] = makePack(GEN2, 0x22);
  EXPECT_FALSE(store.select(GEN1));
  Storage.files[baked] = makePack(GEN1, 0x11);
  ASSERT_TRUE(store.select(GEN1));
  std::array<uint8_t, MAX_FONT_RECORD_BYTES> scratch{};
  FontGlyphView glyph{};
  ASSERT_TRUE(store.readGlyph(0, 0, scratch.data(), scratch.size(), glyph));
  EXPECT_EQ(glyph.ink[0], 0x11);
  ASSERT_NO_FATAL_FAILURE(stage(GEN1, makePack(GEN1, 0x33)));
  ASSERT_TRUE(store.finishUpload());
  store.close();
  ASSERT_TRUE(store.select(GEN1));
  ASSERT_TRUE(store.readGlyph(0, 0, scratch.data(), scratch.size(), glyph));
  EXPECT_EQ(glyph.ink[0], 0x33);
  EXPECT_EQ(Storage.files[baked], makePack(GEN1, 0x11));
}

TEST_F(TextPlayerSdFontStore, FragmentedPackStillSupportsRandomReads) {
  Storage.files[SLOT0] = makePack(GEN1, 0x88);
  Storage.contiguousFile = false;
  ASSERT_TRUE(store.select(GEN1));
  EXPECT_EQ(Storage.randomReadPreparations, 1u);
  std::array<uint8_t, MAX_FONT_RECORD_BYTES> scratch{};
  FontGlyphView glyph{};
  ASSERT_TRUE(store.readGlyph(0, 0, scratch.data(), scratch.size(), glyph));
  EXPECT_EQ(glyph.ink[0], 0x88);
}

TEST_F(TextPlayerSdFontStore, InvalidLengthOffsetOrCrcNeverActivatesStagedPack) {
  const auto pack = makePack(GEN1, 0x88);
  Storage.files[SLOT0] = pack;
  ASSERT_TRUE(store.beginUpload(GEN1, pack.size(), crc32(pack.data(), pack.size())));
  EXPECT_FALSE(writeChunk(store, 1, pack.data(), 7));
  EXPECT_EQ(store.uploadedBytes(), 0u);
  EXPECT_FALSE(store.finishUpload());
  store.abortUpload();
  const auto replacement = makePack(GEN1, 0xf8);
  ASSERT_NO_FATAL_FAILURE(stage(GEN1, replacement, 1));
  EXPECT_FALSE(store.finishUpload());
  store.abortUpload();
  EXPECT_EQ(Storage.files.at(SLOT0), pack);
  EXPECT_FALSE(Storage.exists(PART));
}

TEST_F(TextPlayerSdFontStore, RecoversBetweenRenamesAndForceUpdateCanReplaceRecoveredPack) {
  const auto original = makePack(GEN1, 0x88);
  const auto replacement = makePack(GEN1, 0xf8);
  const auto other = makePack(GEN2, 0x0e);
  Storage.files[SLOT0] = original;
  Storage.files[SLOT1] = other;
  ASSERT_NO_FATAL_FAILURE(stage(GEN1, replacement));
  Storage.failRenameFrom = PART;
  EXPECT_FALSE(store.finishUpload());
  EXPECT_FALSE(Storage.exists(SLOT0));
  EXPECT_EQ(Storage.files.at(BACKUP0), original);
  EXPECT_EQ(Storage.files.at(SLOT1), other);
  store.close();

  SdFontStore restarted;
  ASSERT_TRUE(restarted.select(GEN1));
  EXPECT_EQ(restarted.pixelSize(), 30);
  restarted.close();
  Storage.failRenameFrom.clear();
  ASSERT_NO_FATAL_FAILURE(stage(GEN1, replacement));
  ASSERT_TRUE(store.finishUpload());
  EXPECT_FALSE(Storage.exists(BACKUP0));
  EXPECT_EQ(Storage.files.at(SLOT0), replacement);
  EXPECT_EQ(Storage.files.at(SLOT1), other);
}

TEST(TextPlayerSdGlyphs, HotCacheReusesCallerPoolAndEvictsLeastRecentlyUsed) {
  std::array<uint8_t, 27> pool{};
  HotGlyphCache<4> hot;
  hot.reset(pool.data(), pool.size());
  FontGlyphView glyph{};
  for (uint16_t id = 0; id < 3; ++id) {
    const auto pack = makePack(GEN1, 0xe0 | id);
    const auto* record = pack.data() + FIRST_GLYPH;
    ASSERT_TRUE(parseFontGlyph(record, FONT_GLYPH_OVERHEAD + 1, glyph));
    hot.insert(0, id, record, glyph);
  }
  ASSERT_TRUE(hot.lookup(0, 0, glyph));
  EXPECT_EQ(glyph.ink[0], 0xe0);
  const auto pack = makePack(GEN1, 0x8f);
  const auto* record = pack.data() + FIRST_GLYPH;
  ASSERT_TRUE(parseFontGlyph(record, FONT_GLYPH_OVERHEAD + 1, glyph));
  hot.insert(1, 0, record, glyph);
  EXPECT_FALSE(hot.lookup(0, 1, glyph));
  ASSERT_TRUE(hot.lookup(0, 2, glyph));
  EXPECT_EQ(glyph.ink[0], 0xe2);
  ASSERT_TRUE(hot.lookup(1, 0, glyph));
  EXPECT_EQ(glyph.ink[0], 0x8f);
  EXPECT_EQ(hot.size(), 3u);
  hot.reset(pool.data(), pool.size());
  EXPECT_FALSE(hot.lookup(0, 0, glyph));
}

}  // namespace
