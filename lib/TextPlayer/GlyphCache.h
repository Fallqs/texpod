#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>

namespace textplayer {

struct GlyphMetrics {
  uint16_t width = 0;
  uint16_t height = 0;
  uint16_t rowStride = 0;
  int16_t offsetX = 0;
  int16_t offsetY = 0;
};

struct GlyphBinding {
  uint16_t slotId;
  uint32_t poolOffset;
  uint32_t byteLength;
  GlyphMetrics metrics{};
};

template <size_t MaxSlots>
class GlyphCache {
  static_assert(MaxSlots > 0 && MaxSlots <= 32768);

 public:
  GlyphCache(uint8_t* pool, const size_t poolSize) : pool(pool), poolSize(poolSize) { resetSession(1); }

  void resetSession(const uint32_t initialEpoch) {
    slots.fill(Slot{});
    candidateActive = false;
    candidateBytesWritten = false;
    epoch = initialEpoch;
    revision = 0;
    useClock = 0;
    usable = pool != nullptr && poolSize > 0;
  }

  bool prepare(const uint32_t baseEpoch, const uint32_t baseRevision, const uint32_t targetEpoch,
               const uint32_t targetRevision, const bool reset, const GlyphBinding* bindings, const size_t bindingCount,
               const uint16_t* slotTokens, const size_t tokenCount) {
    if (!usable || candidateActive || baseEpoch != epoch || baseRevision != revision ||
        tokenCount > std::numeric_limits<uint32_t>::max() || (bindingCount > 0 && bindings == nullptr) ||
        (tokenCount > 0 && slotTokens == nullptr)) {
      return false;
    }
    if (reset) {
      if (tokenCount == 0 || baseEpoch == std::numeric_limits<uint32_t>::max() || targetEpoch != baseEpoch + 1 ||
          targetRevision != 1) {
        return false;
      }
    } else if (targetEpoch != baseEpoch || baseRevision == std::numeric_limits<uint32_t>::max() ||
               targetRevision != baseRevision + 1) {
      return false;
    }

    candidateForSlot.fill(-1);
    referenced.fill(false);
    evicted.fill(false);
    lastTokenPosition.fill(0);
    candidateBindingCount = 0;
    candidateBytesWritten = false;

    for (size_t index = 0; index < tokenCount; ++index) {
      const uint16_t slotId = slotTokens[index];
      if (slotId >= MaxSlots) {
        return false;
      }
      referenced[slotId] = true;
      lastTokenPosition[slotId] = static_cast<uint32_t>(index);
    }

    for (size_t index = 0; index < bindingCount; ++index) {
      const GlyphBinding& binding = bindings[index];
      if (binding.slotId >= MaxSlots || binding.byteLength == 0 || binding.poolOffset > poolSize ||
          binding.byteLength > poolSize - binding.poolOffset || !referenced[binding.slotId] ||
          candidateBindingCount >= MaxSlots || candidateForSlot[binding.slotId] >= 0) {
        return false;
      }
      candidateForSlot[binding.slotId] = static_cast<int16_t>(candidateBindingCount);
      candidateBindings[candidateBindingCount++] = CandidateBinding{binding, false};
    }

    for (size_t left = 0; left < candidateBindingCount; ++left) {
      for (size_t right = left + 1; right < candidateBindingCount; ++right) {
        if (overlaps(candidateBindings[left].binding, candidateBindings[right].binding)) {
          return false;
        }
      }
    }

    for (size_t slotId = 0; slotId < MaxSlots; ++slotId) {
      const Slot& slot = slots[slotId];
      if (!slot.occupied) {
        continue;
      }
      if (reset || candidateForSlot[slotId] >= 0) {
        evicted[slotId] = true;
      }
      if (!reset) {
        for (size_t index = 0; index < candidateBindingCount; ++index) {
          const GlyphBinding& binding = candidateBindings[index].binding;
          if (binding.slotId == slotId) {
            continue;
          }
          if (rangesOverlap(slot.poolOffset, slot.byteLength, binding.poolOffset, binding.byteLength)) {
            if (referenced[slotId]) {
              return false;
            }
            evicted[slotId] = true;
          }
        }
      }
      if (referenced[slotId] && evicted[slotId] && candidateForSlot[slotId] < 0) {
        return false;
      }
    }

    for (size_t index = 0; index < tokenCount; ++index) {
      const uint16_t slotId = slotTokens[index];
      if (candidateForSlot[slotId] < 0 && (reset || !slots[slotId].occupied || evicted[slotId])) {
        return false;
      }
    }

    pendingEpoch = targetEpoch;
    pendingRevision = targetRevision;
    candidateActive = true;
    return true;
  }

  bool writeCandidate(const uint16_t slotId, const uint8_t* data, const size_t byteLength) {
    if (!candidateActive || slotId >= MaxSlots || data == nullptr) {
      return false;
    }
    const int16_t index = candidateForSlot[slotId];
    if (index < 0) {
      return false;
    }
    CandidateBinding& candidate = candidateBindings[index];
    if (candidate.binding.byteLength != byteLength) {
      return false;
    }
    std::memcpy(pool + candidate.binding.poolOffset, data, byteLength);
    candidate.written = true;
    candidateBytesWritten = true;
    return true;
  }

  const uint8_t* candidateData(const uint16_t slotId, size_t& byteLength, GlyphMetrics* metrics = nullptr) const {
    byteLength = 0;
    if (!candidateActive || slotId >= MaxSlots) {
      return nullptr;
    }
    const int16_t index = candidateForSlot[slotId];
    if (index >= 0) {
      const CandidateBinding& candidate = candidateBindings[index];
      if (!candidate.written) {
        return nullptr;
      }
      if (metrics) *metrics = candidate.binding.metrics;
      byteLength = candidate.binding.byteLength;
      return pool + candidate.binding.poolOffset;
    }
    const Slot& slot = slots[slotId];
    if (!slot.occupied || evicted[slotId]) {
      return nullptr;
    }
    if (metrics) *metrics = slot.metrics;
    byteLength = slot.byteLength;
    return pool + slot.poolOffset;
  }

  bool commitDisplayed(const uint16_t* slotTokens, const size_t tokenCount) {
    if (!candidateActive || (tokenCount > 0 && slotTokens == nullptr)) {
      return false;
    }
    for (size_t index = 0; index < candidateBindingCount; ++index) {
      if (!candidateBindings[index].written) {
        return false;
      }
    }

    for (size_t slotId = 0; slotId < MaxSlots; ++slotId) {
      if (evicted[slotId]) {
        slots[slotId] = Slot{};
      }
    }
    for (size_t index = 0; index < candidateBindingCount; ++index) {
      const GlyphBinding& binding = candidateBindings[index].binding;
      slots[binding.slotId].occupied = true;
      slots[binding.slotId].poolOffset = binding.poolOffset;
      slots[binding.slotId].byteLength = binding.byteLength;
      slots[binding.slotId].metrics = binding.metrics;
      slots[binding.slotId].lastUsed = 0;
    }
    for (size_t index = 0; index < tokenCount; ++index) {
      const uint16_t slotId = slotTokens[index];
      if (lastTokenPosition[slotId] == index) {
        slots[slotId].lastUsed = ++useClock;
      }
    }
    candidateActive = false;
    candidateBytesWritten = false;
    epoch = pendingEpoch;
    revision = pendingRevision;
    return true;
  }

  void abortCandidate() {
    if (!candidateActive) {
      return;
    }
    if (candidateBytesWritten) {
      slots.fill(Slot{});
      usable = false;
    }
    candidateActive = false;
    candidateBytesWritten = false;
  }

  uint32_t getEpoch() const { return epoch; }
  uint32_t getRevision() const { return revision; }
  bool isUsable() const { return usable; }
  bool hasCandidate() const { return candidateActive; }

  size_t occupiedCount() const {
    size_t count = 0;
    for (const Slot& slot : slots) {
      if (slot.occupied) {
        ++count;
      }
    }
    return count;
  }

  bool isOccupied(const uint16_t slotId) const { return slotId < MaxSlots && slots[slotId].occupied; }
  uint32_t slotOffset(const uint16_t slotId) const { return slots[slotId].poolOffset; }
  uint32_t slotLength(const uint16_t slotId) const { return slots[slotId].byteLength; }
  uint64_t slotLastUsed(const uint16_t slotId) const { return slots[slotId].lastUsed; }
  uint8_t poolByte(const size_t offset) const { return pool[offset]; }

 private:
  struct Slot {
    bool occupied = false;
    uint32_t poolOffset = 0;
    uint32_t byteLength = 0;
    GlyphMetrics metrics{};
    uint64_t lastUsed = 0;
  };

  struct CandidateBinding {
    GlyphBinding binding;
    bool written;
  };

  static bool rangesOverlap(const uint32_t leftOffset, const uint32_t leftLength, const uint32_t rightOffset,
                            const uint32_t rightLength) {
    return leftOffset < rightOffset ? rightOffset - leftOffset < leftLength : leftOffset - rightOffset < rightLength;
  }

  static bool overlaps(const GlyphBinding& left, const GlyphBinding& right) {
    return rangesOverlap(left.poolOffset, left.byteLength, right.poolOffset, right.byteLength);
  }

  uint8_t* pool;
  size_t poolSize;
  std::array<Slot, MaxSlots> slots{};
  std::array<CandidateBinding, MaxSlots> candidateBindings{};
  std::array<int16_t, MaxSlots> candidateForSlot{};
  std::array<bool, MaxSlots> referenced{};
  std::array<bool, MaxSlots> evicted{};
  std::array<uint32_t, MaxSlots> lastTokenPosition{};
  size_t candidateBindingCount = 0;
  uint32_t pendingEpoch = 1;
  uint32_t pendingRevision = 0;
  uint32_t epoch = 1;
  uint32_t revision = 0;
  uint64_t useClock = 0;
  bool candidateActive = false;
  bool candidateBytesWritten = false;
  bool usable = false;
};

}  // namespace textplayer
