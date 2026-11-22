#pragma once

#include <cstddef>
#include <cstdint>

#include "WireProtocol.h"

namespace textplayer {

enum class TransportEventType : uint8_t {
  Frame,
  Connected,
  Authenticated,
  Disconnected,
  PairingRequest,
  Error,
};

struct TransportEvent {
  TransportEventType type = TransportEventType::Error;
  uint16_t length = 0;
  uint16_t intervalUnits = 0;  // For Frame: negotiated interval in units of 1.25 ms.
  uint32_t value = 0;          // Frame arrival (millis), pairing PIN, or negotiated MTU, depending on type.
  uint8_t data[MAX_FRAME_BYTES]{};
};

bool transportStart();
bool transportAdvertise();
bool transportActive();
void transportStop();
bool transportPoll(TransportEvent& event);
bool transportSend(MessageType type, uint32_t sessionId, uint32_t objectId, const uint8_t* body, uint32_t bodyLength);
bool transportPairingDecision(bool accept);

}  // namespace textplayer
