#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>

namespace textplayer {

// The render task claims the offset immediately before starting a panel refresh.
// A press after that point is relative to the newly committed page.
class TurnIntent {
 public:
  static constexpr int MAX_STEPS = 127;
  using Snapshot = uint32_t;

  Snapshot snapshot() const { return intent_.load(); }
  static int steps(const Snapshot snapshot) { return static_cast<int>(snapshot & 0xff) - MAX_STEPS; }

  void press(const int direction) {
    auto current = snapshot();
    do {
      const int offset = std::clamp(steps(current) + direction, -MAX_STEPS, MAX_STEPS);
      if (intent_.compare_exchange_weak(current, next(current, offset))) return;
    } while (true);
  }

  bool claim(Snapshot snapshot) { return intent_.compare_exchange_strong(snapshot, next(snapshot, 0)); }

  void reset() { intent_.store(next(snapshot(), 0)); }

 private:
  static Snapshot next(const Snapshot snapshot, const int offset) {
    return ((snapshot + 0x100) & ~0xffu) | static_cast<uint32_t>(offset + MAX_STEPS);
  }

  std::atomic<Snapshot> intent_{MAX_STEPS};
};

}  // namespace textplayer
