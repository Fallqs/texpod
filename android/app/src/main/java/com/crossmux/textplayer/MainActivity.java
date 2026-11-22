package com.crossmux.textplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.crossmux.textplayer.preview.ReaderPreviewView;
import com.crossmux.textplayer.protocol.WireProtocol;
import com.crossmux.textplayer.session.ReaderDiagnostics;
import com.crossmux.textplayer.session.TextPlayerSession;
import com.crossmux.textplayer.transport.TrafficMonitor;

public final class MainActivity extends Activity {
    private static final int BLUETOOTH_PERMISSION_REQUEST = 2;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1;
    private static final long STATUS_REFRESH_MILLIS = 200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextPlayerController controller;
    private ReaderPreviewView readerPreview;
    private View emptyState;
    private TextView previewError;
    private TextView captureTitle;
    private TextView captureDetail;
    private TextView pageText;
    private View liveDot;
    private ImageButton previousButton;
    private ImageButton nextButton;
    private ImageButton clearButton;
    private Button readerButton;
    private TextView readerStatus;
    private TextView readerProfileDetail;
    private TextView readerTrafficRecent;
    private Button readerTrafficHistory;
    private Button reinstallFontButton;
    private long renderedTrafficRevision = -1;
    private boolean pendingReaderConnection;

    private final Runnable statusRefresher = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, STATUS_REFRESH_MILLIS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SystemBarInsets.applyTo(findViewById(R.id.content_scroll));
        controller = TextPlayerController.getInstance(this);
        controller.initialize();
        bindViews();
        requestNotificationPermission();
        updateStatus();
    }

    private void bindViews() {
        findViewById(R.id.preview_container).setClipToOutline(true);
        readerPreview = findViewById(R.id.reader_preview);
        emptyState = findViewById(R.id.empty_state);
        previewError = findViewById(R.id.preview_error);
        captureTitle = findViewById(R.id.capture_title);
        captureDetail = findViewById(R.id.capture_detail);
        pageText = findViewById(R.id.page_text);
        liveDot = findViewById(R.id.live_dot);
        previousButton = findViewById(R.id.previous_button);
        nextButton = findViewById(R.id.next_button);
        clearButton = findViewById(R.id.clear_button);
        readerButton = findViewById(R.id.reader_button);
        readerStatus = findViewById(R.id.reader_status);
        readerProfileDetail = findViewById(R.id.reader_profile_detail);
        readerTrafficRecent = findViewById(R.id.reader_traffic_recent);
        readerTrafficHistory = findViewById(R.id.reader_traffic_history);
        readerTrafficHistory.setOnClickListener(view -> showTrafficHistory());
        reinstallFontButton = findViewById(R.id.reader_reinstall_font);
        reinstallFontButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.reader_reinstall_font)
                .setMessage(R.string.reader_reinstall_font_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reader_reinstall_font, (dialog, which) -> {
                    controller.reinstallFont();
                    updateConnectionStatus();
                })
                .show());
        findViewById(R.id.reader_profile_toggle).setOnClickListener(view -> {
            boolean open = readerProfileDetail.getVisibility() != View.VISIBLE;
            int visibility = open ? View.VISIBLE : View.GONE;
            readerProfileDetail.setVisibility(visibility);
            readerTrafficRecent.setVisibility(visibility);
            readerTrafficHistory.setVisibility(visibility);
            ((Button) view).setText(open ? R.string.reader_profile_close : R.string.reader_profile_open);
            updateReaderProfile(controller.getConnectionState());
        });

        findViewById(R.id.settings_button).setOnClickListener(view -> openReaderSettings());
        findViewById(R.id.sample_button).setOnClickListener(view ->
                controller.loadSample(getString(R.string.sample_text)));
        previousButton.setOnClickListener(view -> controller.showPreviousPage());
        nextButton.setOnClickListener(view -> controller.showNextPage());
        clearButton.setOnClickListener(view -> {
            controller.clearReading();
            Toast.makeText(this, R.string.session_cleared, Toast.LENGTH_SHORT).show();
            updateStatus();
        });
        readerButton.setOnClickListener(view -> {
            if (controller.isReaderWanted()
                    && controller.getConnectionState() != TextPlayerController.ConnectionState.ERROR) {
                controller.disconnectReader();
            } else {
                requestReaderConnection();
            }
            updateStatus();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(statusRefresher);
        handler.post(statusRefresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(statusRefresher);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void updateStatus() {
        TextPlayerController.Preview preview = controller.getPreview();
        TextPlayerSession.Snapshot session = preview.getSession();
        readerPreview.setPage(preview.getBitmap());
        emptyState.setVisibility(preview.hasPage() ? View.GONE : View.VISIBLE);
        previewError.setVisibility(preview.isRenderFailed() ? View.VISIBLE : View.GONE);
        previousButton.setEnabled(preview.canTurnBack());
        nextButton.setEnabled(preview.getPagesAhead() > 0);
        previousButton.setAlpha(previousButton.isEnabled() ? 1f : 0.45f);
        nextButton.setAlpha(nextButton.isEnabled() ? 1f : 0.45f);
        if (preview.hasPage()) {
            pageText.setText(getString(R.string.page_counts, preview.getPagesPast(), preview.getPagesAhead()));
            pageText.setContentDescription(getString(R.string.page_counts_description,
                    preview.getPagesPast(), preview.getPagesAhead()));
        } else {
            pageText.setText(R.string.no_pages);
            pageText.setContentDescription(null);
        }
        clearButton.setEnabled(session.getState() != TextPlayerSession.State.IDLE);
        clearButton.setAlpha(clearButton.isEnabled() ? 1f : 0.4f);
        liveDot.setVisibility(session.getState() == TextPlayerSession.State.ACTIVE ? View.VISIBLE : View.INVISIBLE);

        switch (session.getState()) {
            case IDLE:
                captureTitle.setText(R.string.ready_title);
                captureDetail.setText(R.string.ready_detail);
                break;
            case ACTIVE:
                captureTitle.setText(R.string.active_title);
                captureDetail.setText(getResources().getQuantityString(R.plurals.active_detail,
                        session.getReceivedCharacters(), sourceLabel(session.getSourcePackage()),
                        session.getReceivedCharacters()));
                break;
            case ERROR:
                captureTitle.setText(R.string.error_title);
                captureDetail.setText(getString(R.string.error_detail, errorMessage(session.getError())));
                break;
        }
        updateConnectionStatus();
    }

    private void updateConnectionStatus() {
        TextPlayerController.ConnectionState state = controller.getConnectionState();
        readerButton.setText(switch (state) {
            case OFF, ERROR -> R.string.connect_reader;
            case CONNECTED -> R.string.disconnect_reader;
            case SCANNING, CONNECTING, PAIRING, SETTING_UP -> R.string.reader_cancel_connection;
        });
        String status = switch (state) {
            case OFF -> getString(R.string.reader_open_tip);
            case SCANNING -> getString(R.string.reader_scanning);
            case CONNECTING -> getString(R.string.reader_connecting);
            case PAIRING -> getString(R.string.reader_pairing);
            case SETTING_UP -> readerSetupStatus();
            case CONNECTED -> getString(R.string.reader_connected);
            case ERROR -> getString(connectionErrorMessage());
        };
        if (!TextUtils.equals(readerStatus.getText(), status)) readerStatus.setText(status);
        updateReaderProfile(state);
    }

    private String readerSetupStatus() {
        var progress = controller.getFontProgress();
        if (progress == null) return getString(R.string.reader_setup);
        return switch (progress.stage()) {
            case PREPARING -> getString(R.string.reader_font_preparing);
            case CHECKING -> getString(R.string.reader_font_checking);
            case GENERATING -> getString(R.string.reader_font_generating);
            case UPLOADING -> getString(R.string.reader_font_uploading, progress.confirmedBytes() / 1048576.0,
                    progress.totalBytes() / 1048576.0);
            case INSTALLING -> getString(R.string.reader_font_installing);
        };
    }

    private void updateReaderProfile(TextPlayerController.ConnectionState state) {
        boolean open = readerProfileDetail.getVisibility() == View.VISIBLE;
        reinstallFontButton.setVisibility(open && controller.canReinstallFont() ? View.VISIBLE : View.GONE);
        if (!open) return;
        updateRecentTraffic();
        var link = controller.getReaderLink();
        if (link == null) {
            setReaderProfileText(getString(R.string.reader_profile_connect));
            return;
        }
        StringBuilder detail = new StringBuilder();
        if (state == TextPlayerController.ConnectionState.OFF || state == TextPlayerController.ConnectionState.ERROR) {
            detail.append(getString(R.string.reader_profile_last_connection));
        }
        detail.append(getString(R.string.reader_profile_link, link.viewportWidth(), link.viewportHeight(),
                link.columns(), link.rows(), link.fontSizePx(), link.mtu(), link.frameBytes(), link.slots(),
                link.poolBytes(), link.maxTokens()));
        detail.append('\n').append(link.pageBurst()
                ? getString(R.string.reader_profile_burst, WireProtocol.PAGE_BURST_FRAMES)
                : getString(R.string.reader_profile_acknowledged));
        if (link.sdGlyphs()) detail.append('\n').append(getString(R.string.reader_profile_sd_mode));
        if (link.timingFlags() == 0) {
            detail.append('\n').append(getString(R.string.reader_profile_legacy));
        } else if (link.timingFlags() == WireProtocol.SESSION_PAGE_TIMINGS) {
            detail.append('\n').append(getString(R.string.reader_profile_detailed_upgrade));
        }
        var pending = controller.getPendingPageProfile();
        var last = controller.getLastPageProfile();
        if (pending != null) {
            detail.append("\n\n").append(getString(R.string.reader_profile_sending, pageDescription(pending),
                    (SystemClock.elapsedRealtime() - pending.startedMs()) / 1000.0));
        }
        if (last != null) {
            appendPageProfile(detail, last);
        } else if (pending == null && state == TextPlayerController.ConnectionState.CONNECTED) {
            detail.append("\n\n").append(getString(R.string.reader_profile_waiting));
        }
        setReaderProfileText(detail.toString());
    }

    private void appendPageProfile(StringBuilder detail, ReaderDiagnostics.Page page) {
        detail.append("\n\n").append(getString(R.string.reader_profile_displayed, pageDescription(page),
                page.totalMs(), page.encodeMs(), page.occupiedSlots()));
        detail.append("\n\n").append(getString(R.string.reader_profile_traffic, page.gattValueBytes()));
        boolean burst = controller.getReaderLink().pageBurst();
        if (page.writesMs() >= 0) {
            detail.append('\n').append(getString(burst ? R.string.reader_profile_local_writes
                    : R.string.reader_profile_writes, page.writesMs()));
            detail.append('\n').append(getString(page.highPrioritySubmitted()
                    ? R.string.reader_profile_priority_submitted : R.string.reader_profile_priority_not_submitted));
        }
        if (!burst && page.writesMs() > 0) {
            detail.append('\n').append(getString(R.string.reader_profile_goodput, page.payloadKbps()));
        }
        var timing = page.reader();
        var intervals = timing == null ? null : timing.intervals();
        if (intervals != null && intervals.firstUnits() > 0 && intervals.lastUnits() > 0) {
            detail.append('\n').append(getString(R.string.reader_profile_connection_interval,
                    intervals.firstMs(), intervals.lastMs()));
        } else {
            detail.append('\n').append(getString(R.string.reader_profile_connection_interval_unavailable));
        }
        if (timing == null) {
            return;
        }
        var turn = timing.glyphTurn();
        if (turn != null) {
            if (turn.hasTurn()) {
                detail.append('\n').append(getString(R.string.reader_profile_turn_e2e, turn.turnMs()));
            }
            detail.append('\n').append(getString(R.string.reader_profile_glyph_cache, turn.cacheHits(),
                    turn.cacheMisses(), turn.glyphReadUs() / 1000.0));
        }
        var rx = timing.rx();
        if (rx != null) {
            detail.append('\n').append(getString(R.string.reader_profile_arrival, rx.arrivalSpanMs()));
            if (burst && rx.arrivalSpanMs() > 0) {
                detail.append('\n').append(getString(R.string.reader_profile_arrival_rate,
                        page.bytes() * 8.0 / rx.arrivalSpanMs()));
            }
        }
        detail.append("\n\n").append(getString(R.string.reader_profile_reader_timing, timing.prepareMs(),
                timing.renderQueueMs(), timing.drawMs(), timing.refreshMs(), timing.stagedToRefreshMs()));
        if (rx != null) {
            int work = page.encoding() == WireProtocol.MessageType.PAGE_GLYPHS
                    ? R.string.reader_profile_glyph_work : R.string.reader_profile_staging;
            detail.append('\n').append(getString(work, rx.stagingUs() / 1000.0, rx.lastArrivalToStagedMs()));
        } else {
            detail.append('\n').append(getString(R.string.reader_profile_legacy_receive, timing.receiveMs()));
        }
    }

    private void updateRecentTraffic() {
        long revision = controller.getTrafficRevision();
        if (renderedTrafficRevision == revision) {
            return;
        }
        renderedTrafficRevision = revision;
        var recent = controller.getTraffic(4);
        readerTrafficHistory.setEnabled(recent.total() > 0);
        readerTrafficRecent.setText(formatTraffic(recent, false));
    }

    private void showTrafficHistory() {
        String trace = formatTraffic(controller.getTraffic(TrafficMonitor.CAPACITY), true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.reader_traffic_title)
                .setMessage(trace)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reader_traffic_copy, (dialog, which) -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.reader_traffic_title), trace));
                    }
                })
                .show();
    }

    private String formatTraffic(TrafficMonitor.Snapshot snapshot, boolean history) {
        StringBuilder text = new StringBuilder();
        if (!history) {
            text.append(getString(R.string.reader_traffic_title)).append('\n');
        }
        text.append(getString(R.string.reader_traffic_scope)).append('\n');
        if (snapshot.total() == 0) {
            return text.append(getString(R.string.reader_traffic_empty)).toString();
        }
        if (history && snapshot.omitted() > 0) {
            text.append(getString(R.string.reader_traffic_omitted, snapshot.omitted()));
        } else if (!history) {
            text.append(getString(R.string.reader_traffic_recent, snapshot.packets().size(), snapshot.total()));
        }
        for (var packet : snapshot.packets()) {
            appendPacket(text, packet);
        }
        return text.toString();
    }

    private void appendPacket(StringBuilder text, TrafficMonitor.Packet packet) {
        var type = WireProtocol.MessageType.fromCode(packet.typeCode());
        text.append("\n\n").append(getString(R.string.reader_traffic_row, packet.sequence(), packet.elapsedMs(),
                packet.tx() ? "TX" : "RX", type == null ? getString(R.string.reader_traffic_unknown_type) : type.name(),
                packet.bytes()));
        if (packet.offset() >= 0) {
            text.append('\n').append(getString(R.string.reader_traffic_position, packet.objectId(), packet.offset()));
        }
        if (packet.notSubmitted()) {
            text.append('\n').append(getString(R.string.reader_traffic_not_submitted));
        } else if (packet.noCallback()) {
            text.append('\n').append(getString(R.string.reader_traffic_no_callback));
        } else if (packet.tx()) {
            var link = controller.getReaderLink();
            boolean command = link != null && link.pageBurst() && type != null && type.isPage();
            text.append('\n').append(getString(command ? R.string.reader_traffic_local_callback
                    : R.string.reader_traffic_callback, packet.writeToCallbackMs(), packet.gattStatus()));
        }
        if (packet.dispatchMs() >= 0) {
            text.append('\n').append(getString(R.string.reader_traffic_dispatch, packet.dispatchMs()));
        }
    }

    private String pageDescription(ReaderDiagnostics.Page page) {
        int encoding = switch (page.encoding()) {
            case PAGE_BITMAP -> R.string.reader_profile_bitmap;
            case PAGE_GLYPHS -> R.string.reader_profile_sd_glyphs;
            default -> R.string.reader_profile_grid;
        };
        return getString(R.string.reader_profile_page, getString(encoding), page.bytes(), page.fragments());
    }

    private void setReaderProfileText(String text) {
        if (!TextUtils.equals(readerProfileDetail.getText(), text)) {
            readerProfileDetail.setText(text);
        }
    }

    private int connectionErrorMessage() {
        if (controller.hasFontError()) return R.string.reader_font_error;
        var failure = controller.getConnectionFailure();
        if (failure == null) {
            return R.string.reader_session_error;
        }
        return switch (failure) {
            case PERMISSION -> R.string.bluetooth_permission;
            case NO_BLUETOOTH -> R.string.reader_no_bluetooth;
            case BLUETOOTH_OFF -> R.string.reader_bluetooth_off;
            case LOCATION_OFF -> R.string.reader_location_off;
            case NOT_FOUND -> R.string.reader_not_found;
            case CONNECT -> R.string.reader_connection_error;
            case PAIRING -> R.string.reader_pairing_error;
            case DISCONNECTED -> R.string.reader_disconnected;
        };
    }

    private String sourceLabel(String sourcePackage) {
        if (sourcePackage == null) {
            return getString(R.string.ready_detail);
        }
        if ("texpod.sample".equals(sourcePackage)) {
            return getString(R.string.local_sample);
        }
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(sourcePackage, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return sourcePackage;
        }
    }

    private String errorMessage(TextPlayerSession.Error error) {
        if (error == null) {
            return getString(R.string.error_interrupted);
        }
        switch (error) {
            case QUEUE_FULL:
                return getString(R.string.error_queue_full);
            case TEXT_LIMIT:
                return getString(R.string.error_text_limit);
            case INTERRUPTED:
                return getString(R.string.error_interrupted);
        }
        return getString(R.string.error_interrupted);
    }

    private void openReaderSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void requestReaderConnection() {
        if (!hasBluetoothPermissions()) {
            pendingReaderConnection = true;
            Toast.makeText(this, R.string.bluetooth_permission, Toast.LENGTH_SHORT).show();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(new String[] {Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION_REQUEST);
            } else {
                requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION},
                        BLUETOOTH_PERMISSION_REQUEST);
            }
            return;
        }
        controller.connectReader();
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != BLUETOOTH_PERMISSION_REQUEST || !pendingReaderConnection) {
            return;
        }
        pendingReaderConnection = false;
        controller.connectReader();
        updateStatus();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        }
    }
}
