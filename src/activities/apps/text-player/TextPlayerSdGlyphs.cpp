#include <Arduino.h>

#include <cstring>

#include "TextPlayerActivity.h"

bool TextPlayerActivity::beginObject(const textplayer::FrameView& frame) {
  using textplayer::MessageType;
  const auto state = state_.load(std::memory_order_acquire);
  if (state == SessionState::PagePending) return false;
  controlBytes_ = 0;
  glyphReadFailed_ = false;

  switch (frame.type) {
    case MessageType::PageGlyphs:
    case MessageType::PageGrid:
    case MessageType::PageBitmap: {
      const bool sd = frame.type == MessageType::PageGlyphs;
      const bool duplicate = state == SessionState::Ready && hasDisplayedPage_ && frame.objectId == lastPushSeq_;
      if (sd != sdGlyphs_ || frame.sessionId != sessionId_ ||
          (!duplicate && state != SessionState::WaitingAnchor && state != SessionState::WaitingPage)) {
        return false;
      }
      if (sd) {
        if (preparingPage_ && (frame.payloadLength < textplayer::START_METADATA_SIZE ||
                               textplayer::readLe16(frame.payload) > textplayer::MAX_PREPARED_PAGE_BYTES))
          return false;
        sinkKind_ = SinkKind::GlyphPage;
        glyphPushSeq_ = frame.objectId;
        duplicateGlyphPage_ = duplicate;
        glyphStream_.reset(layout_, negotiatedTokens_);
        return true;
      }
      sinkKind_ = SinkKind::LegacyPage;
      closeObject(true);
      objectOpen_ = Storage.openFileForWrite("TXTP", OBJECT_PATH, objectFile_);
      objectOnDisk_ = objectOpen_;
      return objectOpen_;
    }
    case MessageType::FontChunk:
      if (sessionId_ != 0 || frame.sessionId != 0 || !fonts_.beginChunk()) return false;
      sinkKind_ = SinkKind::FontChunk;
      return true;
    case MessageType::Hello:
    case MessageType::StartSession:
    case MessageType::NoPage:
    case MessageType::FontQuery:
    case MessageType::FontBegin:
    case MessageType::FontEnd:
      sinkKind_ = SinkKind::Control;
      return true;
    default:
      return false;
  }
}

bool TextPlayerActivity::ObjectSink::begin(const uint32_t requestId, const uint32_t pageId) {
  if (owner.duplicateGlyphPage_) return pageId == owner.lastPageId_;
  if (pageId == 0 || owner.glyphPushSeq_ <= owner.latestGlyphSeq_ || !owner.validPageRequest(requestId)) return false;
  owner.pendingPageId_ = pageId;
  if (!owner.preparingPage_ && !owner.canceledTurn()) {
    owner.provisionalFrame_ = true;
    owner.renderer.clearScreen();
  }
  return true;
}

bool TextPlayerActivity::ObjectSink::draw(const uint8_t face, const uint16_t id, const int32_t x, const int32_t y) {
  if (owner.duplicateGlyphPage_ || owner.canceledTurn()) return true;

  const bool profiled = (owner.profileFlags_ & textplayer::SESSION_GLYPH_TURN_TIMINGS) != 0;
  textplayer::FontGlyphView glyph{};
  if (!owner.hotGlyphs_.lookup(face, id, glyph)) {
    auto& scratch = owner.glyphScratch_;
    const uint32_t startUs = profiled ? micros() : 0;
    if (!owner.fonts_.readGlyph(face, id, scratch.data(), scratch.size(), glyph)) {
      if (owner.preparingPage_) return true;
      owner.glyphReadFailed_ = true;
      return false;
    }
    if (profiled) {
      owner.currentTimings_.glyphReadUs += static_cast<uint32_t>(micros()) - startUs;
      ++owner.currentTimings_.glyphMisses;
    }
    owner.hotGlyphs_.insert(face, id, scratch.data(), glyph);
  } else if (profiled) {
    ++owner.currentTimings_.glyphHits;
  }
  if (owner.preparingPage_) return true;
  const int left = x + glyph.left;
  const int top = y + glyph.top;
  for (uint16_t dy = 0; dy < glyph.height; ++dy) {
    const uint8_t* row = glyph.ink + static_cast<size_t>(dy) * glyph.stride;
    for (uint16_t dx = 0; dx < glyph.width; ++dx) {
      if ((row[dx / 8] & (0x80 >> (dx % 8))) != 0) owner.renderer.drawPixel(left + dx, top + dy, true);
    }
  }
  return true;
}

void TextPlayerActivity::handleGlyphPage(const AssemblyResult& result) {
  if (!glyphStream_.complete()) {
    failSession(result.objectId, 6);
    return;
  }
  if (duplicateGlyphPage_) {
    if (!duplicatePage(result, lastPageId_)) failSession(result.objectId, 14);
    return;
  }
  latestGlyphSeq_ = result.objectId;
  if (preparingPage_ || canceledTurn()) {
    finishWithoutDisplay(result.objectId);
    return;
  }
  queuePage(result, pendingRequestId_, pendingPageId_, PageKind::SdGlyphs);
}

void TextPlayerActivity::handleFontObject(const AssemblyResult& result) {
  using textplayer::FontStatus;
  using textplayer::MessageType;
  uint32_t offset = 0;
  FontStatus status = FontStatus::Rejected;
  if (sessionId_ == 0 && result.sessionId == 0 && result.objectId != 0) {
    textplayer::FontGeneration generation{};
    switch (result.type) {
      case MessageType::FontQuery:
        if (!fonts_.uploading() && result.bodyLength == generation.size()) {
          std::memcpy(generation.data(), control_.data(), generation.size());
          status = fonts_.select(generation) ? FontStatus::Ready : FontStatus::Missing;
        }
        break;
      case MessageType::FontBegin:
        if (result.bodyLength == generation.size() + 2 * sizeof(uint32_t)) {
          std::memcpy(generation.data(), control_.data(), generation.size());
          const auto* metadata = control_.data() + generation.size();
          if (fonts_.beginUpload(generation, textplayer::readLe32(metadata), textplayer::readLe32(metadata + 4))) {
            status = FontStatus::Uploading;
          }
        }
        break;
      case MessageType::FontChunk:
        if (fonts_.endChunk()) {
          status = FontStatus::Uploading;
          offset = fonts_.uploadedBytes();
        }
        break;
      case MessageType::FontEnd:
        if (result.bodyLength == 0) {
          offset = fonts_.uploadedBytes();
          if (fonts_.finishUpload()) status = FontStatus::Ready;
        }
        break;
      default:
        break;
    }
  }
  if (status == FontStatus::Rejected) {
    fonts_.abortUpload();
    offset = 0;
  }
  sendFontStatus(result.objectId, status, offset);
}

void TextPlayerActivity::sendFontStatus(const uint32_t objectId, const textplayer::FontStatus status,
                                        const uint32_t offset) {
  std::array<uint8_t, textplayer::FONT_STATUS_BYTES> body{};
  body[0] = static_cast<uint8_t>(status);
  textplayer::writeLe32(body.data() + 1, offset);
  if (!textplayer::transportSend(textplayer::MessageType::FontStatus, 0, objectId, body.data(), body.size())) {
    failSession(objectId, 33);
  }
}
