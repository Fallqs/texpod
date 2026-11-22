#include "TextPlayerActivity.h"

#include <Arduino.h>
#include <BleInput.h>
#include <HalMemory.h>
#include <HalPowerManager.h>
#include <I18n.h>
#include <Logging.h>
#include <Memory.h>

#include <algorithm>
#include <cstring>
#include <limits>

#include "components/UITheme.h"
#include "fontIds.h"

namespace {

constexpr uint32_t FRAGMENT_TIMEOUT_MS = 5000;
constexpr uint32_t FONT_IDLE_TIMEOUT_MS = 15000;
constexpr uint16_t SESSION_RESET = 1;

int32_t readI32(const uint8_t* data) { return static_cast<int32_t>(textplayer::readLe32(data)); }
int16_t readI16(const uint8_t* data) { return static_cast<int16_t>(textplayer::readLe16(data)); }

}  // namespace

bool TextPlayerActivity::ObjectSink::write(const uint8_t* data, const size_t length) {
  if (length == 0) return true;
  switch (kind) {
    case SinkKind::Control:
      if (length > owner.control_.size() - owner.controlBytes_) return false;
      std::memcpy(owner.control_.data() + owner.controlBytes_, data, length);
      owner.controlBytes_ += length;
      return true;
    case SinkKind::LegacyPage:
      return owner.objectFile_.write(data, length) == length;
    case SinkKind::GlyphPage:
      return owner.glyphStream_.write(data, length, *this);
    case SinkKind::FontChunk:
      return owner.fonts_.writeChunk(data, length);
  }
  return false;
}

void TextPlayerActivity::onEnter() {
  Activity::onEnter();
  releaseBarrier_ = mappedInput.isPressed(MappedInputManager::Button::Back) ||
                    mappedInput.isPressed(MappedInputManager::Button::Confirm) ||
                    mappedInput.isPressed(MappedInputManager::Button::PageForward) ||
                    mappedInput.isPressed(MappedInputManager::Button::PageBack);
  if (!bleinput::stop()) {
    LOG_ERR("TXTP", "Unable to release Bluetooth page-turner host");
    state_.store(SessionState::BluetoothError, std::memory_order_release);
    requestUpdate();
    return;
  }
  glyphPool_ = makeUniqueNoThrow<uint8_t[]>(GLYPH_POOL_BYTES);
  if (!glyphPool_) {
    LOG_ERR("TXTP", "OOM: pool=%u", static_cast<unsigned>(GLYPH_POOL_BYTES));
    state_.store(SessionState::MemoryError, std::memory_order_release);
    requestUpdate();
    return;
  }
  Storage.ensureDirectoryExists("/.crosspoint");
#if CONFIG_IDF_TARGET_ESP32C3
  // The C3 BLE host has used roughly 50 KiB in prior HID measurements.
  const auto heap = HalMemory::getInternalHeap();
  if (heap.freeBytes < 56 * 1024 || heap.largestBlockBytes < 16 * 1024) {
    LOG_ERR("TXTP", "BLE heap too low: free=%zu largest=%zu", heap.freeBytes, heap.largestBlockBytes);
    releaseResources();
    state_.store(SessionState::MemoryError, std::memory_order_release);
    requestUpdate();
    return;
  }
  LOG_INF("TXTP", "Before BLE: free=%zu largest=%zu", heap.freeBytes, heap.largestBlockBytes);
  powerManager.setPowerSaving(false);
#endif
  if (!textplayer::transportStart()) {
    releaseResources();
    state_.store(SessionState::BluetoothError, std::memory_order_release);
  }
  requestUpdate();
}

void TextPlayerActivity::onExit() {
  prepareForSleep();
  Activity::onExit();
}

void TextPlayerActivity::prepareForSleep() {
  textplayer::transportStop();
  closeObject(true);
  if (provisionalFrame_) {
    // Sleep overlays and Quick Resume must not preserve an incomplete page.
    renderer.clearScreen();
    provisionalFrame_ = false;
  }
  releaseResources();
}

bool TextPlayerActivity::handleForcedRefresh() {
  RenderLock lock(*this);
  return provisionalFrame_;
}

bool TextPlayerActivity::allocateLegacyResources() {
  if (cache_) return true;
  cache_ = makeUniqueNoThrow<textplayer::GlyphCache<MAX_SLOTS>>(glyphPool_.get(), GLYPH_POOL_BYTES);
  gridPage_ = makeUniqueNoThrow<textplayer::GridPage<MAX_TOKENS>>();
  bindings_ = makeUniqueNoThrow<textplayer::GlyphBinding[]>(MAX_SLOTS);
  if (!cache_ || !gridPage_ || !bindings_) {
    LOG_ERR("TXTP", "OOM: legacy cache=%u grid=%u bindings=%u", static_cast<unsigned>(sizeof(*cache_)),
            static_cast<unsigned>(sizeof(*gridPage_)),
            static_cast<unsigned>(MAX_SLOTS * sizeof(textplayer::GlyphBinding)));
    releaseLegacyResources();
    return false;
  }
  return true;
}

void TextPlayerActivity::releaseLegacyResources() {
  bindings_.reset();
  gridPage_.reset();
  cache_.reset();
}

void TextPlayerActivity::releaseResources() {
  fonts_.close();
  hotGlyphs_.reset();
  releaseLegacyResources();
  glyphPool_.reset();
}

void TextPlayerActivity::loop() {
  if (inputReleaseBarrier()) {
    return;
  }

  const auto state = state_.load(std::memory_order_acquire);
  if (preparePages_ && (state == SessionState::Ready || state == SessionState::WaitingPage ||
                        (state == SessionState::PagePending && pendingRequestId_ != 0))) {
    const bool next = mappedInput.wasPressed(MappedInputManager::Button::PageForward);
    const bool previous = mappedInput.wasPressed(MappedInputManager::Button::PageBack);
    if (next != previous && !pairingPin_) {
      turnIntent_.press(next ? 1 : -1);
      lastPressMs_ = millis();
    }
  }

  {
    RenderLock lock(RenderLock::Mode::Try);
    if (lock.ownsLock()) {
      textplayer::TransportEvent event;
      while (textplayer::transportPoll(event)) {
        handleTransportEvent(event);
        if (preparePages_ && event.type == textplayer::TransportEventType::Frame &&
            state_.load(std::memory_order_acquire) != SessionState::WaitingAnchor) {
          // Sample input between frames while validating an in-flight page.
          break;
        }
      }
      if (preparePages_) serviceTurns();
    }
  }

  if (((assembler_.isActive() || state_.load(std::memory_order_acquire) == SessionState::WaitingPage) &&
       millis() - lastFragmentMs_ > FRAGMENT_TIMEOUT_MS) ||
      (fonts_.uploading() && millis() - lastFragmentMs_ > FONT_IDLE_TIMEOUT_MS)) {
    RenderLock lock(*this);
    failSession(sessionId_, 1);
  }

  if (pairingPin_) {
    const bool accept = mappedInput.wasReleased(MappedInputManager::Button::Confirm);
    const bool reject = mappedInput.wasReleased(MappedInputManager::Button::Back);
    if (accept || reject) {
      RenderLock lock(*this);
      textplayer::transportPairingDecision(accept);
      pairingPin_.reset();
      requestUpdate();
    }
    return;
  }

  if (mappedInput.wasReleased(MappedInputManager::Button::Back)) {
    if (returnToHome_) {
      activityManager.goHome(HomeMenuItem::TEXT_PLAYER);
    } else {
      activityManager.goToApps();
    }
    return;
  }
  if (preparePages_ || state_.load(std::memory_order_acquire) != SessionState::Ready) return;
  if (mappedInput.wasReleased(MappedInputManager::Button::PageForward)) {
    requestPage(1);
  } else if (mappedInput.wasReleased(MappedInputManager::Button::PageBack)) {
    requestPage(-1);
  }
}

bool TextPlayerActivity::inputReleaseBarrier() {
  if (!releaseBarrier_) {
    return false;
  }
  const bool held = mappedInput.isPressed(MappedInputManager::Button::Back) ||
                    mappedInput.isPressed(MappedInputManager::Button::Confirm) ||
                    mappedInput.isPressed(MappedInputManager::Button::PageForward) ||
                    mappedInput.isPressed(MappedInputManager::Button::PageBack);
  if (held) {
    return true;
  }
  releaseBarrier_ = false;
  return true;
}

void TextPlayerActivity::handleTransportEvent(const textplayer::TransportEvent& event) {
  switch (event.type) {
    case textplayer::TransportEventType::Frame:
      handleFrame(event);
      break;
    case textplayer::TransportEventType::Connected:
      resetSession();
      authorized_.store(false, std::memory_order_release);
      requestUpdate();
      break;
    case textplayer::TransportEventType::Authenticated:
      authorized_.store(true, std::memory_order_release);
      pairingPin_.reset();
      if (!hasDisplayedPage_) requestUpdate();
      break;
    case textplayer::TransportEventType::Disconnected:
      authorized_.store(false, std::memory_order_release);
      resetSession();
      if (!textplayer::transportAdvertise()) {
        LOG_ERR("TXTP", "Unable to resume advertising");
        state_.store(SessionState::Error, std::memory_order_release);
      }
      requestUpdate();
      break;
    case textplayer::TransportEventType::PairingRequest:
      pairingPin_ = event.value;
      requestUpdate();
      break;
    case textplayer::TransportEventType::Error:
      failSession(sessionId_, 3);
      break;
  }
}

void TextPlayerActivity::handleFrame(const textplayer::TransportEvent& event) {
  textplayer::FrameView frame;
  if (!textplayer::parseFrame(event.data, event.length, frame)) {
    failSession(sessionId_, 4);
    return;
  }
  const bool isPage = textplayer::isPage(frame.type);
  if (isPage && state_.load(std::memory_order_acquire) == SessionState::Error) return;
  const bool burst = (pageBurst_ && isPage) || frame.type == textplayer::MessageType::FontChunk;
  const bool timed = isPage && profileFlags_ != 0;
  const bool detailed = timed && (profileFlags_ & textplayer::SESSION_PIPELINE_TIMINGS) != 0;
  const uint32_t stageStartUs = detailed ? micros() : 0;
  if ((frame.flags & textplayer::FRAME_START) != 0) {
    if (assembler_.isActive() || !beginObject(frame)) {
      failSession(frame.objectId, 32);
      return;
    }
    pageReceiveWindow_.reset();
    if (timed) {
      receiveStartMs_ = millis();
      firstArrivalMs_ = event.value;
      currentTimings_ = {};
      currentTimings_.firstIntervalUnits = event.intervalUnits;
    }
  }

  ObjectSink sink{*this, sinkKind_};
  const textplayer::AssemblyResult result = assembler_.receive(frame, sink);
  lastFragmentMs_ = millis();
  switch (result.status) {
    case textplayer::AssemblyStatus::NeedMore:
      if (detailed) currentTimings_.stagingUs += static_cast<uint32_t>(micros()) - stageStartUs;
      if (burst && pageReceiveWindow_.needsCredit()) {
        std::array<uint8_t, textplayer::PAGE_CREDIT_BYTES> body{};
        textplayer::writeLe16(body.data(), static_cast<uint16_t>(result.bodyLength));
        if (!textplayer::transportSend(textplayer::MessageType::PageCredit, result.sessionId, result.objectId,
                                       body.data(), body.size())) {
          failSession(result.objectId, 33);
        }
      }
      return;
    case textplayer::AssemblyStatus::Error:
      failSession(frame.objectId, glyphReadFailed_ ? textplayer::ERROR_FONT_REQUIRED : 6);
      return;
    case textplayer::AssemblyStatus::Complete:
      if (objectOpen_) {
        const bool closed = objectFile_.close();
        objectOpen_ = false;
        if (!closed) {
          failSession(result.objectId, 5);
          return;
        }
      }
      if (timed) {
        receiveEndMs_ = millis();
      }
      if (detailed) {
        currentTimings_.stagingUs += static_cast<uint32_t>(micros()) - stageStartUs;
        currentTimings_.arrivalSpanMs = event.value - firstArrivalMs_;
        currentTimings_.lastArrivalToStagedMs = receiveEndMs_ - event.value;
        currentTimings_.lastIntervalUnits = event.intervalUnits;
      }
      handleObject(result);
      return;
  }
}

void TextPlayerActivity::handleObject(const textplayer::AssemblyResult& result) {
  switch (result.type) {
    case textplayer::MessageType::Hello:
      handleHello(result);
      break;
    case textplayer::MessageType::StartSession:
      handleStartSession(result);
      break;
    case textplayer::MessageType::PageGrid:
      handlePageGrid(result);
      break;
    case textplayer::MessageType::PageBitmap:
      handlePageBitmap(result);
      break;
    case textplayer::MessageType::PageGlyphs:
      handleGlyphPage(result);
      break;
    case textplayer::MessageType::NoPage:
      handleNoPage(result);
      break;
    case textplayer::MessageType::FontQuery:
    case textplayer::MessageType::FontBegin:
    case textplayer::MessageType::FontChunk:
    case textplayer::MessageType::FontEnd:
      handleFontObject(result);
      break;
    case textplayer::MessageType::Capabilities:
    case textplayer::MessageType::RequestPage:
    case textplayer::MessageType::Displayed:
    case textplayer::MessageType::Error:
    case textplayer::MessageType::PairingResponse:
    case textplayer::MessageType::PageCredit:
    case textplayer::MessageType::FontStatus:
    default:
      failSession(result.objectId, 7);
      break;
  }
}

void TextPlayerActivity::handleHello(const textplayer::AssemblyResult& result) {
  if (result.bodyLength != 0) {
    failSession(result.objectId, 8);
    return;
  }
  const Rect safe = UITheme::getInstance().getScreenSafeArea(renderer, false);
  constexpr size_t baseBytes = 38;
  std::array<uint8_t, baseBytes + 1> body{};
  textplayer::writeLe16(body.data(), textplayer::MAX_FRAME_BYTES);
  textplayer::writeLe16(body.data() + 2, textplayer::MAX_OBJECT_BYTES);
  textplayer::writeLe32(body.data() + 4, safe.x);
  textplayer::writeLe32(body.data() + 8, safe.y);
  textplayer::writeLe32(body.data() + 12, safe.width);
  textplayer::writeLe32(body.data() + 16, safe.height);
  textplayer::writeLe16(body.data() + 20, MAX_SLOTS);
  textplayer::writeLe32(body.data() + 22, GLYPH_POOL_BYTES);
  textplayer::writeLe16(body.data() + 26, MAX_TOKENS);
  body[28] = 2;
  body[29] = 3 | textplayer::CAP_PAGE_TIMINGS | textplayer::CAP_PIPELINE_TIMINGS | textplayer::CAP_CONNECTION_INTERVAL |
             textplayer::CAP_PAGE_BURST | textplayer::CAP_SD_GLYPHS | textplayer::CAP_GLYPH_TURN_TIMINGS;
  textplayer::writeLe32(body.data() + 30, renderer.getScreenWidth());
  textplayer::writeLe32(body.data() + 34, renderer.getScreenHeight());
  body[baseBytes] = textplayer::CAP_PREPARE_PAGES;
  const size_t bytes = result.objectId == textplayer::HELLO_EXTENSIONS ? body.size() : baseBytes;
  if (!textplayer::transportSend(textplayer::MessageType::Capabilities, 0, 0, body.data(), bytes)) {
    failSession(0, 9);
  }
}

void TextPlayerActivity::handleStartSession(const textplayer::AssemblyResult& result) {
  constexpr size_t baseBytes = textplayer::START_SESSION_BYTES;
  const auto& body = control_;
  const uint8_t flags = result.bodyLength > baseBytes ? body[baseBytes] : 0;
  const bool sd = (flags & textplayer::SESSION_SD_GLYPHS) != 0;
  const bool correctSize = sd ? result.bodyLength == SD_SESSION_BYTES
                              : result.bodyLength == baseBytes || result.bodyLength == SESSION_FLAGS_BYTES;
  if (!glyphPool_ || result.sessionId == 0 || fonts_.uploading() || !correctSize ||
      !textplayer::validSessionFlags(flags)) {
    failSession(result.objectId, 10);
    return;
  }
  textplayer::GridLayout layout;
  layout.originX = readI32(body.data());
  layout.originY = readI32(body.data() + 4);
  layout.columns = textplayer::readLe16(body.data() + 8);
  layout.rows = textplayer::readLe16(body.data() + 10);
  layout.cellWidth = textplayer::readLe16(body.data() + 12);
  layout.cellHeight = textplayer::readLe16(body.data() + 14);
  layout.baselineOffsetX = readI16(body.data() + 16);
  layout.baselineOffsetY = readI16(body.data() + 18);
  layout.viewportWidth = renderer.getScreenWidth();
  layout.viewportHeight = renderer.getScreenHeight();
  const uint32_t poolBytes = textplayer::readLe32(body.data() + 20);
  const uint16_t slots = textplayer::readLe16(body.data() + 24);
  const uint16_t tokens = textplayer::readLe16(body.data() + 26);
  const Rect safe = UITheme::getInstance().getScreenSafeArea(renderer, false);
  if (!layout.isValid() || !layout.fitsWithin(safe.x, safe.y, safe.width, safe.height) || poolBytes == 0 ||
      poolBytes > GLYPH_POOL_BYTES || slots == 0 || slots > MAX_SLOTS || tokens == 0 || tokens > MAX_TOKENS) {
    failSession(result.objectId, 12);
    return;
  }

  textplayer::FontGeneration generation{};
  if (sd) std::memcpy(generation.data(), body.data() + SESSION_FLAGS_BYTES, generation.size());
  resetSession();
  sessionId_ = result.sessionId;
  if (sd) {
    releaseLegacyResources();
    if (!fonts_.select(generation)) {
      failSession(result.objectId, textplayer::ERROR_FONT_REQUIRED);
      return;
    }
    hotGlyphs_.reset(glyphPool_.get(), GLYPH_POOL_BYTES);
  } else if (!allocateLegacyResources()) {
    failSession(result.objectId, 5);
    return;
  }
  profileFlags_ =
      flags & ~(textplayer::SESSION_PAGE_BURST | textplayer::SESSION_SD_GLYPHS | textplayer::SESSION_PREPARE_PAGES);
  preparePages_ = (flags & textplayer::SESSION_PREPARE_PAGES) != 0;
  pageBurst_ = (flags & textplayer::SESSION_PAGE_BURST) != 0;
  sdGlyphs_ = sd;
  layout_ = layout;
  negotiatedSlots_ = slots;
  negotiatedTokens_ = tokens;
  negotiatedPoolBytes_ = poolBytes;
  state_.store(SessionState::WaitingAnchor, std::memory_order_release);
}

bool TextPlayerActivity::readPageIdentity(const textplayer::AssemblyResult& result, uint32_t& requestId,
                                          uint32_t& pageId) {
  std::array<uint8_t, 8> identity{};
  if (result.bodyLength < identity.size() || !seekObject(0) || !readExact(identity.data(), identity.size())) {
    return false;
  }
  requestId = textplayer::readLe32(identity.data());
  pageId = textplayer::readLe32(identity.data() + 4);
  return true;
}

bool TextPlayerActivity::validPageRequest(const uint32_t requestId) const {
  const SessionState state = state_.load(std::memory_order_acquire);
  return (state == SessionState::WaitingAnchor && requestId == 0) ||
         (state == SessionState::WaitingPage && requestId == pendingRequestId_);
}

bool TextPlayerActivity::duplicatePage(const textplayer::AssemblyResult& result, const uint32_t pageId) {
  if (!hasDisplayedPage_ || result.objectId != lastPushSeq_ || pageId != lastPageId_ ||
      result.expectedCrc != lastObjectCrc_) {
    return false;
  }
  sendDisplayed();
  closeObject(true);
  return true;
}

void TextPlayerActivity::handlePageGrid(const textplayer::AssemblyResult& result) {
  uint32_t requestId = 0;
  uint32_t pageId = 0;
  if (result.sessionId != sessionId_ || !readPageIdentity(result, requestId, pageId)) {
    failSession(result.objectId, 13);
    return;
  }
  if (duplicatePage(result, pageId)) {
    return;
  }
  if (!validPageRequest(requestId) || result.objectId <= lastPushSeq_ || result.bodyLength < GRID_HEADER_BYTES) {
    failSession(result.objectId, 14);
    return;
  }

  std::array<uint8_t, GRID_HEADER_BYTES> header{};
  if (!seekObject(0) || !readExact(header.data(), header.size())) {
    failSession(result.objectId, 15);
    return;
  }
  const uint32_t baseEpoch = textplayer::readLe32(header.data() + 8);
  const uint32_t baseRevision = textplayer::readLe32(header.data() + 12);
  const uint32_t targetEpoch = textplayer::readLe32(header.data() + 16);
  const uint32_t targetRevision = textplayer::readLe32(header.data() + 20);
  bindingCount_ = textplayer::readLe16(header.data() + 24);
  const uint16_t tokenCount = textplayer::readLe16(header.data() + 26);
  const uint16_t flags = textplayer::readLe16(header.data() + 28);
  const uint16_t reserved = textplayer::readLe16(header.data() + 30);
  if (bindingCount_ > negotiatedSlots_ || tokenCount == 0 || tokenCount > negotiatedTokens_ ||
      (flags & ~SESSION_RESET) != 0 || reserved != 0) {
    failSession(result.objectId, 16);
    return;
  }

  const size_t glyphDataOffset = GRID_HEADER_BYTES + bindingCount_ * GLYPH_DESCRIPTOR_BYTES;
  size_t tokenDataOffset = glyphDataOffset;
  for (size_t index = 0; index < bindingCount_; ++index) {
    std::array<uint8_t, GLYPH_DESCRIPTOR_BYTES> data{};
    if (!readExact(data.data(), data.size())) {
      failSession(result.objectId, 17);
      return;
    }
    const textplayer::GlyphMetrics metrics{
        textplayer::readLe16(data.data() + 10), textplayer::readLe16(data.data() + 12),
        textplayer::readLe16(data.data() + 14), readI16(data.data() + 16), readI16(data.data() + 18)};
    const textplayer::GlyphBinding binding{textplayer::readLe16(data.data()), textplayer::readLe32(data.data() + 2),
                                           textplayer::readLe32(data.data() + 6), metrics};
    if (binding.slotId >= negotiatedSlots_ || metrics.width == 0 || metrics.height == 0 ||
        metrics.rowStride != (metrics.width + 7) / 8 ||
        binding.byteLength != static_cast<uint32_t>(metrics.rowStride) * metrics.height ||
        binding.byteLength > MAX_GLYPH_BYTES || binding.poolOffset > negotiatedPoolBytes_ ||
        binding.byteLength > negotiatedPoolBytes_ - binding.poolOffset) {
      failSession(result.objectId, 18);
      return;
    }
    bindings_[index] = binding;
    tokenDataOffset += binding.byteLength;
  }
  if (tokenDataOffset + static_cast<size_t>(tokenCount) * 2 != result.bodyLength || !seekObject(tokenDataOffset)) {
    failSession(result.objectId, 19);
    return;
  }
  if (!loadGridTokens(tokenCount)) {
    failSession(result.objectId, 20);
    return;
  }
  if (!gridPage_->parseLoaded(tokenCount, layout_.columns, layout_.rows) ||
      !cache_->prepare(baseEpoch, baseRevision, targetEpoch, targetRevision, (flags & SESSION_RESET) != 0,
                       bindings_.get(), bindingCount_, gridPage_->glyphSlots(), gridPage_->glyphCount())) {
    failSession(result.objectId, 21);
    return;
  }
  if (!seekObject(glyphDataOffset)) {
    failSession(result.objectId, 22);
    return;
  }
  for (size_t index = 0; index < bindingCount_; ++index) {
    const textplayer::GlyphBinding& binding = bindings_[index];
    if (!readExact(glyphScratch_.data(), binding.byteLength) ||
        !cache_->writeCandidate(binding.slotId, glyphScratch_.data(), binding.byteLength)) {
      cache_->abortCandidate();
      failSession(result.objectId, 23);
      return;
    }
  }

  queuePage(result, requestId, pageId, PageKind::LegacyGrid);
}

bool TextPlayerActivity::loadGridTokens(const size_t tokenCount) {
  constexpr size_t tokenBytes = sizeof(int16_t);
  auto* tokens = gridPage_->loadBuffer();
  for (size_t loaded = 0; loaded < tokenCount;) {
    const size_t count = std::min(tokenCount - loaded, glyphScratch_.size() / tokenBytes);
    if (!readExact(glyphScratch_.data(), count * tokenBytes)) {
      return false;
    }
    for (size_t i = 0; i < count; ++i) {
      tokens[loaded + i] = readI16(glyphScratch_.data() + i * tokenBytes);
    }
    loaded += count;
  }
  return true;
}

void TextPlayerActivity::handlePageBitmap(const textplayer::AssemblyResult& result) {
  uint32_t requestId = 0;
  uint32_t pageId = 0;
  if (result.sessionId != sessionId_ || !readPageIdentity(result, requestId, pageId)) {
    failSession(result.objectId, 24);
    return;
  }
  if (duplicatePage(result, pageId)) {
    return;
  }
  if (!validPageRequest(requestId) || result.objectId <= lastPushSeq_ || result.bodyLength < BITMAP_HEADER_BYTES) {
    failSession(result.objectId, 25);
    return;
  }
  std::array<uint8_t, BITMAP_HEADER_BYTES> header{};
  if (!seekObject(0) || !readExact(header.data(), header.size())) {
    failSession(result.objectId, 26);
    return;
  }
  bitmapStride_ = textplayer::readLe16(header.data() + 8);
  bitmapWidth_ = textplayer::readLe16(header.data() + 10);
  bitmapHeight_ = textplayer::readLe16(header.data() + 12);
  if (bitmapWidth_ != renderer.getScreenWidth() || bitmapHeight_ != renderer.getScreenHeight() ||
      bitmapStride_ != (bitmapWidth_ + 7) / 8 || bitmapStride_ > MAX_BITMAP_ROW_BYTES ||
      textplayer::readLe16(header.data() + 14) != 0 ||
      result.bodyLength != BITMAP_HEADER_BYTES + static_cast<size_t>(bitmapStride_) * bitmapHeight_) {
    failSession(result.objectId, 27);
    return;
  }
  bitmapDataOffset_ = BITMAP_HEADER_BYTES;
  queuePage(result, requestId, pageId, PageKind::LegacyBitmap);
}

void TextPlayerActivity::queuePage(const AssemblyResult& result, const uint32_t requestId, const uint32_t pageId,
                                   const PageKind kind) {
  pendingRequestId_ = requestId;
  pendingPushSeq_ = result.objectId;
  pendingPageId_ = pageId;
  pendingObjectCrc_ = result.expectedCrc;
  pageKind_ = kind;
  if (profileFlags_) {
    renderQueuedMs_ = millis();
    currentTimings_.receiveMs = receiveEndMs_ - receiveStartMs_;
    currentTimings_.prepareMs = renderQueuedMs_ - receiveEndMs_;
  }
  state_.store(SessionState::PagePending, std::memory_order_release);
  pagePending_.store(true, std::memory_order_release);
  requestUpdate();
}

void TextPlayerActivity::handleNoPage(const textplayer::AssemblyResult& result) {
  if (result.sessionId != sessionId_ || result.bodyLength != 0 ||
      state_.load(std::memory_order_acquire) != SessionState::WaitingPage || result.objectId != pendingRequestId_) {
    failSession(result.objectId, 28);
    return;
  }
  if (preparePages_ && !preparingPage_) turnIntent_.claim(requestIntent_);
  preparingPage_ = false;
  pendingRequestId_ = 0;
  state_.store(SessionState::Ready, std::memory_order_release);
  closeObject(true);
}

bool TextPlayerActivity::canceledTurn() const {
  return preparePages_ && pendingRequestId_ != 0 && turnIntent_.snapshot() != requestIntent_;
}

void TextPlayerActivity::finishWithoutDisplay(const uint32_t objectId) {
  if (preparingPage_ && canceledTurn()) ++prepareRemaining_;
  preparingPage_ = false;
  pendingRequestId_ = 0;
  pagePending_.store(false, std::memory_order_release);
  state_.store(SessionState::Ready, std::memory_order_release);
  if (!textplayer::transportSend(textplayer::MessageType::PagePrepared, sessionId_, objectId, nullptr, 0)) {
    failSession(objectId, 30);
  }
}

void TextPlayerActivity::serviceTurns() {
  if (state_.load(std::memory_order_acquire) != SessionState::Ready || !hasDisplayedPage_ || pairingPin_) return;
  requestIntent_ = turnIntent_.snapshot();
  const int steps = textplayer::TurnIntent::steps(requestIntent_);
  const uint32_t now = millis();
  if (steps != 0) {
    if (now - lastPressMs_ >= 35) requestPage(steps);
    return;
  }
  if (prepareRemaining_ != 0 && now - lastPressMs_ >= 200 && now - lastDisplayMs_ >= 200) {
    requestPage(prepareRemaining_-- == 2 ? 1 : -1, true);
  }
}

void TextPlayerActivity::requestPage(const int offset, const bool prepare) {
  if (!authorized_.load(std::memory_order_acquire) || !hasDisplayedPage_ ||
      state_.load(std::memory_order_acquire) != SessionState::Ready) {
    return;
  }
  if (lastRequestId_ == std::numeric_limits<uint32_t>::max()) {
    failSession(lastPushSeq_, 29);
    return;
  }
  if (!prepare && (profileFlags_ & textplayer::SESSION_GLYPH_TURN_TIMINGS) != 0) {
    turnStartedMs_ = preparePages_ ? lastPressMs_ : millis();
  }
  const uint32_t requestId = ++lastRequestId_;
  std::array<uint8_t, textplayer::NAV_REQUEST_BYTES> body{};
  textplayer::writeLe32(body.data(), lastPageId_);
  textplayer::writeLe32(body.data() + 4, lastPushSeq_);
  body[8] = preparePages_ ? static_cast<uint8_t>(offset) : (offset > 0 ? 1 : 0);
  body[9] = prepare ? 1 : 0;
  preparingPage_ = prepare;
  pendingRequestId_ = requestId;
  lastFragmentMs_ = millis();
  state_.store(SessionState::WaitingPage, std::memory_order_release);
  if (!textplayer::transportSend(textplayer::MessageType::RequestPage, sessionId_, requestId, body.data(),
                                 preparePages_ ? body.size() : body.size() - 1)) {
    pendingRequestId_ = 0;
    state_.store(SessionState::Ready, std::memory_order_release);
    failSession(lastPushSeq_, 30);
  }
}

void TextPlayerActivity::sendDisplayed() {
  constexpr size_t baseBytes = textplayer::DISPLAYED_BYTES;
  std::array<uint8_t, textplayer::GLYPH_TURN_DISPLAYED_BYTES> body{};
  textplayer::writeLe32(body.data(), lastPushSeq_);
  textplayer::writeLe32(body.data() + 4, lastPageId_);
  if (sdGlyphs_) {
    textplayer::writeLe32(body.data() + 16, hotGlyphs_.size());
  } else {
    textplayer::writeLe32(body.data() + 8, cache_->getEpoch());
    textplayer::writeLe32(body.data() + 12, cache_->getRevision());
    textplayer::writeLe32(body.data() + 16, cache_->occupiedCount());
  }
  if ((profileFlags_ & textplayer::SESSION_PAGE_TIMINGS) != 0) {
    textplayer::writeLe32(body.data() + baseBytes, lastTimings_.receiveMs);
    textplayer::writeLe32(body.data() + baseBytes + 4, lastTimings_.prepareMs);
    textplayer::writeLe32(body.data() + baseBytes + 8, lastTimings_.renderQueueMs);
    textplayer::writeLe32(body.data() + baseBytes + 12, lastTimings_.drawMs);
    textplayer::writeLe32(body.data() + baseBytes + 16, lastTimings_.refreshMs);
  }
  if ((profileFlags_ & textplayer::SESSION_PIPELINE_TIMINGS) != 0) {
    constexpr size_t rxOffset = textplayer::PROFILED_DISPLAYED_BYTES;
    textplayer::writeLe32(body.data() + rxOffset, lastTimings_.arrivalSpanMs);
    textplayer::writeLe32(body.data() + rxOffset + 4, lastTimings_.stagingUs);
    textplayer::writeLe32(body.data() + rxOffset + 8, lastTimings_.lastArrivalToStagedMs);
  }
  if ((profileFlags_ & textplayer::SESSION_CONNECTION_INTERVAL) != 0) {
    constexpr size_t linkOffset = textplayer::PIPELINE_DISPLAYED_BYTES;
    textplayer::writeLe16(body.data() + linkOffset, lastTimings_.firstIntervalUnits);
    textplayer::writeLe16(body.data() + linkOffset + 2, lastTimings_.lastIntervalUnits);
  }
  if ((profileFlags_ & textplayer::SESSION_GLYPH_TURN_TIMINGS) != 0) {
    constexpr size_t glyphOffset = textplayer::LINK_DISPLAYED_BYTES;
    textplayer::writeLe32(body.data() + glyphOffset, lastTimings_.turnMs);
    textplayer::writeLe32(body.data() + glyphOffset + 4, lastTimings_.glyphReadUs);
    textplayer::writeLe16(body.data() + glyphOffset + 8, lastTimings_.glyphHits);
    textplayer::writeLe16(body.data() + glyphOffset + 10, lastTimings_.glyphMisses);
  }
  textplayer::transportSend(textplayer::MessageType::Displayed, sessionId_, lastPushSeq_, body.data(),
                            textplayer::displayedBytes(profileFlags_));
}

void TextPlayerActivity::sendError(const uint32_t objectId, const uint32_t code) {
  std::array<uint8_t, 4> body{};
  textplayer::writeLe32(body.data(), code);
  textplayer::transportSend(textplayer::MessageType::Error, sessionId_, objectId, body.data(), body.size());
}

void TextPlayerActivity::failSession(const uint32_t objectId, const uint32_t code) {
  if (cache_ && cache_->hasCandidate()) {
    cache_->abortCandidate();
  }
  assembler_.reset();
  fonts_.abortUpload();
  pageReceiveWindow_.reset();
  pagePending_.store(false, std::memory_order_release);
  state_.store(SessionState::Error, std::memory_order_release);
  closeObject(true);
  sendError(objectId, code);
  if (!hasDisplayedPage_) {
    requestUpdate();
  }
}

void TextPlayerActivity::resetSession() {
  if (cache_) {
    cache_->resetSession(1);
  }
  assembler_.reset();
  sessionId_ = 0;
  pendingRequestId_ = 0;
  lastRequestId_ = 0;
  pendingPushSeq_ = 0;
  pendingPageId_ = 0;
  lastPushSeq_ = 0;
  lastPageId_ = 0;
  lastObjectCrc_ = 0;
  latestGlyphSeq_ = 0;
  hasDisplayedPage_ = false;
  sdGlyphs_ = false;
  preparePages_ = false;
  preparingPage_ = false;
  prepareRemaining_ = 0;
  lastDisplayMs_ = lastPressMs_ = millis();
  turnIntent_.reset();
  hotGlyphs_.reset();
  fonts_.abortUpload();
  profileFlags_ = 0;
  pageBurst_ = false;
  pageReceiveWindow_.reset();
  pairingPin_.reset();
  pagePending_.store(false, std::memory_order_release);
  state_.store(SessionState::Idle, std::memory_order_release);
  closeObject(true);
}

void TextPlayerActivity::closeObject(const bool remove) {
  if (objectOpen_) {
    objectFile_.close();
    objectOpen_ = false;
  }
  if (remove && objectOnDisk_) {
    Storage.remove(OBJECT_PATH);
    objectOnDisk_ = false;
  }
}

bool TextPlayerActivity::readExact(void* data, const size_t length) {
  uint8_t* output = static_cast<uint8_t*>(data);
  size_t read = 0;
  while (read < length) {
    const int count = objectFile_.read(output + read, length - read);
    if (count <= 0) {
      return false;
    }
    read += static_cast<size_t>(count);
  }
  return true;
}

bool TextPlayerActivity::seekObject(const size_t offset) {
  if (!objectOpen_) {
    if (!Storage.openFileForRead("TXTP", OBJECT_PATH, objectFile_)) {
      return false;
    }
    objectOpen_ = true;
  }
  return objectFile_.seek(offset);
}

void TextPlayerActivity::render(RenderLock&&) {
  if (pagePending_.load(std::memory_order_acquire)) {
    const uint32_t drawStart = profileFlags_ ? millis() : 0;
    bool drawn = false;
    switch (pageKind_) {
      case PageKind::SdGlyphs:
        drawn = provisionalFrame_;
        break;
      case PageKind::LegacyBitmap:
        drawn = renderBitmap();
        break;
      case PageKind::LegacyGrid:
        drawn = renderGrid();
        break;
    }
    const uint32_t refreshStart = profileFlags_ ? millis() : 0;
    if (preparePages_ && pendingRequestId_ != 0 && !turnIntent_.claim(requestIntent_)) {
      finishWithoutDisplay(pendingPushSeq_);
      return;
    }
    if (drawn) {
      renderer.displayBuffer();
    }
    if (profileFlags_) {
      const uint32_t refreshEnd = millis();
      currentTimings_.renderQueueMs = drawStart - renderQueuedMs_;
      currentTimings_.drawMs = refreshStart - drawStart;
      currentTimings_.refreshMs = refreshEnd - refreshStart;
      if (pendingRequestId_ != 0 && (profileFlags_ & textplayer::SESSION_GLYPH_TURN_TIMINGS) != 0) {
        currentTimings_.turnMs = refreshEnd - turnStartedMs_;
      }
    }
    completeRenderedPage(drawn && renderer.displayCommitted());
    return;
  }
  if (provisionalFrame_ && !pairingPin_) return;
  if (!hasDisplayedPage_ || state_.load(std::memory_order_acquire) == SessionState::Error) {
    renderStatus();
  }
}

void TextPlayerActivity::renderStatus() {
  provisionalFrame_ = false;
  renderer.clearScreen();
  const auto& metrics = UITheme::getInstance().getMetrics();
  GUI.drawHeader(renderer, Rect{0, metrics.topPadding, renderer.getScreenWidth(), metrics.headerHeight},
                 tr(STR_TEXT_PLAYER_TITLE));
  Rect safe = UITheme::getInstance().getScreenSafeArea(renderer, true);
  safe.y += metrics.headerHeight + metrics.verticalSpacing;
  safe.height -= metrics.headerHeight + metrics.verticalSpacing;
  UITheme::drawCenteredWrappedText(renderer, safe, UI_12_FONT_ID, statusText(), 3);
  if (pairingPin_) {
    char pin[7];
    snprintf(pin, sizeof(pin), "%06u", static_cast<unsigned>(*pairingPin_));
    const int y = safe.y + safe.height / 2 + metrics.menuRowHeight;
    UITheme::drawCenteredText(renderer, safe, CONTROL_18_FONT_ID, y, pin);
    const auto labels = mappedInput.mapLabels(tr(STR_BACK), tr(STR_CONFIRM), "", "");
    GUI.drawButtonHints(renderer, labels.btn1, labels.btn2, labels.btn3, labels.btn4);
  }
  renderer.displayBuffer();
}

const char* TextPlayerActivity::statusText() const {
  if (pairingPin_) {
    return tr(STR_TEXT_PLAYER_PAIRING);
  }
  switch (state_.load(std::memory_order_acquire)) {
    case SessionState::Idle:
    case SessionState::WaitingAnchor:
      return authorized_.load(std::memory_order_acquire) ? tr(STR_TEXT_PLAYER_CONNECTED) : tr(STR_TEXT_PLAYER_WAITING);
    case SessionState::Ready:
    case SessionState::WaitingPage:
    case SessionState::PagePending:
      return tr(STR_TEXT_PLAYER_CONNECTED);
    case SessionState::BluetoothError:
      return tr(STR_TEXT_PLAYER_BLE_ERROR);
    case SessionState::MemoryError:
      return tr(STR_TEXT_PLAYER_MEMORY_ERROR);
    case SessionState::Error:
      return tr(STR_TEXT_PLAYER_ERROR);
  }
  return tr(STR_TEXT_PLAYER_ERROR);
}

bool TextPlayerActivity::renderGrid() {
  renderer.clearScreen();
  uint64_t cursor = 0;
  for (size_t index = 0; index < gridPage_->size(); ++index) {
    const int16_t token = gridPage_->tokens()[index];
    const bool asciiRun = token == textplayer::GRID_ASCII_RUN;
    int16_t glyphToken = token;
    uint16_t advanceCells = 1;
    if (asciiRun) {
      if (index + 2 >= gridPage_->size()) {
        return false;
      }
      glyphToken = gridPage_->tokens()[++index];
      const int16_t span = gridPage_->tokens()[++index];
      if (glyphToken < 0 || span <= 0) {
        return false;
      }
      advanceCells = static_cast<uint16_t>(span);
    }
    if (glyphToken >= 0) {
      int32_t anchorX = 0;
      int32_t anchorY = 0;
      if (!layout_.anchor(cursor, anchorX, anchorY)) {
        return false;
      }
      textplayer::GlyphMetrics metrics;
      size_t dataLength = 0;
      const uint8_t* data = cache_->candidateData(glyphToken, dataLength, &metrics);
      if (data == nullptr || dataLength != static_cast<size_t>(metrics.rowStride) * metrics.height) {
        return false;
      }
      for (uint16_t y = 0; y < metrics.height; ++y) {
        const uint8_t* row = data + static_cast<size_t>(y) * metrics.rowStride;
        for (uint16_t x = 0; x < metrics.width; ++x) {
          if ((row[x / 8] & (0x80 >> (x % 8))) != 0) {
            renderer.drawPixel(anchorX + metrics.offsetX + x, anchorY + metrics.offsetY + y, true);
          }
        }
      }
    }
    const bool advanced =
        asciiRun ? textplayer::GridLayout::advanceCells(advanceCells, layout_.columns, layout_.rows, cursor)
                 : textplayer::GridLayout::advance(token, layout_.columns, layout_.rows, cursor);
    if (!advanced) {
      return false;
    }
  }
  return true;
}

bool TextPlayerActivity::renderBitmap() {
  if (!seekObject(bitmapDataOffset_)) {
    return false;
  }
  renderer.clearScreen();
  for (uint16_t y = 0; y < bitmapHeight_; ++y) {
    if (!readExact(bitmapRow_.data(), bitmapStride_)) {
      return false;
    }
    for (uint16_t x = 0; x < bitmapWidth_; ++x) {
      if ((bitmapRow_[x / 8] & (0x80 >> (x % 8))) != 0) {
        renderer.drawPixel(x, y, true);
      }
    }
  }
  return true;
}

void TextPlayerActivity::completeRenderedPage(const bool committed) {
  const bool legacyGrid = pageKind_ == PageKind::LegacyGrid;
  if (!committed || (legacyGrid && !cache_->commitDisplayed(gridPage_->glyphSlots(), gridPage_->glyphCount()))) {
    if (legacyGrid && cache_->hasCandidate()) {
      cache_->abortCandidate();
    }
    hasDisplayedPage_ = false;
    pagePending_.store(false, std::memory_order_release);
    state_.store(SessionState::Error, std::memory_order_release);
    closeObject(true);
    sendError(pendingPushSeq_, 31);
    requestUpdate();
    return;
  }

  lastPushSeq_ = pendingPushSeq_;
  lastPageId_ = pendingPageId_;
  lastObjectCrc_ = pendingObjectCrc_;
  if (profileFlags_) {
    lastTimings_ = currentTimings_;
  }
  provisionalFrame_ = false;
  hasDisplayedPage_ = true;
  lastDisplayMs_ = millis();
  prepareRemaining_ = preparePages_ ? 2 : 0;
  pendingRequestId_ = 0;
  pagePending_.store(false, std::memory_order_release);
  state_.store(SessionState::Ready, std::memory_order_release);
  closeObject(true);
  sendDisplayed();
}
