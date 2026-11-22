#pragma once

#include <cstddef>
#include <cstdint>

namespace textplayer {

constexpr uint8_t WIRE_VERSION = 0;
constexpr size_t FRAME_HEADER_SIZE = 14;
constexpr size_t START_METADATA_SIZE = 6;
constexpr size_t MAX_FRAME_BYTES = 240;
constexpr size_t gattPayloadLimit(const size_t mtu) {
  constexpr size_t attOverhead = 3;
  const size_t frameBytes = mtu > attOverhead ? mtu - attOverhead : 0;
  const size_t capped = frameBytes < MAX_FRAME_BYTES ? frameBytes : MAX_FRAME_BYTES;
  return capped > FRAME_HEADER_SIZE ? capped - FRAME_HEADER_SIZE : 0;
}

constexpr uint8_t FRAME_START = 1;
constexpr uint8_t FRAME_END = 2;
constexpr uint32_t MAX_OBJECT_BYTES = 65'535;
constexpr uint8_t CAP_PAGE_TIMINGS = 1 << 2;
constexpr uint8_t CAP_PIPELINE_TIMINGS = 1 << 3;
constexpr uint8_t CAP_CONNECTION_INTERVAL = 1 << 4;
constexpr uint8_t CAP_PAGE_BURST = 1 << 5;
constexpr uint8_t CAP_SD_GLYPHS = 1 << 6;
constexpr uint8_t CAP_GLYPH_TURN_TIMINGS = 1 << 7;
constexpr uint8_t PAGE_BURST_FRAMES = 6;
constexpr size_t PAGE_CREDIT_BYTES = sizeof(uint16_t);
constexpr uint8_t SESSION_PAGE_TIMINGS = 1 << 0;
constexpr uint8_t SESSION_PIPELINE_TIMINGS = 1 << 1;
constexpr uint8_t SESSION_CONNECTION_INTERVAL = 1 << 2;
constexpr uint8_t SESSION_PAGE_BURST = 1 << 3;
constexpr uint8_t SESSION_SD_GLYPHS = 1 << 4;
constexpr uint8_t SESSION_GLYPH_TURN_TIMINGS = 1 << 5;
constexpr uint8_t SESSION_PREPARE_PAGES = 1 << 6;
constexpr uint32_t HELLO_EXTENSIONS = 1;
constexpr uint8_t CAP_PREPARE_PAGES = 1;
constexpr size_t NAV_REQUEST_BYTES = 10;
constexpr size_t MAX_PREPARED_PAGE_BYTES = 2048;
constexpr size_t MAX_FONT_CHUNK_BYTES = 8192;
constexpr size_t FONT_STATUS_BYTES = 5;
constexpr uint32_t ERROR_FONT_REQUIRED = 34;

enum class FontStatus : uint8_t { Ready = 0, Missing = 1, Uploading = 2, Rejected = 3 };
constexpr uint8_t SESSION_TIMING_FLAGS = SESSION_PAGE_TIMINGS | SESSION_PIPELINE_TIMINGS;
constexpr uint8_t SESSION_LINK_TIMINGS = SESSION_TIMING_FLAGS | SESSION_CONNECTION_INTERVAL;
constexpr size_t START_SESSION_BYTES = 28;
constexpr size_t DISPLAYED_BYTES = 20;
constexpr size_t PROFILED_DISPLAYED_BYTES = DISPLAYED_BYTES + 5 * sizeof(uint32_t);
constexpr size_t PIPELINE_DISPLAYED_BYTES = PROFILED_DISPLAYED_BYTES + 3 * sizeof(uint32_t);
constexpr size_t LINK_DISPLAYED_BYTES = PIPELINE_DISPLAYED_BYTES + 2 * sizeof(uint16_t);
constexpr size_t GLYPH_TURN_DISPLAYED_BYTES = LINK_DISPLAYED_BYTES + 2 * sizeof(uint32_t) + 2 * sizeof(uint16_t);
constexpr uint32_t NO_TURN_TIMING = UINT32_MAX;

constexpr bool validProfileFlags(const uint8_t flags) {
  return flags == 0 || flags == SESSION_PAGE_TIMINGS || flags == SESSION_TIMING_FLAGS ||
         flags == SESSION_LINK_TIMINGS || flags == (SESSION_LINK_TIMINGS | SESSION_GLYPH_TURN_TIMINGS);
}

constexpr bool validSessionFlags(const uint8_t flags) {
  if ((flags & (SESSION_GLYPH_TURN_TIMINGS | SESSION_PREPARE_PAGES)) != 0 && (flags & SESSION_SD_GLYPHS) == 0) {
    return false;
  }
  return validProfileFlags(flags & ~(SESSION_PAGE_BURST | SESSION_SD_GLYPHS | SESSION_PREPARE_PAGES));
}

class PageReceiveWindow {
 public:
  void reset() { staged_ = 0; }

  // Count only fragments accepted by the object sink.
  bool needsCredit() {
    if (++staged_ < PAGE_BURST_FRAMES) return false;
    reset();
    return true;
  }

 private:
  uint8_t staged_ = 0;
};

constexpr size_t displayedBytes(const uint8_t flags) {
  if ((flags & SESSION_GLYPH_TURN_TIMINGS) != 0) return GLYPH_TURN_DISPLAYED_BYTES;
  if ((flags & SESSION_CONNECTION_INTERVAL) != 0) return LINK_DISPLAYED_BYTES;
  if ((flags & SESSION_PIPELINE_TIMINGS) != 0) return PIPELINE_DISPLAYED_BYTES;
  return flags != 0 ? PROFILED_DISPLAYED_BYTES : DISPLAYED_BYTES;
}

enum class MessageType : uint8_t {
  Hello = 1,
  Capabilities = 2,
  StartSession = 3,
  PageGrid = 4,
  PageBitmap = 5,
  RequestPage = 6,
  Displayed = 7,
  NoPage = 8,
  Error = 9,
  PairingResponse = 10,
  PageCredit = 11,
  PageGlyphs = 12,
  FontQuery = 13,
  FontBegin = 14,
  FontChunk = 15,
  FontEnd = 16,
  FontStatus = 17,
  PagePrepared = 18,
};

constexpr bool isPage(const MessageType type) {
  return type == MessageType::PageGrid || type == MessageType::PageBitmap || type == MessageType::PageGlyphs;
}

constexpr bool isBulkWrite(const MessageType type) { return isPage(type) || type == MessageType::FontChunk; }

inline uint16_t readLe16(const uint8_t* data) {
  return static_cast<uint16_t>(data[0]) | static_cast<uint16_t>(data[1]) << 8;
}

inline uint32_t readLe32(const uint8_t* data) {
  return static_cast<uint32_t>(data[0]) | static_cast<uint32_t>(data[1]) << 8 | static_cast<uint32_t>(data[2]) << 16 |
         static_cast<uint32_t>(data[3]) << 24;
}

inline void writeLe16(uint8_t* data, const uint16_t value) {
  data[0] = static_cast<uint8_t>(value);
  data[1] = static_cast<uint8_t>(value >> 8);
}

inline void writeLe32(uint8_t* data, const uint32_t value) {
  data[0] = static_cast<uint8_t>(value);
  data[1] = static_cast<uint8_t>(value >> 8);
  data[2] = static_cast<uint8_t>(value >> 16);
  data[3] = static_cast<uint8_t>(value >> 24);
}

inline uint32_t crc32Update(uint32_t crc, const uint8_t* data, size_t length) {
  while (length-- > 0) {
    crc ^= *data++;
    for (int bit = 0; bit < 8; ++bit) {
      crc = (crc >> 1) ^ (0xEDB88320UL & (0U - (crc & 1U)));
    }
  }
  return crc;
}

inline uint32_t crc32(const uint8_t* data, const size_t length) {
  return crc32Update(0xFFFFFFFFUL, data, length) ^ 0xFFFFFFFFUL;
}

struct FrameView {
  MessageType type = MessageType::Error;
  uint8_t flags = 0;
  uint32_t sessionId = 0;
  uint32_t objectId = 0;
  uint16_t offset = 0;
  const uint8_t* payload = nullptr;
  size_t payloadLength = 0;
};

inline bool parseFrame(const uint8_t* data, const size_t length, FrameView& frame) {
  if (data == nullptr || length < FRAME_HEADER_SIZE || data[0] != WIRE_VERSION || data[1] == 0 ||
      (data[2] & ~(FRAME_START | FRAME_END)) != 0 || data[3] != 0) {
    return false;
  }
  frame.type = static_cast<MessageType>(data[1]);
  frame.flags = data[2];
  frame.sessionId = readLe32(data + 4);
  frame.objectId = readLe32(data + 8);
  frame.offset = readLe16(data + 12);
  frame.payload = data + FRAME_HEADER_SIZE;
  frame.payloadLength = length - FRAME_HEADER_SIZE;
  return true;
}

inline void writeFrameHeader(uint8_t* data, const MessageType type, const uint8_t flags, const uint32_t sessionId,
                             const uint32_t objectId, const uint16_t offset) {
  data[0] = WIRE_VERSION;
  data[1] = static_cast<uint8_t>(type);
  data[2] = flags;
  data[3] = 0;
  writeLe32(data + 4, sessionId);
  writeLe32(data + 8, objectId);
  writeLe16(data + 12, offset);
}

enum class AssemblyStatus : uint8_t { NeedMore, Complete, Error };

struct AssemblyResult {
  AssemblyStatus status = AssemblyStatus::Error;
  MessageType type = MessageType::Error;
  uint32_t sessionId = 0;
  uint32_t objectId = 0;
  uint32_t bodyLength = 0;
  uint32_t expectedCrc = 0;
};

class ObjectAssembler {
 public:
  explicit ObjectAssembler(const uint32_t maxBodyBytes = MAX_OBJECT_BYTES) : maxBodyBytes(maxBodyBytes) {}

  void reset() {
    active = false;
    received = 0;
    total = 0;
    crc = 0xFFFFFFFFUL;
  }

  template <typename Sink>
  AssemblyResult receive(const FrameView& frame, Sink& sink) {
    if (frame.flags & FRAME_START) {
      return start(frame, sink);
    }
    if (!active || frame.type != type || frame.sessionId != sessionId || frame.objectId != objectId ||
        frame.offset != received || (frame.payloadLength == 0 && !(frame.flags & FRAME_END))) {
      return fail();
    }
    return append(frame, sink, 0);
  }

  bool isActive() const { return active; }
  uint32_t receivedBytes() const { return received; }
  uint32_t totalBytes() const { return total; }

 private:
  template <typename Sink>
  AssemblyResult start(const FrameView& frame, Sink& sink) {
    if (active || frame.offset != 0 || frame.payloadLength < START_METADATA_SIZE) {
      return fail();
    }
    const uint32_t newTotal = readLe16(frame.payload);
    if (newTotal > maxBodyBytes || newTotal > MAX_OBJECT_BYTES) {
      return fail();
    }
    type = frame.type;
    sessionId = frame.sessionId;
    objectId = frame.objectId;
    expectedCrc = readLe32(frame.payload + 2);
    total = newTotal;
    received = 0;
    crc = 0xFFFFFFFFUL;
    active = true;
    return append(frame, sink, START_METADATA_SIZE);
  }

  template <typename Sink>
  AssemblyResult append(const FrameView& frame, Sink& sink, const size_t metadataSize) {
    const size_t bodyBytes = frame.payloadLength - metadataSize;
    if (bodyBytes > total - received || !sink.write(frame.payload + metadataSize, bodyBytes)) {
      return fail();
    }
    crc = crc32Update(crc, frame.payload + metadataSize, bodyBytes);
    received += bodyBytes;
    if (!(frame.flags & FRAME_END)) {
      return result(AssemblyStatus::NeedMore);
    }
    if (received != total || (crc ^ 0xFFFFFFFFUL) != expectedCrc) {
      return fail();
    }
    active = false;
    return result(AssemblyStatus::Complete);
  }

  AssemblyResult result(const AssemblyStatus status) const {
    return AssemblyResult{status, type, sessionId, objectId, received, expectedCrc};
  }

  AssemblyResult fail() {
    active = false;
    return result(AssemblyStatus::Error);
  }

  uint32_t maxBodyBytes;
  MessageType type = MessageType::Error;
  uint32_t sessionId = 0;
  uint32_t objectId = 0;
  uint32_t expectedCrc = 0;
  uint32_t total = 0;
  uint32_t received = 0;
  uint32_t crc = 0xFFFFFFFFUL;
  bool active = false;
};

}  // namespace textplayer
