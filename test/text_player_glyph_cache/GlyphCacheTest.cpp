#include <GlyphCache.h>
#include <gtest/gtest.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace {

using textplayer::GlyphBinding;
using textplayer::GlyphCache;

template <size_t MaxSlots>
bool prepare(GlyphCache<MaxSlots>& cache, const std::vector<GlyphBinding>& bindings,
             const std::vector<uint16_t>& tokens, const bool reset = false) {
  const uint32_t epoch = cache.getEpoch();
  const uint32_t revision = cache.getRevision();
  return cache.prepare(epoch, revision, reset ? epoch + 1 : epoch, reset ? 1 : revision + 1, reset, bindings.data(),
                       bindings.size(), tokens.data(), tokens.size());
}

template <size_t MaxSlots>
void writeAll(GlyphCache<MaxSlots>& cache, const std::vector<GlyphBinding>& bindings) {
  for (const GlyphBinding& binding : bindings) {
    const std::vector<uint8_t> bytes(binding.byteLength, static_cast<uint8_t>(binding.slotId + 1));
    ASSERT_TRUE(cache.writeCandidate(binding.slotId, bytes.data(), bytes.size()));
  }
}

template <size_t MaxSlots>
void commit(GlyphCache<MaxSlots>& cache, const std::vector<uint16_t>& tokens) {
  ASSERT_TRUE(cache.commitDisplayed(tokens.data(), tokens.size()));
}

TEST(TextPlayerGlyphCache, CommitsBindingsAndLastOccurrenceRecency) {
  std::array<uint8_t, 16> pool{};
  GlyphCache<4> cache(pool.data(), pool.size());
  const std::vector<GlyphBinding> bindings{{0, 0, 2}, {1, 2, 2}};
  const std::vector<uint16_t> tokens{0, 1, 0};

  ASSERT_TRUE(prepare(cache, bindings, tokens));
  EXPECT_EQ(cache.getRevision(), 0u);
  writeAll(cache, bindings);
  commit(cache, tokens);

  EXPECT_EQ(cache.getEpoch(), 1u);
  EXPECT_EQ(cache.getRevision(), 1u);
  EXPECT_EQ(cache.occupiedCount(), 2u);
  EXPECT_LT(cache.slotLastUsed(1), cache.slotLastUsed(0));
  EXPECT_EQ(cache.poolByte(0), 1);
  EXPECT_EQ(cache.poolByte(2), 2);
}

TEST(TextPlayerGlyphCache, HitPageNeedsNoWritesAndAdvancesRevision) {
  std::array<uint8_t, 16> pool{};
  GlyphCache<4> cache(pool.data(), pool.size());
  const std::vector<GlyphBinding> initial{{0, 0, 2}, {1, 2, 2, {5, 2, 1, -2, 3}}};
  ASSERT_TRUE(prepare(cache, initial, {0, 1}));
  writeAll(cache, initial);
  commit(cache, {0, 1});

  ASSERT_TRUE(prepare(cache, {}, {1}));
  size_t length = 0;
  textplayer::GlyphMetrics metrics;
  const uint8_t* data = cache.candidateData(1, length, &metrics);
  ASSERT_NE(data, nullptr);
  EXPECT_EQ(length, 2u);
  EXPECT_EQ(metrics.width, 5u);
  EXPECT_EQ(metrics.height, 2u);
  EXPECT_EQ(metrics.rowStride, 1u);
  EXPECT_EQ(metrics.offsetX, -2);
  EXPECT_EQ(metrics.offsetY, 3);
  commit(cache, {1});
  EXPECT_EQ(cache.getRevision(), 2u);
  EXPECT_GT(cache.slotLastUsed(1), cache.slotLastUsed(0));
}

TEST(TextPlayerGlyphCache, OverlapEvictsUnreferencedSlot) {
  std::array<uint8_t, 16> pool{};
  GlyphCache<4> cache(pool.data(), pool.size());
  const std::vector<GlyphBinding> initial{{0, 0, 2}, {1, 2, 2}};
  ASSERT_TRUE(prepare(cache, initial, {0, 1}));
  writeAll(cache, initial);
  commit(cache, {0, 1});

  const std::vector<GlyphBinding> replacement{{2, 2, 2}};
  ASSERT_TRUE(prepare(cache, replacement, {0, 2}));
  writeAll(cache, replacement);
  commit(cache, {0, 2});
  EXPECT_TRUE(cache.isOccupied(0));
  EXPECT_FALSE(cache.isOccupied(1));
  EXPECT_TRUE(cache.isOccupied(2));
}

TEST(TextPlayerGlyphCache, RejectsOverlapWithReferencedSlot) {
  std::array<uint8_t, 16> pool{};
  GlyphCache<4> cache(pool.data(), pool.size());
  const std::vector<GlyphBinding> initial{{0, 0, 2}};
  ASSERT_TRUE(prepare(cache, initial, {0}));
  writeAll(cache, initial);
  commit(cache, {0});

  const std::vector<GlyphBinding> overlapping{{1, 1, 2}};
  EXPECT_FALSE(prepare(cache, overlapping, {0, 1}));
  EXPECT_FALSE(cache.hasCandidate());
  EXPECT_TRUE(cache.isUsable());
}

TEST(TextPlayerGlyphCache, RejectsUnusedDuplicateAndMissingBindings) {
  std::array<uint8_t, 16> pool{};
  GlyphCache<4> cache(pool.data(), pool.size());

  EXPECT_FALSE(prepare(cache, {{0, 0, 2}}, {1}));
  EXPECT_FALSE(prepare(cache, {{0, 0, 2}, {0, 2, 2}}, {0}));
  EXPECT_FALSE(prepare(cache, {{0, 0, 2}, {1, 1, 2}}, {0, 1}));
  EXPECT_FALSE(prepare(cache, {}, {0}));
}

TEST(TextPlayerGlyphCache, RequiresEveryCandidateWriteBeforeDisplayCommit) {
  std::array<uint8_t, 16> pool{};
  GlyphCache<4> cache(pool.data(), pool.size());
  const std::vector<GlyphBinding> bindings{{0, 0, 2}, {1, 2, 2}};
  const std::vector<uint16_t> tokens{0, 1};
  ASSERT_TRUE(prepare(cache, bindings, tokens));
  const std::array<uint8_t, 2> bytes{1, 2};
  ASSERT_TRUE(cache.writeCandidate(0, bytes.data(), bytes.size()));

  EXPECT_FALSE(cache.commitDisplayed(tokens.data(), tokens.size()));
  EXPECT_EQ(cache.getRevision(), 0u);
  ASSERT_TRUE(cache.writeCandidate(1, bytes.data(), bytes.size()));
  EXPECT_TRUE(cache.commitDisplayed(tokens.data(), tokens.size()));
}

TEST(TextPlayerGlyphCache, AbortAfterWriteRequiresFreshSession) {
  std::array<uint8_t, 16> pool{};
  GlyphCache<4> cache(pool.data(), pool.size());
  const std::vector<GlyphBinding> bindings{{0, 0, 2}};
  ASSERT_TRUE(prepare(cache, bindings, {0}));
  const std::array<uint8_t, 2> bytes{7, 8};
  ASSERT_TRUE(cache.writeCandidate(0, bytes.data(), bytes.size()));

  cache.abortCandidate();
  EXPECT_FALSE(cache.isUsable());
  EXPECT_EQ(cache.occupiedCount(), 0u);
  EXPECT_FALSE(prepare(cache, bindings, {0}));

  cache.resetSession(9);
  EXPECT_TRUE(cache.isUsable());
  EXPECT_EQ(cache.getEpoch(), 9u);
  EXPECT_EQ(cache.getRevision(), 0u);
}

TEST(TextPlayerGlyphCache, AbortBeforeWriteKeepsCommittedCache) {
  std::array<uint8_t, 16> pool{};
  GlyphCache<4> cache(pool.data(), pool.size());
  const std::vector<GlyphBinding> initial{{0, 0, 2}};
  ASSERT_TRUE(prepare(cache, initial, {0}));
  writeAll(cache, initial);
  commit(cache, {0});

  ASSERT_TRUE(prepare(cache, {{1, 2, 2}}, {1}));
  cache.abortCandidate();
  EXPECT_TRUE(cache.isUsable());
  EXPECT_TRUE(cache.isOccupied(0));
  EXPECT_EQ(cache.getRevision(), 1u);
}

TEST(TextPlayerGlyphCache, ResetEpochCannotReuseOldBindings) {
  std::array<uint8_t, 16> pool{};
  GlyphCache<4> cache(pool.data(), pool.size());
  const std::vector<GlyphBinding> initial{{0, 0, 2}};
  ASSERT_TRUE(prepare(cache, initial, {0}));
  writeAll(cache, initial);
  commit(cache, {0});

  EXPECT_FALSE(prepare(cache, {}, {0}, true));
  const std::vector<GlyphBinding> reset{{1, 0, 2}};
  ASSERT_TRUE(prepare(cache, reset, {1}, true));
  writeAll(cache, reset);
  commit(cache, {1});
  EXPECT_EQ(cache.getEpoch(), 2u);
  EXPECT_EQ(cache.getRevision(), 1u);
  EXPECT_FALSE(cache.isOccupied(0));
  EXPECT_TRUE(cache.isOccupied(1));
}

TEST(TextPlayerGlyphCache, RejectsStaleBaseAndInvalidRanges) {
  std::array<uint8_t, 16> pool{};
  GlyphCache<4> cache(pool.data(), pool.size());
  const GlyphBinding binding{0, 0, 2};
  const uint16_t token = 0;

  EXPECT_FALSE(cache.prepare(2, 0, 2, 1, false, &binding, 1, &token, 1));
  EXPECT_FALSE(cache.prepare(1, 0, 1, 2, false, &binding, 1, &token, 1));
  const GlyphBinding outOfRange{0, 15, 2};
  EXPECT_FALSE(cache.prepare(1, 0, 1, 1, false, &outOfRange, 1, &token, 1));
}

}  // namespace
