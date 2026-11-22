#include <WireProtocol.h>
#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace {

struct Sink {
  std::vector<uint8_t> bytes;
  bool accept = true;

  bool write(const uint8_t* data, const size_t length) {
    if (!accept) return false;
    bytes.insert(bytes.end(), data, data + length);
    return true;
  }
};

textplayer::FrameView frame(const std::vector<uint8_t>& bytes, const textplayer::MessageType type, const uint8_t flags,
                            const uint16_t offset, std::vector<uint8_t>& storage) {
  storage.resize(textplayer::FRAME_HEADER_SIZE + bytes.size());
  textplayer::writeFrameHeader(storage.data(), type, flags, 7, 11, offset);
  std::copy(bytes.begin(), bytes.end(), storage.begin() + textplayer::FRAME_HEADER_SIZE);
  textplayer::FrameView view;
  EXPECT_TRUE(textplayer::parseFrame(storage.data(), storage.size(), view));
  return view;
}

std::vector<uint8_t> startPayload(const std::vector<uint8_t>& body, const size_t firstBytes = 0) {
  std::vector<uint8_t> payload(6);
  textplayer::writeLe16(payload.data(), body.size());
  textplayer::writeLe32(payload.data() + 2, textplayer::crc32(body.data(), body.size()));
  payload.insert(payload.end(), body.begin(), body.begin() + firstBytes);
  return payload;
}

TEST(TextPlayerWire, GattPayloadExcludesAttAndWireHeaders) {
  EXPECT_EQ(textplayer::gattPayloadLimit(22), textplayer::START_METADATA_SIZE - 1);
  EXPECT_EQ(textplayer::gattPayloadLimit(23), textplayer::START_METADATA_SIZE);
  EXPECT_EQ(textplayer::gattPayloadLimit(185), 168u);
  EXPECT_EQ(textplayer::gattPayloadLimit(243), 226u);
  EXPECT_EQ(textplayer::gattPayloadLimit(517), 226u);
}

TEST(TextPlayerWire, NegotiatedTimingFlagsPreserveLegacyAckSizes) {
  EXPECT_TRUE(textplayer::validProfileFlags(0));
  EXPECT_TRUE(textplayer::validProfileFlags(1));
  EXPECT_TRUE(textplayer::validProfileFlags(3));
  EXPECT_TRUE(textplayer::validProfileFlags(7));
  EXPECT_FALSE(textplayer::validProfileFlags(2));
  EXPECT_FALSE(textplayer::validProfileFlags(4));
  EXPECT_FALSE(textplayer::validProfileFlags(5));
  EXPECT_FALSE(textplayer::validProfileFlags(6));
  EXPECT_FALSE(textplayer::validProfileFlags(15));
  EXPECT_EQ(textplayer::displayedBytes(0), 20u);
  EXPECT_EQ(textplayer::displayedBytes(1), 40u);
  EXPECT_EQ(textplayer::displayedBytes(3), 52u);
  EXPECT_EQ(textplayer::displayedBytes(7), 56u);
}

TEST(TextPlayerWire, PageBurstIsIndependentOfTimingFlags) {
  for (const uint8_t timing : {0, 1, 3, 7}) {
    EXPECT_TRUE(textplayer::validSessionFlags(timing));
    EXPECT_TRUE(textplayer::validSessionFlags(timing | textplayer::SESSION_PAGE_BURST));
  }
  EXPECT_FALSE(textplayer::validSessionFlags(2 | textplayer::SESSION_PAGE_BURST));
  EXPECT_TRUE(textplayer::validSessionFlags(textplayer::SESSION_SD_GLYPHS | textplayer::SESSION_PAGE_BURST));
  EXPECT_FALSE(textplayer::validSessionFlags(0x40 | textplayer::SESSION_PAGE_BURST));
}

TEST(TextPlayerWire, GlyphTurnTimingsRequireSdGlyphsAndLinkTimings) {
  constexpr uint8_t turn = textplayer::SESSION_GLYPH_TURN_TIMINGS;
  constexpr uint8_t sd = textplayer::SESSION_SD_GLYPHS;
  constexpr uint8_t link = textplayer::SESSION_LINK_TIMINGS;
  EXPECT_FALSE(textplayer::validSessionFlags(turn | link));
  EXPECT_FALSE(textplayer::validSessionFlags(turn | sd | textplayer::SESSION_TIMING_FLAGS));
  EXPECT_TRUE(textplayer::validSessionFlags(turn | sd | link));
  EXPECT_TRUE(textplayer::validSessionFlags(turn | sd | link | textplayer::SESSION_PAGE_BURST));
  EXPECT_EQ(textplayer::displayedBytes(turn | link), 68u);
}

TEST(TextPlayerWire, NeighborPreparationRequiresSdGlyphs) {
  constexpr auto prepare = textplayer::SESSION_PREPARE_PAGES;
  EXPECT_FALSE(textplayer::validSessionFlags(prepare));
  EXPECT_TRUE(textplayer::validSessionFlags(prepare | textplayer::SESSION_SD_GLYPHS | textplayer::SESSION_PAGE_BURST));
}

TEST(TextPlayerWire, CreditsOnlyFullNonFinalBatchesAtCumulativeOffset) {
  constexpr size_t bodyPerFrame = 226;
  constexpr size_t firstBytes = bodyPerFrame - textplayer::START_METADATA_SIZE;
  constexpr size_t batch = textplayer::PAGE_BURST_FRAMES;
  textplayer::PageReceiveWindow window;
  for (const size_t frameCount : {batch - 1, batch, batch + 1, batch * 2, batch * 2 + 1}) {
    SCOPED_TRACE(frameCount);
    const std::vector<uint8_t> body(firstBytes + bodyPerFrame * (frameCount - 1), 0x5a);
    textplayer::ObjectAssembler assembler;
    Sink sink;
    std::vector<uint8_t> storage;
    size_t offset = 0;
    size_t credits = 0;
    window.reset();
    for (size_t index = 0; index < frameCount; ++index) {
      const bool start = index == 0;
      const bool end = index + 1 == frameCount;
      const auto payload = start ? startPayload(body, firstBytes)
                                 : std::vector<uint8_t>(body.begin() + offset, body.begin() + offset + bodyPerFrame);
      const uint8_t flags = (start ? textplayer::FRAME_START : 0) | (end ? textplayer::FRAME_END : 0);
      const auto input = frame(payload, textplayer::MessageType::PageBitmap, flags, offset, storage);
      const auto result = assembler.receive(input, sink);
      offset += start ? firstBytes : bodyPerFrame;
      ASSERT_EQ(result.status, end ? textplayer::AssemblyStatus::Complete : textplayer::AssemblyStatus::NeedMore);
      const bool sendCredit = result.status == textplayer::AssemblyStatus::NeedMore && window.needsCredit();
      EXPECT_EQ(sendCredit, !end && (index + 1) % batch == 0);
      if (sendCredit) {
        ++credits;
        EXPECT_EQ(result.sessionId, 7u);
        EXPECT_EQ(result.objectId, 11u);
        EXPECT_EQ(result.bodyLength, offset);
      }
    }
    EXPECT_EQ(credits, (frameCount - 1) / batch);
    EXPECT_EQ(sink.bytes, body);
  }
}

TEST(TextPlayerWire, AssemblesFragmentedObjectAndCrc) {
  const std::vector<uint8_t> body{1, 2, 3, 4, 5, 6};
  textplayer::ObjectAssembler assembler;
  Sink sink;
  std::vector<uint8_t> bytes;
  auto first = frame(startPayload(body, 2), textplayer::MessageType::PageGrid, textplayer::FRAME_START, 0, bytes);
  EXPECT_EQ(assembler.receive(first, sink).status, textplayer::AssemblyStatus::NeedMore);

  const std::vector<uint8_t> rest{3, 4, 5, 6};
  auto last = frame(rest, textplayer::MessageType::PageGrid, textplayer::FRAME_END, 2, bytes);
  const auto result = assembler.receive(last, sink);
  EXPECT_EQ(result.status, textplayer::AssemblyStatus::Complete);
  EXPECT_EQ(result.bodyLength, body.size());
  EXPECT_EQ(sink.bytes, body);
}

TEST(TextPlayerWire, RejectsOutOfOrderAndCanRestart) {
  const std::vector<uint8_t> body{9, 8, 7};
  textplayer::ObjectAssembler assembler;
  Sink sink;
  std::vector<uint8_t> bytes;
  auto first = frame(startPayload(body), textplayer::MessageType::PageGrid, textplayer::FRAME_START, 0, bytes);
  EXPECT_EQ(assembler.receive(first, sink).status, textplayer::AssemblyStatus::NeedMore);
  auto wrong = frame(body, textplayer::MessageType::PageGrid, textplayer::FRAME_END, 2, bytes);
  EXPECT_EQ(assembler.receive(wrong, sink).status, textplayer::AssemblyStatus::Error);
  EXPECT_FALSE(assembler.isActive());

  auto restarted = frame(startPayload(body, 3), textplayer::MessageType::PageGrid,
                         textplayer::FRAME_START | textplayer::FRAME_END, 0, bytes);
  EXPECT_EQ(assembler.receive(restarted, sink).status, textplayer::AssemblyStatus::Complete);
}

TEST(TextPlayerWire, RejectsCrcMismatch) {
  const std::vector<uint8_t> body{1, 2, 3};
  auto payload = startPayload(body, 3);
  payload[2]++;
  textplayer::ObjectAssembler assembler;
  Sink sink;
  std::vector<uint8_t> bytes;
  auto input =
      frame(payload, textplayer::MessageType::PageGrid, textplayer::FRAME_START | textplayer::FRAME_END, 0, bytes);
  EXPECT_EQ(assembler.receive(input, sink).status, textplayer::AssemblyStatus::Error);
}

TEST(TextPlayerWire, RejectsDuplicateOffsetAndTruncatedEnd) {
  const std::vector<uint8_t> body{1, 2, 3, 4};
  textplayer::ObjectAssembler assembler;
  Sink sink;
  std::vector<uint8_t> bytes;
  auto first = frame(startPayload(body, 2), textplayer::MessageType::PageGrid, textplayer::FRAME_START, 0, bytes);
  EXPECT_EQ(assembler.receive(first, sink).status, textplayer::AssemblyStatus::NeedMore);
  const std::vector<uint8_t> duplicate{1, 2};
  auto repeated = frame(duplicate, textplayer::MessageType::PageGrid, 0, 0, bytes);
  EXPECT_EQ(assembler.receive(repeated, sink).status, textplayer::AssemblyStatus::Error);

  auto restarted = frame(startPayload(body, 2), textplayer::MessageType::PageGrid, textplayer::FRAME_START, 0, bytes);
  EXPECT_EQ(assembler.receive(restarted, sink).status, textplayer::AssemblyStatus::NeedMore);
  const std::vector<uint8_t> rest{3, 4};
  auto last = frame(rest, textplayer::MessageType::PageGrid, textplayer::FRAME_END, 3, bytes);
  EXPECT_EQ(assembler.receive(last, sink).status, textplayer::AssemblyStatus::Error);
}

TEST(TextPlayerWire, EnforcesBodyLimitAndSinkFailure) {
  const std::vector<uint8_t> body{1, 2, 3};
  textplayer::ObjectAssembler assembler(2);
  Sink sink;
  std::vector<uint8_t> bytes;
  auto input = frame(startPayload(body), textplayer::MessageType::PageGrid, textplayer::FRAME_START, 0, bytes);
  EXPECT_EQ(assembler.receive(input, sink).status, textplayer::AssemblyStatus::Error);

  textplayer::ObjectAssembler failingAssembler;
  sink.accept = false;
  auto valid = frame(startPayload(body), textplayer::MessageType::PageGrid, textplayer::FRAME_START, 0, bytes);
  EXPECT_EQ(failingAssembler.receive(valid, sink).status, textplayer::AssemblyStatus::Error);
}

TEST(TextPlayerWire, ParsesUnalignedHeaderWithoutWidenedReads) {
  std::array<uint8_t, 17> bytes{};
  textplayer::writeFrameHeader(bytes.data() + 1, textplayer::MessageType::Hello,
                               textplayer::FRAME_START | textplayer::FRAME_END, 0x01020304, 0x11223344, 0x5566);
  bytes[15] = 8;
  bytes[16] = 9;
  textplayer::FrameView view;
  ASSERT_TRUE(textplayer::parseFrame(bytes.data() + 1, bytes.size() - 1, view));
  EXPECT_EQ(view.sessionId, 0x01020304u);
  EXPECT_EQ(view.objectId, 0x11223344u);
  EXPECT_EQ(view.offset, 0x5566u);
  EXPECT_EQ(view.payloadLength, 2u);
  EXPECT_EQ(view.payload[1], 9u);
}

TEST(TextPlayerWire, RejectsMalformedFrameAndCrcMatchesKnownVector) {
  const std::array<uint8_t, 9> digits{'1', '2', '3', '4', '5', '6', '7', '8', '9'};
  EXPECT_EQ(textplayer::crc32(digits.data(), digits.size()), 0xCBF43926u);
  textplayer::FrameView view;
  EXPECT_FALSE(textplayer::parseFrame(nullptr, 0, view));
  const std::array<uint8_t, 12> invalid{};
  EXPECT_FALSE(textplayer::parseFrame(invalid.data(), invalid.size(), view));
}

TEST(TextPlayerWire, DecodesSharedAndroidFrameVector) {
  const std::array<uint8_t, 29> encoded{0, 4,    3,    0,    7,    0, 0, 0, 11, 0, 0, 0, 0, 0, 9,
                                        0, 0x9e, 0xab, 0xef, 0x40, 1, 2, 3, 4,  5, 6, 7, 8, 9};
  textplayer::FrameView view;
  ASSERT_TRUE(textplayer::parseFrame(encoded.data(), encoded.size(), view));
  EXPECT_EQ(view.type, textplayer::MessageType::PageGrid);
  EXPECT_EQ(view.sessionId, 7u);
  EXPECT_EQ(view.objectId, 11u);
  textplayer::ObjectAssembler assembler;
  Sink sink;
  const auto result = assembler.receive(view, sink);
  ASSERT_EQ(result.status, textplayer::AssemblyStatus::Complete);
  EXPECT_EQ(result.bodyLength, 9u);
  EXPECT_EQ(sink.bytes, (std::vector<uint8_t>{1, 2, 3, 4, 5, 6, 7, 8, 9}));
}

}  // namespace
