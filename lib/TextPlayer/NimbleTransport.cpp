#include "Transport.h"

#if FREEINK_CAP_BLE_HID_HOST

#include <Arduino.h>
#include <Logging.h>
#include <Memory.h>
#include <NimBLEDevice.h>

#if !CONFIG_BT_ENABLED || !CONFIG_BT_NIMBLE_ROLE_PERIPHERAL || !CONFIG_BT_NIMBLE_ROLE_BROADCASTER
#error "Texpod requires NimBLE Peripheral and Broadcaster support on BLE-enabled builds"
#endif

#include <algorithm>
#include <array>
#include <atomic>
#include <cstring>

#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"

namespace textplayer {
namespace {

constexpr char SERVICE_UUID[] = "6f2d4a3e-3f5d-4e8f-a7b2-c9d10e4f6a70";
constexpr char WRITE_UUID[] = "6f2d4a3e-3f5d-4e8f-a7b2-c9d10e4f6a71";
constexpr char EVENT_UUID[] = "6f2d4a3e-3f5d-4e8f-a7b2-c9d10e4f6a72";
constexpr UBaseType_t QUEUE_LENGTH = 8;
static_assert(PAGE_BURST_FRAMES + 2 <= QUEUE_LENGTH);

std::atomic<QueueHandle_t> eventQueue{nullptr};
std::atomic<uint16_t> peer{BLE_HS_CONN_HANDLE_NONE};
std::atomic<uint16_t> pairingPeer{BLE_HS_CONN_HANDLE_NONE};
std::atomic<bool> queueOverflow{false};
NimBLEServer* server = nullptr;
NimBLECharacteristic* eventCharacteristic = nullptr;

void post(TransportEvent& event) {
  QueueHandle_t queue = eventQueue.load(std::memory_order_acquire);
  if (queue != nullptr && xQueueSend(queue, &event, 0) != pdTRUE) {
    queueOverflow.store(true, std::memory_order_release);
  }
}

void postSimple(const TransportEventType type, const uint32_t value = 0) {
  TransportEvent event{};
  event.type = type;
  event.value = value;
  post(event);
}

class ServerCallbacks final : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server, NimBLEConnInfo& connInfo) override {
    peer.store(connInfo.getConnHandle(), std::memory_order_release);
    postSimple(TransportEventType::Connected);
    int result = 0;
    if (!NimBLEDevice::startSecurity(connInfo.getConnHandle(), &result)) {
      LOG_ERR("TXTP", "Security start failed: %d", result);
      server->disconnect(connInfo);
    }
  }

  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override {
    peer.store(BLE_HS_CONN_HANDLE_NONE, std::memory_order_release);
    pairingPeer.store(BLE_HS_CONN_HANDLE_NONE, std::memory_order_release);
    postSimple(TransportEventType::Disconnected);
  }

  void onConfirmPassKey(NimBLEConnInfo& connInfo, const uint32_t pin) override {
    pairingPeer.store(connInfo.getConnHandle(), std::memory_order_release);
    postSimple(TransportEventType::PairingRequest, pin);
  }

  void onAuthenticationComplete(NimBLEConnInfo& connInfo) override {
    pairingPeer.store(BLE_HS_CONN_HANDLE_NONE, std::memory_order_release);
    if (connInfo.isEncrypted() && connInfo.isAuthenticated()) {
      postSimple(TransportEventType::Authenticated, connInfo.getMTU());
    } else {
      server->disconnect(connInfo);
    }
  }
};

class WriteCharacteristic final : public NimBLECharacteristic {
 public:
  explicit WriteCharacteristic(NimBLEService* service)
      : NimBLECharacteristic(WRITE_UUID,
                             NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::WRITE_AUTHEN,
                             MAX_FRAME_BYTES, service) {}

 private:
  void writeEvent(const uint8_t* data, uint16_t length, NimBLEConnInfo& connInfo) override {
    if (!connInfo.isEncrypted() || !connInfo.isAuthenticated() || length > MAX_FRAME_BYTES) {
      postSimple(TransportEventType::Error);
      return;
    }
    TransportEvent event{};
    event.type = TransportEventType::Frame;
    event.length = length;
    event.value = millis();
    event.intervalUnits = connInfo.getConnInterval();
    if (length > 0) {
      std::memcpy(event.data, data, length);
    }
    post(event);
  }
};

ServerCallbacks serverCallbacks;

}  // namespace

bool transportStart() {
  if (NimBLEDevice::isInitialized()) {
    LOG_ERR("TXTP", "BLE already owned");
    return false;
  }
  QueueHandle_t queue = xQueueCreate(QUEUE_LENGTH, sizeof(TransportEvent));
  if (queue == nullptr) {
    LOG_ERR("TXTP", "OOM: event queue (%u bytes)", static_cast<unsigned>(QUEUE_LENGTH * sizeof(TransportEvent)));
    return false;
  }
  eventQueue.store(queue, std::memory_order_release);
  queueOverflow.store(false, std::memory_order_release);

  if (!NimBLEDevice::init("Texpod")) {
    LOG_ERR("TXTP", "BLE init failed");
    transportStop();
    return false;
  }
  NimBLEDevice::setSecurityAuth(true, true, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_YESNO);
  NimBLEDevice::setMTU(MAX_FRAME_BYTES + 3);
  server = NimBLEDevice::createServer();
  if (server == nullptr) {
    transportStop();
    return false;
  }
  server->setCallbacks(&serverCallbacks, false);
  NimBLEService* service = server->createService(SERVICE_UUID);
  if (service == nullptr) {
    transportStop();
    return false;
  }
  // NimBLEService owns this one characteristic until teardown; a local object would dangle.
  auto writeCharacteristic = makeUniqueNoThrow<WriteCharacteristic>(service);
  if (!writeCharacteristic) {
    LOG_ERR("TXTP", "OOM: write characteristic (%u bytes)", static_cast<unsigned>(sizeof(WriteCharacteristic)));
    transportStop();
    return false;
  }
  service->addCharacteristic(writeCharacteristic.release());
  eventCharacteristic = service->createCharacteristic(EVENT_UUID, NIMBLE_PROPERTY::NOTIFY, MAX_FRAME_BYTES);
  if (eventCharacteristic == nullptr) {
    transportStop();
    return false;
  }
  auto* advertising = NimBLEDevice::getAdvertising();
  if (advertising == nullptr || !advertising->addServiceUUID(SERVICE_UUID) || !advertising->setName("Texpod") ||
      !transportAdvertise()) {
    LOG_ERR("TXTP", "Unable to start GATT advertising");
    transportStop();
    return false;
  }
  LOG_INF("TXTP", "Texpod advertising");
  return true;
}

bool transportAdvertise() { return server != nullptr && NimBLEDevice::startAdvertising(); }

bool transportActive() { return eventQueue.load(std::memory_order_acquire) != nullptr; }

void transportStop() {
  QueueHandle_t queue = eventQueue.load(std::memory_order_acquire);
  if (queue == nullptr) return;
  if (NimBLEDevice::isInitialized()) {
    NimBLEDevice::stopAdvertising();
    if (!NimBLEDevice::deinit(false)) {
      LOG_ERR("TXTP", "BLE stop failed");
      return;
    }
  }
  NimBLEDevice::deinit(true);
  server = nullptr;
  eventCharacteristic = nullptr;
  peer.store(BLE_HS_CONN_HANDLE_NONE, std::memory_order_release);
  pairingPeer.store(BLE_HS_CONN_HANDLE_NONE, std::memory_order_release);
  eventQueue.store(nullptr, std::memory_order_release);
  vQueueDelete(queue);
}

bool transportPoll(TransportEvent& event) {
  QueueHandle_t queue = eventQueue.load(std::memory_order_acquire);
  if (queue == nullptr) {
    return false;
  }
  if (queueOverflow.exchange(false, std::memory_order_acq_rel)) {
    event = TransportEvent{};
    event.type = TransportEventType::Error;
    return true;
  }
  return xQueueReceive(queue, &event, 0) == pdTRUE;
}

bool transportSend(const MessageType type, const uint32_t sessionId, const uint32_t objectId, const uint8_t* body,
                   const uint32_t bodyLength) {
  const uint16_t handle = peer.load(std::memory_order_acquire);
  if (handle == BLE_HS_CONN_HANDLE_NONE || eventCharacteristic == nullptr || bodyLength > MAX_OBJECT_BYTES ||
      (bodyLength > 0 && body == nullptr)) {
    return false;
  }
  const auto conn = server->getPeerInfoByHandle(handle);
  const size_t limit = gattPayloadLimit(conn.getMTU());
  if (!conn.isEncrypted() || !conn.isAuthenticated() || limit < START_METADATA_SIZE) {
    return false;
  }

  std::array<uint8_t, MAX_FRAME_BYTES> frame{};
  const size_t firstBytes = std::min<size_t>(bodyLength, limit - START_METADATA_SIZE);
  const uint8_t firstFlags = FRAME_START | (firstBytes == bodyLength ? FRAME_END : 0);
  writeFrameHeader(frame.data(), type, firstFlags, sessionId, objectId, 0);
  writeLe16(frame.data() + FRAME_HEADER_SIZE, static_cast<uint16_t>(bodyLength));
  writeLe32(frame.data() + FRAME_HEADER_SIZE + 2, crc32(body, bodyLength));
  if (firstBytes > 0) {
    std::memcpy(frame.data() + FRAME_HEADER_SIZE + START_METADATA_SIZE, body, firstBytes);
  }
  const auto notify = [&](const size_t bytes) {
    for (int attempt = 0; attempt < 4 && peer.load(std::memory_order_acquire) == handle; ++attempt) {
      if (eventCharacteristic->notify(frame.data(), bytes, handle)) return true;
      vTaskDelay(1);
    }
    return false;
  };
  if (!notify(FRAME_HEADER_SIZE + START_METADATA_SIZE + firstBytes)) {
    return false;
  }

  uint32_t sent = firstBytes;
  while (sent < bodyLength) {
    const size_t chunk = std::min<size_t>(bodyLength - sent, limit);
    const uint8_t flags = sent + chunk == bodyLength ? FRAME_END : 0;
    writeFrameHeader(frame.data(), type, flags, sessionId, objectId, static_cast<uint16_t>(sent));
    std::memcpy(frame.data() + FRAME_HEADER_SIZE, body + sent, chunk);
    if (!notify(FRAME_HEADER_SIZE + chunk)) {
      return false;
    }
    sent += chunk;
  }
  return true;
}

bool transportPairingDecision(const bool accept) {
  const uint16_t handle = pairingPeer.exchange(BLE_HS_CONN_HANDLE_NONE, std::memory_order_acq_rel);
  if (handle == BLE_HS_CONN_HANDLE_NONE || peer.load(std::memory_order_acquire) != handle || server == nullptr) {
    return false;
  }
  const auto conn = server->getPeerInfoByHandle(handle);
  return conn.getMTU() != 0 && NimBLEDevice::injectConfirmPasskey(conn, accept);
}

}  // namespace textplayer

#else

namespace textplayer {
bool transportStart() { return false; }
bool transportAdvertise() { return false; }
bool transportActive() { return false; }
void transportStop() {}
bool transportPoll(TransportEvent&) { return false; }
bool transportSend(MessageType, uint32_t, uint32_t, const uint8_t*, uint32_t) { return false; }
bool transportPairingDecision(bool) { return false; }
}  // namespace textplayer

#endif
