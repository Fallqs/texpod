#pragma once

#include <GlyphCache.h>
#include <GlyphPageStream.h>
#include <GridPage.h>
#include <HotGlyphCache.h>
#include <SdFontStore.h>
#include <Transport.h>
#include <TurnIntent.h>
#include <WireProtocol.h>

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>

#include "activities/Activity.h"

using textplayer::AssemblyResult;
using textplayer::TransportEvent;

class TextPlayerActivity final : public Activity {
 public:
  TextPlayerActivity(GfxRenderer& renderer, MappedInputManager& mappedInput, bool returnToHome = false)
      : Activity("TextPlayer", renderer, mappedInput), returnToHome_(returnToHome) {}

  void onEnter() override;
  void onExit() override;
  void prepareForSleep() override;
  void loop() override;
  void render(RenderLock&&) override;
  bool preventAutoSleep() override { return fonts_.uploading(); }
  bool keepsBluetoothAlive() const override { return false; }
  bool hasCompleteFramebuffer() const override { return !provisionalFrame_; }
  bool handleForcedRefresh() override;

 private:
#if CONFIG_IDF_TARGET_ESP32C3
  static constexpr size_t MAX_SLOTS = 256;
  static constexpr size_t GLYPH_POOL_BYTES = 16 * 1024;
#else
  static constexpr size_t MAX_SLOTS = 512;
  static constexpr size_t GLYPH_POOL_BYTES = 32 * 1024;
#endif
  static constexpr size_t MAX_TOKENS = 2048;
  static constexpr size_t MAX_GLYPH_BYTES = 1024;
  static constexpr size_t MAX_BITMAP_ROW_BYTES = 128;
  static constexpr size_t GRID_HEADER_BYTES = 32;
  static constexpr size_t GLYPH_DESCRIPTOR_BYTES = 20;
  static constexpr size_t BITMAP_HEADER_BYTES = 16;
  static constexpr size_t SESSION_FLAGS_BYTES = textplayer::START_SESSION_BYTES + 1;
  static constexpr size_t SD_SESSION_BYTES = SESSION_FLAGS_BYTES + textplayer::FontGeneration{}.size();

  enum class PageKind : uint8_t { LegacyGrid, LegacyBitmap, SdGlyphs };
  enum class SinkKind : uint8_t { Control, LegacyPage, GlyphPage, FontChunk };

  struct ObjectSink {
    TextPlayerActivity& owner;
    SinkKind kind;
    bool write(const uint8_t* data, size_t length);
    bool begin(uint32_t requestId, uint32_t pageId);
    bool draw(uint8_t face, uint16_t id, int32_t x, int32_t y);
  };
  struct PageTimings {
    uint32_t receiveMs = 0;
    uint32_t prepareMs = 0;
    uint32_t renderQueueMs = 0;
    uint32_t drawMs = 0;
    uint32_t refreshMs = 0;
    uint32_t arrivalSpanMs = 0;
    uint32_t stagingUs = 0;
    uint32_t lastArrivalToStagedMs = 0;
    uint16_t firstIntervalUnits = 0;
    uint16_t lastIntervalUnits = 0;
    uint32_t turnMs = textplayer::NO_TURN_TIMING;
    uint32_t glyphReadUs = 0;
    uint16_t glyphHits = 0;
    uint16_t glyphMisses = 0;
  };

  bool loadGridTokens(size_t tokenCount);
  void queuePage(const AssemblyResult& result, uint32_t requestId, uint32_t pageId, PageKind kind);

  uint8_t profileFlags_ = 0;
  bool pageBurst_ = false;
  bool sdGlyphs_ = false;
  textplayer::PageReceiveWindow pageReceiveWindow_;
  uint32_t firstArrivalMs_ = 0;
  uint32_t receiveStartMs_ = 0;
  uint32_t receiveEndMs_ = 0;
  uint32_t renderQueuedMs_ = 0;
  uint32_t turnStartedMs_ = 0;
  PageTimings currentTimings_{};
  PageTimings lastTimings_{};
  static constexpr char OBJECT_PATH[] = "/.crosspoint/text-player-page.tmp";

  enum class SessionState : uint8_t {
    Idle,
    WaitingAnchor,
    Ready,
    WaitingPage,
    PagePending,
    BluetoothError,
    MemoryError,
    Error
  };

  bool allocateLegacyResources();
  void releaseLegacyResources();
  void releaseResources();
  void handleTransportEvent(const TransportEvent& event);
  void handleFrame(const TransportEvent& event);
  bool beginObject(const textplayer::FrameView& frame);
  void handleObject(const AssemblyResult& result);
  void handleHello(const AssemblyResult& result);
  void handleStartSession(const AssemblyResult& result);
  void handlePageGrid(const AssemblyResult& result);
  void handlePageBitmap(const AssemblyResult& result);
  void handleGlyphPage(const AssemblyResult& result);
  void handleFontObject(const AssemblyResult& result);
  void sendFontStatus(uint32_t objectId, textplayer::FontStatus status, uint32_t offset);
  void handleNoPage(const AssemblyResult& result);
  bool readPageIdentity(const AssemblyResult& result, uint32_t& requestId, uint32_t& pageId);
  bool validPageRequest(uint32_t requestId) const;
  bool duplicatePage(const AssemblyResult& result, uint32_t pageId);
  void requestPage(int offset, bool prepare = false);
  void serviceTurns();
  bool canceledTurn() const;
  void finishWithoutDisplay(uint32_t objectId);
  void sendDisplayed();
  void sendError(uint32_t objectId, uint32_t code);
  void failSession(uint32_t objectId, uint32_t code);
  void resetSession();
  void closeObject(bool remove);
  bool readExact(void* data, size_t length);
  bool seekObject(size_t offset);
  void renderStatus();
  bool renderGrid();
  bool renderBitmap();
  void completeRenderedPage(bool committed);
  bool inputReleaseBarrier();
  const char* statusText() const;

  const bool returnToHome_;
  std::unique_ptr<textplayer::GlyphCache<MAX_SLOTS>> cache_;
  std::unique_ptr<uint8_t[]> glyphPool_;
  std::unique_ptr<textplayer::GridPage<MAX_TOKENS>> gridPage_;
  std::unique_ptr<textplayer::GlyphBinding[]> bindings_;
  std::array<uint8_t, textplayer::MAX_FONT_RECORD_BYTES> glyphScratch_{};
  std::array<uint8_t, MAX_BITMAP_ROW_BYTES> bitmapRow_{};
  textplayer::SdFontStore fonts_;
  textplayer::HotGlyphCache<MAX_SLOTS> hotGlyphs_;
  textplayer::GlyphPageStream glyphStream_;

  textplayer::ObjectAssembler assembler_;
  HalFile objectFile_;
  std::array<uint8_t, SD_SESSION_BYTES> control_{};
  size_t controlBytes_ = 0;
  SinkKind sinkKind_ = SinkKind::Control;
  bool objectOnDisk_ = false;
  bool objectOpen_ = false;
  bool releaseBarrier_ = false;
  bool hasDisplayedPage_ = false;
  bool provisionalFrame_ = false;
  bool duplicateGlyphPage_ = false;
  bool glyphReadFailed_ = false;
  uint32_t glyphPushSeq_ = 0;
  PageKind pageKind_ = PageKind::LegacyGrid;

  std::atomic<SessionState> state_{SessionState::Idle};
  std::atomic<bool> pagePending_{false};
  std::atomic<bool> authorized_{false};
  textplayer::TurnIntent turnIntent_;
  textplayer::TurnIntent::Snapshot requestIntent_ = 0;
  bool preparePages_ = false;
  bool preparingPage_ = false;
  uint8_t prepareRemaining_ = 0;
  uint32_t lastPressMs_ = 0;
  uint32_t lastDisplayMs_ = 0;
  uint32_t latestGlyphSeq_ = 0;
  uint32_t sessionId_ = 0;
  std::atomic<uint32_t> pendingRequestId_{0};
  uint32_t lastRequestId_ = 0;
  uint32_t pendingPushSeq_ = 0;
  uint32_t pendingPageId_ = 0;
  uint32_t lastPushSeq_ = 0;
  uint32_t lastPageId_ = 0;
  uint32_t lastObjectCrc_ = 0;
  uint32_t pendingObjectCrc_ = 0;
  std::optional<uint32_t> pairingPin_;
  uint32_t lastFragmentMs_ = 0;
  size_t bindingCount_ = 0;
  size_t bitmapDataOffset_ = 0;
  uint16_t bitmapStride_ = 0;
  uint16_t bitmapWidth_ = 0;
  uint16_t bitmapHeight_ = 0;
  uint16_t negotiatedSlots_ = 0;
  uint16_t negotiatedTokens_ = 0;
  uint32_t negotiatedPoolBytes_ = 0;
  textplayer::GridLayout layout_{};
};
