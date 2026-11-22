package com.crossmux.textplayer.session;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.crossmux.textplayer.glyph.AndroidSdGlyphs;
import com.crossmux.textplayer.glyph.FontPackWriter;
import com.crossmux.textplayer.layout.GridPager;
import com.crossmux.textplayer.protocol.WireProtocol;
import com.crossmux.textplayer.transport.BleTransport;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

@TargetApi(31)
public final class ReaderFontSetup implements AutoCloseable {
    public enum Stage { PREPARING, CHECKING, GENERATING, UPLOADING, INSTALLING }

    public record Progress(Stage stage, int confirmedBytes, int totalBytes) {}

    public interface Listener {
        void onFontReady(AndroidSdGlyphs glyphs, byte[] generation);
        void onFontFailed();
    }

    private static final String TAG = "TexpodFonts";
    private static final long STATUS_TIMEOUT_MS = 15_000;
    private static final int MAX_FRAMES_PER_CHUNK = 64;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final BleTransport transport;
    private final int objectLimit;
    private final Listener listener;
    private final Runnable statusTimeout = () -> fail(new IOException("Timed out awaiting reader font status"));

    private volatile boolean closed;
    private Thread worker;
    private FontPackSync sync;
    private AndroidSdGlyphs glyphs;
    private byte[] generation;
    private File tempPack;
    private Runnable probeAndroidFont;
    private boolean forceUpload;
    private Stage stage = Stage.PREPARING;

    public ReaderFontSetup(Context context, BleTransport transport, int objectLimit, Listener listener) {
        this.context = context;
        this.transport = transport;
        this.objectLimit = objectLimit;
        this.listener = listener;
    }

    public void start(int pixelSize, AndroidSdGlyphs previous, GridPager.Page anchor, boolean force) {
        forceUpload = force;
        probeAndroidFont = () -> prepare(() -> AndroidSdGlyphs.forPage(pixelSize, previous, anchor));
        if (force) {
            probeAndroidFont.run();
        } else {
            prepare(() -> AndroidSdGlyphs.forBakedPage(context.getAssets(), pixelSize, anchor));
        }
    }

    private interface FontProbe {
        AndroidSdGlyphs load() throws IOException;
    }

    private void prepare(FontProbe probe) {
        stage = Stage.PREPARING;
        launchWorker(() -> {
            try {
                AndroidSdGlyphs prepared = probe.load();
                if (closed) return;
                byte[] id = prepared == null ? null : prepared.generation();
                handler.post(() -> {
                    if (closed) return;
                    if (prepared == null) probeAndroidFont.run();
                    else begin(prepared, id);
                });
            } catch (IOException | RuntimeException error) {
                handler.post(() -> fail(error));
            }
        });
    }

    public Progress progress() {
        return new Progress(stage, sync == null ? 0 : sync.confirmedBytes(), sync == null ? 0 : sync.totalBytes());
    }

    public void onStatus(long sessionId, long id, byte[] body) {
        if (closed) return;
        try {
            if (sync == null) throw new IOException("Unsolicited reader font status");
            FontPackSync.Command next = sync.onStatus(sessionId, id, body);
            handler.removeCallbacks(statusTimeout);
            advance(next);
        } catch (IOException error) {
            fail(error);
        }
    }

    private void begin(AndroidSdGlyphs prepared, byte[] id) {
        if (closed) return;
        glyphs = prepared;
        generation = id;
        int payload = transport.getFrameBytes() - WireProtocol.HEADER_SIZE;
        // Avoid a single status wait covering hundreds of fragments at the minimum ATT MTU.
        int limit = Math.min(objectLimit, payload * MAX_FRAMES_PER_CHUNK - WireProtocol.START_METADATA_SIZE);
        try {
            long previousId = sync == null ? 0 : sync.lastId();
            closeSource(sync);
            sync = new FontPackSync(generation, limit, previousId);
            advance(sync.start(forceUpload));
        } catch (IllegalArgumentException error) {
            fail(error);
        }
    }

    private void advance(FontPackSync.Command command) {
        switch (sync.phase()) {
            case QUERYING -> stage = Stage.CHECKING;
            case BEGINNING, UPLOADING -> stage = Stage.UPLOADING;
            case INSTALLING -> stage = Stage.INSTALLING;
            case NEED_PACK -> {
                if (glyphs.isBaked()) {
                    probeAndroidFont.run();
                } else if (forceUpload) {
                    stage = Stage.GENERATING;
                    exportPack();
                } else {
                    fail(new IOException("No matching font pack on the reader"));
                }
                return;
            }
            case READY -> {
                deletePack(tempPack);
                tempPack = null;
                listener.onFontReady(glyphs, generation);
                return;
            }
            default -> throw new IllegalStateException("Unexpected font sync phase");
        }
        if (command == null || !transport.send(command.type(), 0, command.id(), command.body())) {
            fail(new IOException("Unable to queue reader font command"));
        } else {
            handler.postDelayed(statusTimeout, STATUS_TIMEOUT_MS);
        }
    }

    private void exportPack() {
        AndroidSdGlyphs prepared = glyphs;
        byte[] id = generation;
        launchWorker(() -> {
            File draft = null;
            try {
                draft = File.createTempFile("texpod-font-", ".tpf", context.getCacheDir());
                FontPackWriter.Result result;
                try (var output = new BufferedOutputStream(new FileOutputStream(draft))) {
                    result = prepared.exportPack(output, id, () -> closed);
                }
                File saved = draft;
                if (!closed && handler.post(() -> uploadPack(saved, result))) draft = null;
            } catch (IOException | RuntimeException error) {
                handler.post(() -> fail(error));
            } finally {
                deletePack(draft);
            }
        });
    }

    private void uploadPack(File saved, FontPackWriter.Result result) {
        if (closed) {
            deletePack(saved);
            return;
        }
        tempPack = saved;
        FileInputStream source = null;
        try {
            source = new FileInputStream(tempPack);
            FontPackSync.Command begin = sync.beginUpload(result, source);
            source = null;
            advance(begin);
        } catch (IOException | IllegalArgumentException error) {
            closeSource(source);
            fail(error);
        }
    }

    private void launchWorker(Runnable task) {
        worker = new Thread(task, "TexpodFontSetup");
        worker.start();
    }

    private void fail(Exception error) {
        if (closed) return;
        Log.w(TAG, "Font setup failed", error);
        close();
        listener.onFontFailed();
    }

    @Override
    public void close() {
        closed = true;
        handler.removeCallbacks(statusTimeout);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        closeSource(sync);
        sync = null;
        deletePack(tempPack);
        tempPack = null;
    }

    private static void closeSource(java.io.Closeable source) {
        if (source == null) return;
        try {
            source.close();
        } catch (IOException error) {
            Log.w(TAG, "Unable to close temporary font pack", error);
        }
    }

    private static void deletePack(File pack) {
        if (pack != null && !pack.delete()) Log.w(TAG, "Unable to remove temporary font pack");
    }
}
