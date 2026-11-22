package com.crossmux.textplayer.transport;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.location.LocationManager;
import android.util.Log;

import com.crossmux.textplayer.protocol.WireProtocol;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.UUID;

public final class BleTransport {
    public enum Stage { SCANNING, CONNECTING, PAIRING }

    public enum Failure { PERMISSION, NO_BLUETOOTH, BLUETOOTH_OFF, LOCATION_OFF, NOT_FOUND, CONNECT, PAIRING, DISCONNECTED }

    public interface Listener {
        void onStage(Stage stage);
        void onReady();
        void onFrame(byte[] frame, long callbackMs);
        void onObjectSent(WireProtocol.MessageType type, long sessionId, long objectId, long firstWriteMs,
                long lastCallbackMs, boolean highPrioritySubmitted);
        void onError(Failure failure);
    }

    private static final UUID SERVICE_UUID = UUID.fromString("6f2d4a3e-3f5d-4e8f-a7b2-c9d10e4f6a70");
    private static final UUID WRITE_UUID = UUID.fromString("6f2d4a3e-3f5d-4e8f-a7b2-c9d10e4f6a71");
    private static final UUID EVENT_UUID = UUID.fromString("6f2d4a3e-3f5d-4e8f-a7b2-c9d10e4f6a72");
    private static final UUID CCC_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String TAG = "TexpodBLE";
    private static final long SCAN_TIMEOUT_MILLIS = 15_000;
    private static final long CONNECT_TIMEOUT_MILLIS = 20_000;
    private static final long PAIR_TIMEOUT_MILLIS = 60_000;
    private static final long PAGE_PRIORITY_IDLE_MILLIS = 2_000;
    private static final long WRITE_PROGRESS_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_OUTGOING_OBJECTS = 4;

    private static final class Outgoing {
        final WireProtocol.MessageType type;
        final long sessionId;
        final long objectId;
        final ArrayDeque<byte[]> frames;
        final boolean bulk;
        final boolean noResponse;
        final PageWriteWindow window;
        boolean highPrioritySubmitted;

        Outgoing(WireProtocol.MessageType type, long sessionId, long objectId, ArrayDeque<byte[]> frames,
                boolean pageBurst, boolean supportsNoResponse) {
            this.type = type;
            this.sessionId = sessionId;
            this.objectId = objectId;
            this.frames = frames;
            boolean fontChunk = type == WireProtocol.MessageType.FONT_CHUNK;
            boolean burst = type.isPage() && pageBurst;
            bulk = type.isPage() || fontChunk;
            noResponse = burst || (fontChunk && supportsNoResponse);
            window = burst || fontChunk ? new PageWriteWindow() : null;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Outgoing> outgoing = new ArrayDeque<>();
    private final BluetoothAdapter adapter;
    private final TrafficMonitor traffic;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private Outgoing current;
    private byte[] writingFrame;
    private long writeStartedMs;
    private long firstWriteMs;
    private boolean ready;
    private boolean stopped;
    private boolean notificationsEnabled;
    private boolean mtuRequested;
    private Stage stage;
    private static final int DEFAULT_ATT_MTU = 23;
    private int attMtu = DEFAULT_ATT_MTU;
    private int payloadLimit = WireProtocol.gattPayloadLimit(DEFAULT_ATT_MTU);

    public int getAttMtu() {
        return attMtu;
    }

    public int getFrameBytes() {
        return payloadLimit + WireProtocol.HEADER_SIZE;
    }

    public int frameCount(int bodyBytes) {
        return WireProtocol.frameCount(bodyBytes, payloadLimit);
    }

    private final Runnable stageTimeout = () -> fail(switch (stage) {
        case SCANNING -> Failure.NOT_FOUND;
        case CONNECTING -> Failure.CONNECT;
        case PAIRING -> Failure.PAIRING;
    });

    private void enterStage(Stage next, long timeoutMillis) {
        stage = next;
        listener.onStage(next);
        handler.removeCallbacks(stageTimeout);
        handler.postDelayed(stageTimeout, timeoutMillis);
    }

    public BleTransport(Context context, Listener listener, TrafficMonitor traffic) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.traffic = traffic;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }

    public void start() {
        if (!hasPermissions()) {
            fail(Failure.PERMISSION);
            return;
        }
        if (adapter == null || !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            fail(Failure.NO_BLUETOOTH);
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                fail(Failure.BLUETOOTH_OFF);
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
                fail(Failure.LOCATION_OFF);
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(bondReceiver,
                        new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                        Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(bondReceiver,
                        new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                fail();
                return;
            }
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            enterStage(Stage.SCANNING, SCAN_TIMEOUT_MILLIS);
        } catch (SecurityException failure) {
            fail(Failure.PERMISSION);
        } catch (IllegalStateException failure) {
            fail();
        }
    }

    public void stop() {
        stopped = true;
        ready = false;
        handler.removeCallbacksAndMessages(null);
        outgoing.clear();
        current = null;
        if (writingFrame != null) {
            traffic.unfinishedWrite(writingFrame, SystemClock.elapsedRealtime(), TrafficMonitor.NO_CALLBACK);
            writingFrame = null;
        }
        try {
            context.unregisterReceiver(bondReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            if (scanner != null) {
                scanner.stopScan(scanCallback);
            }
        } catch (SecurityException ignored) {
        }
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (SecurityException ignored) {
            }
            try {
                gatt.close();
            } catch (SecurityException ignored) {
            }
        }
        gatt = null;
        writeCharacteristic = null;
    }

    public boolean setMaxFrameBytes(int maxFrameBytes) {
        if (maxFrameBytes < WireProtocol.HEADER_SIZE + WireProtocol.START_METADATA_SIZE
                || maxFrameBytes > WireProtocol.MAX_FRAME_BYTES) {
            return false;
        }
        payloadLimit = Math.min(payloadLimit, maxFrameBytes - WireProtocol.HEADER_SIZE);
        return true;
    }

    private boolean pageBurst;

    public boolean negotiatePageBurst(int capabilities) {
        pageBurst = (capabilities & WireProtocol.CAP_PAGE_BURST) != 0 && supportsNoResponse();
        return pageBurst;
    }

    private boolean supportsNoResponse() {
        return writeCharacteristic != null
                && (writeCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
    }

    public boolean acceptPageCredit(long sessionId, long objectId, int stagedBytes) {
        if (current == null || current.window == null || current.sessionId != sessionId
                || current.objectId != objectId || !current.window.acceptCredit(stagedBytes)) {
            return false;
        }
        if (writingFrame == null) {
            writeNextFrame();
        }
        return true;
    }

    public boolean send(WireProtocol.MessageType type, long sessionId, long objectId, byte[] body) {
        if (!ready || outgoing.size() >= MAX_OUTGOING_OBJECTS) {
            return false;
        }
        ArrayDeque<byte[]> frames = new ArrayDeque<>(
                WireProtocol.createFrames(type, sessionId, objectId, body, payloadLimit));
        outgoing.add(new Outgoing(type, sessionId, objectId, frames, pageBurst, supportsNoResponse()));
        if (current == null) {
            writeNextObject();
        }
        return true;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handler.post(() -> {
                if (stopped || scanner == null) {
                    return;
                }
                try {
                    scanner.stopScan(scanCallback);
                    scanner = null;
                    enterStage(Stage.CONNECTING, CONNECT_TIMEOUT_MILLIS);
                    gatt = result.getDevice().connectGatt(context, false, gattCallback,
                            BluetoothDevice.TRANSPORT_LE);
                    if (gatt == null) {
                        fail();
                    }
                } catch (SecurityException | IllegalStateException failure) {
                    fail();
                }
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            handler.post(() -> fail());
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
            handler.post(() -> {
                if (!isCurrent(bluetoothGatt)) {
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail();
                    return;
                }
                try {
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        if (!bluetoothGatt.discoverServices()) {
                            fail();
                        }
                    } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        fail();
                    }
                } catch (SecurityException failure) {
                    fail(Failure.PERMISSION);
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
            handler.post(() -> {
                if (!isCurrent(bluetoothGatt)) {
                    return;
                }
                try {
                    android.bluetooth.BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
                    BluetoothGattCharacteristic event = service == null ? null : service.getCharacteristic(EVENT_UUID);
                    writeCharacteristic = service == null ? null : service.getCharacteristic(WRITE_UUID);
                    BluetoothGattDescriptor descriptor = event == null ? null : event.getDescriptor(CCC_UUID);
                    if (status != BluetoothGatt.GATT_SUCCESS || event == null || writeCharacteristic == null
                            || descriptor == null || !bluetoothGatt.setCharacteristicNotification(event, true)
                            || !descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            || !bluetoothGatt.writeDescriptor(descriptor)) {
                        fail();
                    }
                } catch (SecurityException failure) {
                    fail();
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt bluetoothGatt, BluetoothGattDescriptor descriptor,
                int status) {
            handler.post(() -> {
                if (!isCurrent(bluetoothGatt)) {
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail();
                    return;
                }
                notificationsEnabled = true;
                try {
                    BluetoothDevice device = bluetoothGatt.getDevice();
                    int bondState = device.getBondState();
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        requestMtuIfBonded(bluetoothGatt);
                        return;
                    }
                    if (stage != Stage.PAIRING) {
                        enterStage(Stage.PAIRING, PAIR_TIMEOUT_MILLIS);
                    }
                    if (bondState == BluetoothDevice.BOND_NONE && !device.createBond()
                            && device.getBondState() == BluetoothDevice.BOND_NONE) {
                        fail(Failure.PAIRING);
                    }
                } catch (SecurityException failure) {
                    fail(Failure.PERMISSION);
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt bluetoothGatt, int mtu, int status) {
            handler.post(() -> {
                if (!isCurrent(bluetoothGatt) || !mtuRequested || ready) {
                    return;
                }
                finishSetup(status == BluetoothGatt.GATT_SUCCESS ? mtu : DEFAULT_ATT_MTU);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic, int status) {
            long callbackMs = SystemClock.elapsedRealtime();
            handler.post(() -> {
                if (!isCurrent(bluetoothGatt) || !WRITE_UUID.equals(characteristic.getUuid()) || writingFrame == null) {
                    return;
                }
                traffic.written(writingFrame, writeStartedMs, callbackMs, SystemClock.elapsedRealtime(), status);
                writingFrame = null;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail();
                    return;
                }
                onWriteComplete(callbackMs);
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic) {
            long callbackMs = SystemClock.elapsedRealtime();
            byte[] value = characteristic.getValue();
            if (value == null || !EVENT_UUID.equals(characteristic.getUuid())) {
                return;
            }
            byte[] frame = value.clone();
            handler.post(() -> {
                if (isCurrent(bluetoothGatt) && ready) {
                    traffic.received(frame, callbackMs, SystemClock.elapsedRealtime());
                    listener.onFrame(frame, callbackMs);
                }
            });
        }
    };

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (stopped || gatt == null || !gatt.getDevice().equals(device)) {
                return;
            }
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            switch (state) {
                case BluetoothDevice.BOND_BONDING:
                    if (!ready && stage != Stage.PAIRING) {
                        enterStage(Stage.PAIRING, PAIR_TIMEOUT_MILLIS);
                    }
                    break;
                case BluetoothDevice.BOND_BONDED:
                    requestMtuIfBonded(gatt);
                    break;
                case BluetoothDevice.BOND_NONE:
                    fail(Failure.PAIRING);
                    break;
            }
        }
    };

    private boolean isCurrent(BluetoothGatt bluetoothGatt) {
        return !stopped && bluetoothGatt != null && bluetoothGatt == gatt;
    }

    private void requestMtuIfBonded(BluetoothGatt bluetoothGatt) {
        if (!isCurrent(bluetoothGatt) || !notificationsEnabled || mtuRequested) {
            return;
        }
        try {
            if (bluetoothGatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {
                mtuRequested = true;
                if (!bluetoothGatt.requestMtu(WireProtocol.MAX_FRAME_BYTES + 3)) {
                    finishSetup(DEFAULT_ATT_MTU);
                }
            }
        } catch (SecurityException failure) {
            fail(Failure.PERMISSION);
        }
    }

    private void finishSetup(int mtu) {
        attMtu = mtu;
        payloadLimit = WireProtocol.gattPayloadLimit(mtu);
        if (payloadLimit < WireProtocol.START_METADATA_SIZE) {
            fail(Failure.CONNECT);
            return;
        }
        handler.removeCallbacks(stageTimeout);
        ready = true;
        listener.onReady();
    }

    private long priorityIdleDeadlineMs;
    private boolean highPrioritySubmitted;
    private final Runnable priorityIdle = this::restoreBalancedPriority;

    private boolean requestPriority(int priority) {
        if (!ready || gatt == null) {
            return false;
        }
        try {
            return gatt.requestConnectionPriority(priority);
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private void beginPagePriority() {
        if (highPrioritySubmitted) {
            return;
        }
        highPrioritySubmitted = requestPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        if (highPrioritySubmitted) {
            extendPagePriority();
            handler.postAtTime(priorityIdle, priorityIdleDeadlineMs);
        }
    }

    private void extendPagePriority() {
        if (highPrioritySubmitted) {
            priorityIdleDeadlineMs = SystemClock.uptimeMillis() + PAGE_PRIORITY_IDLE_MILLIS;
        }
    }

    private void restoreBalancedPriority() {
        if (!highPrioritySubmitted) {
            return;
        }
        if (SystemClock.uptimeMillis() < priorityIdleDeadlineMs) {
            handler.postAtTime(priorityIdle, priorityIdleDeadlineMs);
            return;
        }
        highPrioritySubmitted = false;
        requestPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
    }

    private final Runnable writeProgressTimeout = this::fail;

    private void watchWriteProgress() {
        handler.removeCallbacks(writeProgressTimeout);
        handler.postDelayed(writeProgressTimeout, WRITE_PROGRESS_TIMEOUT_MILLIS);
    }

    private void writeNextObject() {
        current = outgoing.pollFirst();
        if (current == null) {
            return;
        }
        if (current.bulk) {
            beginPagePriority();
            current.highPrioritySubmitted = highPrioritySubmitted;
        }
        writeNextFrame();
        firstWriteMs = writeStartedMs;
    }

    private void onWriteComplete(long callbackMs) {
        if (current.bulk) {
            extendPagePriority();
        }
        if (!current.frames.isEmpty()) {
            // This covers both a pending write callback and waiting for a reader credit.
            watchWriteProgress();
            writeNextFrame();
            return;
        }
        handler.removeCallbacks(writeProgressTimeout);
        Outgoing sent = current;
        long startedMs = firstWriteMs;
        current = null;
        handler.post(() -> {
            if (!stopped) {
                listener.onObjectSent(sent.type, sent.sessionId, sent.objectId, startedMs, callbackMs,
                        sent.highPrioritySubmitted);
            }
        });
        writeNextObject();
    }

    private void writeNextFrame() {
        if (stopped || current == null || writingFrame != null || gatt == null || writeCharacteristic == null) {
            return;
        }
        PageWriteWindow window = current.window;
        if (window != null && !window.canWrite()) {
            return;
        }
        if (current.bulk) {
            extendPagePriority();
        }
        byte[] frame = current.frames.removeFirst();
        try {
            writeCharacteristic.setValue(frame);
            writeCharacteristic.setWriteType(current.noResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            writingFrame = frame;
            writeStartedMs = SystemClock.elapsedRealtime();
            if (gatt.writeCharacteristic(writeCharacteristic)) {
                if (window != null) {
                    byte[] next = current.frames.peekFirst();
                    window.submitted(next == null ? -1 : WireProtocol.readLe16(next, 12));
                }
                watchWriteProgress();
                return;
            }
        } catch (SecurityException ignored) {
        }
        traffic.unfinishedWrite(frame, SystemClock.elapsedRealtime(), TrafficMonitor.NOT_SUBMITTED);
        writingFrame = null;
        fail();
    }

    private void fail() {
        fail(ready ? Failure.DISCONNECTED : stage == Stage.PAIRING ? Failure.PAIRING : Failure.CONNECT);
    }

    private void fail(Failure failure) {
        if (stopped) {
            return;
        }
        Log.w(TAG, "Connection failed: " + failure);
        stop();
        // A write can fail inside send(); let the controller finish that call before resetting it.
        handler.post(() -> listener.onError(failure));
    }

    private boolean isLocationEnabled() {
        LocationManager location = context.getSystemService(LocationManager.class);
        if (location == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return location.isLocationEnabled();
        }
        return location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
