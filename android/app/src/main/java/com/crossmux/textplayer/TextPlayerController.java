package com.crossmux.textplayer;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.crossmux.textplayer.glyph.AndroidGlyphRasterizer;
import com.crossmux.textplayer.glyph.AndroidSdGlyphs;
import com.crossmux.textplayer.glyph.DeviceGlyphCache;
import com.crossmux.textplayer.glyph.GlyphBitmap;
import com.crossmux.textplayer.glyph.GlyphKey;
import com.crossmux.textplayer.glyph.PageBitmap;
import com.crossmux.textplayer.glyph.PageBitmapEncoder;
import com.crossmux.textplayer.glyph.PageEncoder;
import com.crossmux.textplayer.glyph.RasterGlyphLru;
import com.crossmux.textplayer.layout.GridLayout;
import com.crossmux.textplayer.layout.GridPager;
import com.crossmux.textplayer.layout.LocalReading;
import com.crossmux.textplayer.layout.ReaderProfile;
import com.crossmux.textplayer.layout.TextRunPacker;
import com.crossmux.textplayer.protocol.WireProtocol;
import com.crossmux.textplayer.session.ReaderDiagnostics;
import com.crossmux.textplayer.session.ReaderFontSetup;
import com.crossmux.textplayer.session.ReadingFlowGate;
import com.crossmux.textplayer.session.TextPlayerSession;
import com.crossmux.textplayer.session.TtsIngester;
import com.crossmux.textplayer.transport.BleTransport;
import com.crossmux.textplayer.transport.TrafficMonitor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;

public final class TextPlayerController {
    public enum ConnectionState {
        OFF,
        SCANNING,
        CONNECTING,
        PAIRING,
        SETTING_UP,
        CONNECTED,
        ERROR
    }

    public static final class IngestOutcome {
        private final TextPlayerSession.IngestResult result;
        private final ReadingFlowGate.Token token;

        private IngestOutcome(TextPlayerSession.IngestResult result, ReadingFlowGate.Token token) {
            this.result = result;
            this.token = token;
        }

        public TextPlayerSession.IngestResult getResult() {
            return result;
        }

        public ReadingFlowGate.Token getToken() {
            return token;
        }
    }

    public static final class Preview {
        private final PageBitmap bitmap;
        private final int pagesPast;
        private final int pagesAhead;
        private final boolean bridging;
        private final boolean renderFailed;
        private final TextPlayerSession.Snapshot session;

        private Preview(PageBitmap bitmap, int pageIndex, int pageCount, boolean renderFailed,
                TextPlayerSession.Snapshot session) {
            this.bitmap = bitmap;
            bridging = pageIndex == LocalReading.BRIDGE_PAGE;
            pagesPast = Math.max(0, pageIndex);
            pagesAhead = bridging ? pageCount : Math.max(0, pageCount - pageIndex - 1);
            this.renderFailed = renderFailed;
            this.session = session;
        }

        public PageBitmap getBitmap() {
            return bitmap;
        }

        public boolean hasPage() {
            return bitmap != null || renderFailed;
        }

        public int getPagesPast() {
            return pagesPast;
        }

        public int getPagesAhead() {
            return pagesAhead;
        }

        public boolean canTurnBack() {
            return pagesPast > 0 || (bridging && pagesAhead > 0);
        }

        public boolean isRenderFailed() {
            return renderFailed;
        }

        public TextPlayerSession.Snapshot getSession() {
            return session;
        }
    }

    private static final int CAPABILITY_BYTES = 38;
    private static final int PHONE_GLYPH_SLOTS = 1024;
    private static final int PHONE_GLYPH_BYTES = 256 * 1024;
    private static final int MAX_PREVIEW_PIXELS = 1_000_000;
    private static final String FONT_ID = "android-sans-serif";
    private static final String PREFS_NAME = "texpod";
    private static final String KEY_TEXT_SIZE_PX = "text_size_px";
    private static TextPlayerController instance;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextPlayerSession session = TextPlayerSession.getInstance();
    private final TtsIngester ingester = new TtsIngester(session);
    private final SecureRandom random = new SecureRandom();
    private final WireProtocol.ObjectAssembler assembler = new WireProtocol.ObjectAssembler();
    private final Object flowLock = new Object();
    private final ReadingFlowGate flowGate = new ReadingFlowGate();
    private AndroidGlyphRasterizer glyphProvider;
    private TextRunPacker latinPacker;
    private RasterGlyphLru<GlyphKey, GlyphBitmap> rasterCache;
    private int textSizePx;

    private BleTransport transport;
    private ConnectionState connectionState = ConnectionState.OFF;
    private BleTransport.Failure connectionFailure;
    private GridLayout layout = ReaderProfile.phonePreview();
    private LocalReading reading;
    private PageBitmapEncoder bitmapEncoder;
    private PageEncoder pageEncoder;
    private PageBitmap previewBitmap;
    private boolean previewRenderFailed;
    private boolean initialized;
    private boolean readerWanted;
    private boolean transportReady;
    private boolean deviceConfigured;
    private int deviceSlots;
    private int devicePoolBytes;
    private int maxRawTokens = ReaderProfile.MAX_RAW_TOKENS;
    private long observedGeneration = -1;
    private long sessionId;
    private int consumedChunks;
    private int consumedChunkOffset;
    private int readerPageIndex;
    private int displayedReaderPageIndex;
    private long pushSeq;
    private long displayedPushSeq;
    private boolean sessionReady;
    private boolean hasDisplayedPage;
    private boolean preparePages;
    private record PendingPage(long pageId, PageEncoder.EncodedPage legacy, boolean prepare) {}
    private record PreparedPage(GridPager.Page page, byte[] body) {}
    private final PreparedPage[] prepared = new PreparedPage[2];
    private PendingPage pendingPage;
    private ReaderFontSetup fontSetup;
    private AndroidSdGlyphs sdGlyphs;
    private AndroidSdGlyphs lastFontProfile;
    private int lastFontPixelSize;
    private boolean forceFontUpload;
    private boolean fontFailed;
    private ByteArrayOutputStream incoming;
    private int maxObjectBytes = WireProtocol.MAX_OBJECT_BYTES;
    private boolean bitmapSupported;
    private final ReaderDiagnostics diagnostics = new ReaderDiagnostics();
    private final TrafficMonitor traffic = new TrafficMonitor();
    private ReaderDiagnostics.Link readerLink;

    public long getTrafficRevision() { return traffic.revision(); }

    public TrafficMonitor.Snapshot getTraffic(int limit) { return traffic.latest(limit); }

    public ReaderDiagnostics.Link getReaderLink() { return readerLink; }

    public ReaderDiagnostics.Page getPendingPageProfile() { return diagnostics.pending(); }

    public ReaderDiagnostics.Page getLastPageProfile() { return diagnostics.last(); }

    public static synchronized TextPlayerController getInstance(Context context) {
        if (instance == null) {
            instance = new TextPlayerController(context);
        }
        return instance;
    }

    private TextPlayerController(Context context) {
        this.context = context.getApplicationContext();
        textSizePx = ReaderProfile.clampTextSize(this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_TEXT_SIZE_PX, ReaderProfile.TEXT_SIZE_PX));
        layout = ReaderProfile.phonePreview(textSizePx);
        configureRasterizer(Typeface.create("sans-serif", Typeface.NORMAL));
        reading = new LocalReading(layout, ReaderProfile.MAX_RAW_TOKENS, latinPacker);
        bitmapEncoder = new PageBitmapEncoder(glyphProvider, rasterCache, layout, FONT_ID, textSizePx, 0);
    }

    private Typeface rasterTypeface;

    private void configureRasterizer(Typeface typeface) {
        rasterTypeface = typeface;
        glyphProvider = new AndroidGlyphRasterizer(typeface, textSizePx);
        rasterCache = new RasterGlyphLru<>(PHONE_GLYPH_SLOTS, PHONE_GLYPH_BYTES, GlyphBitmap::getByteLength);
        latinPacker = new TextRunPacker(glyphProvider, ReaderProfile.cellWidth(textSizePx), glyphProvider.maxRunWidth(),
                ReaderProfile.WIDE_GLYPH_CELLS);
    }

    public int getTextSizePx() {
        synchronized (flowLock) {
            return textSizePx;
        }
    }

    public void setTextSizePx(int requestedSize) {
        synchronized (flowLock) {
            int newSize = ReaderProfile.clampTextSize(requestedSize);
            if (newSize == textSizePx) {
                return;
            }
            textSizePx = newSize;
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_TEXT_SIZE_PX, newSize).apply();
            configureRasterizer(rasterTypeface);
            if (readerWanted && connectionState != ConnectionState.ERROR) {
                connectReader(hasDisplayedPage ? displayedReaderPageIndex : readerAnchorIndex);
            } else {
                useLayout(ReaderProfile.phonePreview(textSizePx), ReaderProfile.MAX_RAW_TOKENS);
            }
        }
    }

    public void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        handler.post(syncText);
    }

    public IngestOutcome ingest(String callerPackage, CharSequence text) {
        synchronized (flowLock) {
            TextPlayerSession.IngestResult result = ingester.ingest(callerPackage, text);
            ReadingFlowGate.Token token = result == TextPlayerSession.IngestResult.ACCEPTED
                    ? flowGate.accept(session.getSnapshot().getGeneration(), text.length()) : null;
            return new IngestOutcome(result, token);
        }
    }

    public boolean awaitConsumption(ReadingFlowGate.Token token) {
        return flowGate.await(token);
    }

    public void loadSample(String text) {
        synchronized (flowLock) {
            ingester.loadSample(text);
            replaceReading = true;
            flowGate.accept(session.getSnapshot().getGeneration(), text.length());
        }
    }

    public void clearReading() {
        synchronized (flowLock) {
            ingester.clear();
            replaceReading = true;
            flowGate.pause();
            if (Looper.myLooper() == Looper.getMainLooper()) {
                syncReadingLocked(session.getSnapshot());
            }
        }
    }

    public void pauseFlow(String callerPackage) {
        synchronized (flowLock) {
            if (ingester.pause(callerPackage)) {
                flowGate.pause();
            }
        }
    }

    public Preview getPreview() {
        synchronized (flowLock) {
            return new Preview(previewBitmap, reading.currentIndex(), reading.pageCount(), previewRenderFailed,
                    session.getSnapshot());
        }
    }

    public boolean showNextPage() {
        return turnPreviewPage(1);
    }

    public boolean showPreviousPage() {
        return turnPreviewPage(-1);
    }

    private boolean turnPreviewPage(int direction) {
        synchronized (flowLock) {
            if (!syncReadingLocked(session.getSnapshot())
                    || !reading.showPage(reading.turnTarget(reading.currentIndex(), direction))) {
                return false;
            }
            refreshPreview();
            updateFlowRenderedLocked();
            return true;
        }
    }

    public void connectReader() {
        connectReader(LocalReading.NO_PAGE);
    }

    private void connectReader(int anchorIndex) {
        connectReader(anchorIndex, false);
    }

    private void connectReader(int anchorIndex, boolean forcePack) {
        readerWanted = true;
        connectionState = ConnectionState.CONNECTING;
        stopTransport();
        diagnostics.reset();
        traffic.reset(SystemClock.elapsedRealtime());
        readerLink = null;
        fontFailed = false;
        forceFontUpload = forcePack;
        readerAnchorIndex = anchorIndex;
        if (layout.cellWidth != ReaderProfile.cellWidth(textSizePx)) {
            useLayout(ReaderProfile.phonePreview(textSizePx), ReaderProfile.MAX_RAW_TOKENS);
        }
        transport = new BleTransport(context, transportListener, traffic);
        transport.start();
    }

    public void disconnectReader() {
        readerWanted = false;
        stopTransport();
        useLayout(ReaderProfile.phonePreview(textSizePx), ReaderProfile.MAX_RAW_TOKENS);
        connectionState = ConnectionState.OFF;
    }

    public ConnectionState getConnectionState() {
        return connectionState;
    }

    public boolean isReaderWanted() {
        return readerWanted;
    }

    public void shutdown() {
        initialized = false;
        handler.removeCallbacks(syncText);
        clearReading();
        disconnectReader();
    }

    private final Runnable syncText = new Runnable() {
        @Override
        public void run() {
            synchronized (flowLock) {
                if (!initialized) {
                    return;
                }
                TextPlayerSession.Snapshot snapshot = session.getSnapshot();
                syncReadingLocked(snapshot);
                maybePushAnchor();
                handler.postDelayed(this, 200);
            }
        }
    };

    private boolean replaceReading;
    private int readerAnchorIndex = -1;

    private boolean syncReadingLocked(TextPlayerSession.Snapshot snapshot) {
        long generation = snapshot.getGeneration();
        boolean changed = observedGeneration != generation;
        if (changed) {
            if (replaceReading) {
                replaceReading = false;
                reading = new LocalReading(layout, maxRawTokens, latinPacker);
                readerAnchorIndex = -1;
                if (transportReady && deviceConfigured) {
                    connectReader();
                }
            } else {
                if (pendingPage != null) {
                    // The reader may already be drawing the page. Use its acknowledged anchor.
                    return false;
                }
                int anchor = hasDisplayedPage ? displayedReaderPageIndex : anchorIndex();
                int keptIndex = reading.switchFlowAt(anchor);
                if (hasDisplayedPage) {
                    readerPageIndex = keptIndex;
                    displayedReaderPageIndex = keptIndex;
                } else if (readerAnchorIndex != LocalReading.NO_PAGE) {
                    readerAnchorIndex = keptIndex;
                }
            }
            observedGeneration = generation;
            consumedChunks = 0;
            consumedChunkOffset = 0;
        }
        updateFlowRenderedLocked();
        List<String> chunks = session.getReceivedText();
        while (consumedChunks < chunks.size() && flowGate.canConsume(generation)) {
            String chunk = chunks.get(consumedChunks);
            int codePoint = chunk.codePointAt(consumedChunkOffset);
            int length = Character.charCount(codePoint);
            reading.appendRange(chunk, consumedChunkOffset, consumedChunkOffset + length);
            consumedChunkOffset += length;
            if (consumedChunkOffset == chunk.length()) {
                consumedChunks++;
                consumedChunkOffset = 0;
            }
            changed = true;
            // Publish the page count before acknowledging the character that may have filled the window.
            updateFlowRenderedLocked();
            flowGate.consumed(generation, length);
        }
        if (changed) {
            refreshPreview();
        }
        return true;
    }

    private void updateFlowRenderedLocked() {
        int readerIndex = hasDisplayedPage ? displayedReaderPageIndex : readerAnchorIndex;
        reading.discardUnusedBridge(readerIndex);
        int pageIndex = hasDisplayedPage ? displayedReaderPageIndex : reading.currentIndex();
        int pageCount = reading.pageCount();
        if (pageIndex == LocalReading.BRIDGE_PAGE) {
            // The bridge consumes one position in the prepared-page window until the first turn.
            flowGate.rendered(observedGeneration, 0, pageCount + 1);
        } else {
            flowGate.rendered(observedGeneration, pageIndex, pageCount);
        }
    }

    private void refreshPreview() {
        GridPager.Page page = reading.currentPage();
        if (page == null) {
            previewBitmap = null;
            previewRenderFailed = false;
            return;
        }
        previewBitmap = bitmapEncoder.rasterize(page);
        previewRenderFailed = previewBitmap == null;
    }

    private final Runnable readerSetupTimeout = this::failReader;
    private final Runnable readerDisplayTimeout = this::failReader;

    private final BleTransport.Listener transportListener = new BleTransport.Listener() {
        @Override
        public void onStage(BleTransport.Stage stage) {
            connectionState = switch (stage) {
                case SCANNING -> ConnectionState.SCANNING;
                case CONNECTING -> ConnectionState.CONNECTING;
                case PAIRING -> ConnectionState.PAIRING;
            };
        }

        @Override
        public void onReady() {
            transportReady = true;
            connectionState = ConnectionState.SETTING_UP;
            handler.postDelayed(readerSetupTimeout, 15_000);
            if (!transport.send(WireProtocol.MessageType.HELLO, 0, WireProtocol.HELLO_EXTENSIONS, new byte[0])) {
                failReader();
            }
        }

        @Override
        public void onFrame(byte[] encoded, long callbackMs) {
            WireProtocol.Frame frame = WireProtocol.parseFrame(encoded);
            if (frame == null) {
                failReader();
                return;
            }
            if ((frame.flags & WireProtocol.START) != 0) {
                assembler.reset();
                incoming = new ByteArrayOutputStream();
            }
            WireProtocol.AssemblyResult result = assembler.receive(frame, (data, offset, length) -> {
                if (incoming == null) {
                    return false;
                }
                incoming.write(data, offset, length);
                return true;
            });
            switch (result.status) {
                case NEED_MORE:
                    return;
                case ERROR:
                    failReader();
                    return;
                case COMPLETE:
                    handleObject(result, callbackMs);
                    return;
            }
        }

        @Override
        public void onObjectSent(WireProtocol.MessageType type, long sentSessionId, long objectId, long firstWriteMs,
                long lastCallbackMs, boolean highPrioritySubmitted) {
            if (sentSessionId != sessionId) {
                return;
            }
            if (type == WireProtocol.MessageType.START_SESSION && objectId == 0) {
                sessionReady = true;
                connectionState = ConnectionState.CONNECTED;
                handler.removeCallbacks(readerSetupTimeout);
            } else if (type.isPage()) {
                diagnostics.writesComplete(objectId, firstWriteMs, lastCallbackMs, highPrioritySubmitted);
                if (pendingPage != null && pushSeq == objectId) {
                    handler.postDelayed(readerDisplayTimeout, 10_000);
                }
            }
        }

        @Override
        public void onError(BleTransport.Failure failure) {
            failReader(failure);
        }
    };

    private void handleObject(WireProtocol.AssemblyResult result, long callbackMs) {
        byte[] body = incoming == null ? null : incoming.toByteArray();
        if (body == null) {
            failReader();
            return;
        }
        switch (result.type) {
            case CAPABILITIES:
                if (result.sessionId != 0 || result.objectId != 0) failReader();
                else configure(body);
                break;
            case DISPLAYED:
                handleDisplayed(result, body, callbackMs);
                break;
            case PAGE_PREPARED:
                handlePagePrepared(result, body);
                break;
            case PAGE_CREDIT:
                if (body.length != WireProtocol.PAGE_CREDIT_BYTES || !transport.acceptPageCredit(result.sessionId,
                        result.objectId, WireProtocol.readLe16(body, 0))) {
                    failReader();
                }
                break;
            case REQUEST_PAGE:
                handlePageRequest(result, body, callbackMs);
                break;
            case FONT_STATUS:
                if (fontSetup == null) failReader();
                else fontSetup.onStatus(result.sessionId, result.objectId, body);
                break;
            case ERROR:
                if (sdGlyphs != null && result.sessionId == sessionId && body.length == Integer.BYTES
                        && WireProtocol.readLe32(body, 0) == WireProtocol.ERROR_FONT_REQUIRED) {
                    failReaderFont();
                } else {
                    failReader();
                }
                break;
            default:
                failReader();
                break;
        }
    }

    private void configure(byte[] body) {
        if ((body.length != CAPABILITY_BYTES && body.length != CAPABILITY_BYTES + 1) || deviceConfigured) {
            failReader();
            return;
        }
        int maxFrameBytes = WireProtocol.readLe16(body, 0);
        int maxObject = WireProtocol.readLe16(body, 2);
        int safeX = (int) WireProtocol.readLe32(body, 4);
        int safeY = (int) WireProtocol.readLe32(body, 8);
        int safeWidth = (int) WireProtocol.readLe32(body, 12);
        int safeHeight = (int) WireProtocol.readLe32(body, 16);
        int slots = WireProtocol.readLe16(body, 20);
        int poolBytes = (int) WireProtocol.readLe32(body, 22);
        int tokens = WireProtocol.readLe16(body, 26);
        int viewportWidth = (int) WireProtocol.readLe32(body, 30);
        int viewportHeight = (int) WireProtocol.readLe32(body, 34);
        int encodingMask = body[29] & 0xff;
        long bitmapBytes = 16L + ((viewportWidth + 7L) / 8) * viewportHeight;
        GridLayout capabilities = ReaderProfile.fromCapabilities(safeX, safeY, safeWidth, safeHeight,
                viewportWidth, viewportHeight, textSizePx);
        if (slots <= 0 || slots > 32_768 || poolBytes <= 0 || tokens < 4 || maxObject == 0
                || body[28] != 2 || (encodingMask & 1) == 0 || viewportWidth <= 0 || viewportHeight <= 0
                || (long) viewportWidth * viewportHeight > MAX_PREVIEW_PIXELS || capabilities.columns > Short.MAX_VALUE
                || !capabilities.isValid() || !transport.setMaxFrameBytes(maxFrameBytes)) {
            failReader();
            return;
        }
        maxObjectBytes = maxObject;
        bitmapSupported = (encodingMask & 2) != 0 && bitmapBytes <= maxObjectBytes;
        deviceSlots = slots;
        devicePoolBytes = poolBytes;
        deviceConfigured = true;
        boolean sdSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && (encodingMask & WireProtocol.CAP_SD_GLYPHS) != 0;
        preparePages = sdSupported && body.length > CAPABILITY_BYTES
                && (body[CAPABILITY_BYTES] & WireProtocol.CAP_PREPARE_PAGES) != 0;
        readerLink = new ReaderDiagnostics.Link(viewportWidth, viewportHeight, capabilities.columns,
                capabilities.rows, textSizePx, transport.getAttMtu(), transport.getFrameBytes(), slots,
                poolBytes, tokens, WireProtocol.timingFlags(encodingMask, sdSupported),
                transport.negotiatePageBurst(encodingMask), sdSupported);
        useLayout(capabilities, tokens);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && sdSupported) {
            startFontSetup();
        } else {
            startDeviceSession(null);
        }
    }

    private void useLayout(GridLayout newLayout, int newMaxRawTokens) {
        synchronized (flowLock) {
            layout = newLayout;
            maxRawTokens = newMaxRawTokens;
            bitmapEncoder = new PageBitmapEncoder(glyphProvider, rasterCache, layout, FONT_ID,
                    textSizePx, 0);
            readerAnchorIndex = reading.reflow(layout, maxRawTokens, latinPacker, readerAnchorIndex);
            refreshPreview();
            updateFlowRenderedLocked();
        }
    }

    private int anchorIndex() {
        return readerAnchorIndex == LocalReading.NO_PAGE ? reading.currentIndex() : readerAnchorIndex;
    }

    @TargetApi(31)
    private void startFontSetup() {
        handler.removeCallbacks(readerSetupTimeout);
        fontSetup = new ReaderFontSetup(context, transport, maxObjectBytes,
                new ReaderFontSetup.Listener() {
                    @Override public void onFontReady(AndroidSdGlyphs glyphs, byte[] generation) {
                        synchronized (flowLock) {
                            closeFontSetup();
                            sdGlyphs = glyphs;
                            if (!glyphs.isBaked()) {
                                lastFontProfile = glyphs;
                                lastFontPixelSize = textSizePx;
                            }
                            if (!glyphs.typeface().equals(rasterTypeface)) {
                                configureRasterizer(glyphs.typeface());
                                useLayout(layout, maxRawTokens);
                            }
                            startDeviceSession(generation);
                        }
                    }

                    @Override public void onFontFailed() { failReaderFont(); }
                });
        fontSetup.start(textSizePx, lastFontPixelSize == textSizePx ? lastFontProfile : null,
                reading.pageAt(anchorIndex()), forceFontUpload);
        forceFontUpload = false;
    }

    private void closeFontSetup() {
        if (fontSetup != null) {
            fontSetup.close();
            fontSetup = null;
        }
    }

    private void startDeviceSession(byte[] generation) {
        if (generation == null) {
            pageEncoder = new PageEncoder(glyphProvider, rasterCache,
                    new DeviceGlyphCache<>(deviceSlots, devicePoolBytes), FONT_ID,
                    textSizePx, 0);
        }
        sessionId = random.nextInt() & 0xffff_ffffL;
        if (sessionId == 0) {
            sessionId = 1;
        }
        int flags = readerLink.timingFlags() | (readerLink.pageBurst() ? WireProtocol.SESSION_PAGE_BURST : 0);
        preparePages = generation != null && preparePages;
        if (generation != null) flags |= WireProtocol.SESSION_SD_GLYPHS;
        if (preparePages) flags |= WireProtocol.SESSION_PREPARE_PAGES;
        byte[] start = new byte[WireProtocol.START_SESSION_BYTES + (flags != 0 ? 1 : 0)
                + (generation == null ? 0 : generation.length)];
        if (flags != 0) {
            start[WireProtocol.START_SESSION_BYTES] = (byte) flags;
        }
        WireProtocol.writeLe32(start, 0, layout.originX);
        WireProtocol.writeLe32(start, 4, layout.originY);
        WireProtocol.writeLe16(start, 8, layout.columns);
        WireProtocol.writeLe16(start, 10, layout.rows);
        WireProtocol.writeLe16(start, 12, layout.cellWidth);
        WireProtocol.writeLe16(start, 14, layout.cellHeight);
        WireProtocol.writeLe16(start, 16, layout.baselineOffsetX);
        WireProtocol.writeLe16(start, 18, layout.baselineOffsetY);
        WireProtocol.writeLe32(start, 20, devicePoolBytes);
        WireProtocol.writeLe16(start, 24, deviceSlots);
        WireProtocol.writeLe16(start, 26, maxRawTokens);
        if (generation != null) {
            System.arraycopy(generation, 0, start, WireProtocol.START_SESSION_BYTES + 1, generation.length);
        }
        handler.removeCallbacks(readerSetupTimeout);
        handler.postDelayed(readerSetupTimeout, 15_000);
        if (!transport.send(WireProtocol.MessageType.START_SESSION, sessionId, 0, start)) {
            failReader();
        }
    }

    private void maybePushAnchor() {
        if (!sessionReady || hasDisplayedPage || pendingPage != null) {
            return;
        }
        readerPageIndex = anchorIndex();
        if (reading.pageAt(readerPageIndex) == null) return;
        readerAnchorIndex = readerPageIndex;
        pushPage(0, SystemClock.elapsedRealtime(), false, -1);
    }

    private void handlePageRequest(WireProtocol.AssemblyResult result, byte[] body, long callbackMs) {
        synchronized (flowLock) {
            int requestBytes = preparePages ? WireProtocol.NAV_REQUEST_BYTES : 9;
            if (result.sessionId != sessionId || body.length != requestBytes || !hasDisplayedPage || pendingPage != null) {
                failReader();
                return;
            }
            syncReadingLocked(session.getSnapshot());
            if (!hasDisplayedPage) return;

            GridPager.Page displayed = reading.pageAt(displayedReaderPageIndex);
            if (displayed == null || displayed.getPageId() != WireProtocol.readLe32(body, 0)
                    || displayedPushSeq != WireProtocol.readLe32(body, 4)) {
                failReader();
                return;
            }
            int offset = preparePages ? body[8] : body[8] == 0 ? -1 : body[8] & 0xff;
            int mode = preparePages ? body[9] & 0xff : 0;
            if (mode > 1 || offset == 0 || offset < -127 || offset > 127
                    || ((mode == 1 || !preparePages) && Math.abs(offset) != 1)) {
                failReader();
                return;
            }
            boolean prepare = mode == 1;
            int target = prepare || !preparePages ? reading.turnTarget(displayedReaderPageIndex, offset)
                    : reading.jumpTarget(displayedReaderPageIndex, offset);
            if (target == LocalReading.NO_PAGE) {
                sendNoPage(result.objectId);
                return;
            }
            readerPageIndex = target;
            pushPage(result.objectId, callbackMs, prepare, offset > 0 ? 0 : 1);
        }
    }

    private void sendNoPage(long requestId) {
        readerPageIndex = displayedReaderPageIndex;
        if (!transport.send(WireProtocol.MessageType.NO_PAGE, sessionId, requestId, new byte[0])) failReader();
    }

    private void pushPage(long requestId, long requestMs, boolean prepare, int neighbor) {
        GridPager.Page page = reading.pageAt(readerPageIndex);
        if (page == null || pendingPage != null || pushSeq == 0xffff_ffffL) {
            failReader();
            return;
        }
        long newPushSeq = pushSeq + 1;
        long encodeStart = SystemClock.elapsedRealtime();
        PageEncoder.EncodedPage legacy = null;
        WireProtocol.MessageType type;
        byte[] body;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && sdGlyphs != null) {
                type = WireProtocol.MessageType.PAGE_GLYPHS;
                PreparedPage cached = neighbor < 0 ? null : prepared[neighbor];
                if (cached != null && cached.page() == page) {
                    body = cached.body().clone();
                    WireProtocol.writeLe32(body, 0, requestId);
                } else {
                    body = sdGlyphs.encode(page, requestId, maxRawTokens,
                            prepare ? Math.min(maxObjectBytes, WireProtocol.MAX_PREPARED_PAGE_BYTES) : maxObjectBytes);
                    if (prepare) prepared[neighbor] = new PreparedPage(page, body);
                }
            } else {
                legacy = pageEncoder.encode(page, requestId, newPushSeq);
                if (legacy == null) {
                    failReader();
                    return;
                }
                boolean bitmap = legacy.getEncoding() == DeviceGlyphCache.Encoding.BITMAP;
                type = bitmap ? WireProtocol.MessageType.PAGE_BITMAP : WireProtocol.MessageType.PAGE_GRID;
                body = bitmap ? (bitmapSupported ? bitmapEncoder.encode(page, requestId) : null) : legacy.getBody();
            }
        } catch (AndroidSdGlyphs.MissingFaceException missing) {
            if (prepare) sendNoPage(requestId);
            else connectReader(readerPageIndex);
            return;
        } catch (IOException | IllegalStateException | IllegalArgumentException error) {
            if (prepare) {
                sendNoPage(requestId);
            } else {
                Log.w("Texpod", "Page encoding failed", error);
                failReader();
            }
            return;
        }
        if (body == null || body.length > maxObjectBytes) {
            failReader();
            return;
        }
        int fragments = transport.frameCount(body.length);
        if (prepare && fragments > WireProtocol.MAX_PREPARED_PAGE_FRAMES) {
            sendNoPage(requestId);
            return;
        }
        if (!prepare) {
            diagnostics.begin(newPushSeq, type, body.length, fragments, requestMs, encodeStart,
                    SystemClock.elapsedRealtime());
        }
        if (!transport.send(type, sessionId, newPushSeq, body)) {
            failReader();
            return;
        }
        pushSeq = newPushSeq;
        if (!prepare) reading.markDelivered(readerPageIndex);
        pendingPage = new PendingPage(page.getPageId(), legacy, prepare);
    }

    private void handlePagePrepared(WireProtocol.AssemblyResult result, byte[] body) {
        synchronized (flowLock) {
            if (!preparePages || result.sessionId != sessionId || result.objectId != pushSeq || body.length != 0
                    || pendingPage == null || !hasDisplayedPage) {
                failReader();
                return;
            }
            handler.removeCallbacks(readerDisplayTimeout);
            if (!pendingPage.prepare()) diagnostics.cancelPending();
            pendingPage = null;
            readerPageIndex = displayedReaderPageIndex;
            updateFlowRenderedLocked();
        }
    }

    private void handleDisplayed(WireProtocol.AssemblyResult result, byte[] body, long callbackMs) {
        if (result.sessionId != sessionId || readerLink == null || pendingPage == null || pendingPage.prepare()
                || result.objectId != pushSeq) {
            failReader();
            return;
        }
        PageEncoder.EncodedPage legacy = pendingPage.legacy();
        long epoch = legacy == null ? 0 : legacy.getPlan().getTargetEpoch();
        long revision = legacy == null ? 0 : legacy.getPlan().getTargetRevision();
        if (!WireProtocol.matchesDisplayed(body, readerLink.timingFlags(), pushSeq, pendingPage.pageId(), epoch,
                revision)) {
            failReader();
            return;
        }
        handler.removeCallbacks(readerDisplayTimeout);
        diagnostics.displayed(result.objectId, callbackMs, WireProtocol.readLe32(body, 16),
                WireProtocol.PageTimings.fromDisplayed(body));
        if (legacy != null) pageEncoder.commitDisplayed(legacy);
        synchronized (flowLock) {
            hasDisplayedPage = true;
            displayedPushSeq = result.objectId;
            displayedReaderPageIndex = readerPageIndex;
            readerAnchorIndex = readerPageIndex;
            pendingPage = null;
            updateFlowRenderedLocked();
        }
    }

    private void stopTransport() {
        handler.removeCallbacks(readerSetupTimeout);
        handler.removeCallbacks(readerDisplayTimeout);
        closeFontSetup();
        readerAnchorIndex = LocalReading.NO_PAGE;
        transportReady = false;
        deviceConfigured = false;
        if (transport != null) {
            transport.stop();
            transport = null;
        }
        diagnostics.cancelPending();
        sessionReady = false;
        hasDisplayedPage = false;
        displayedReaderPageIndex = 0;
        pendingPage = null;
        incoming = null;
        assembler.reset();
        pageEncoder = null;
        sdGlyphs = null;
        preparePages = false;
        prepared[0] = prepared[1] = null;
        sessionId = 0;
        displayedPushSeq = pushSeq = 0;
    }

    private void failReaderFont() {
        int retry = pendingPage != null ? readerPageIndex : anchorIndex();
        stopTransport();
        readerAnchorIndex = retry;
        useLayout(ReaderProfile.phonePreview(textSizePx), ReaderProfile.MAX_RAW_TOKENS);
        fontFailed = true;
        connectionFailure = null;
        connectionState = ConnectionState.ERROR;
    }

    private void failReader() {
        failReader(null);
    }

    private void failReader(BleTransport.Failure failure) {
        stopTransport();
        useLayout(ReaderProfile.phonePreview(textSizePx), ReaderProfile.MAX_RAW_TOKENS);
        connectionFailure = failure;
        connectionState = ConnectionState.ERROR;
    }

    public ReaderFontSetup.Progress getFontProgress() {
        return fontSetup == null ? null : fontSetup.progress();
    }

    public boolean hasFontError() { return fontFailed; }

    public boolean canReinstallFont() {
        return readerLink != null && readerLink.sdGlyphs()
                && (connectionState == ConnectionState.CONNECTED || (connectionState == ConnectionState.ERROR && fontFailed));
    }

    public void reinstallFont() {
        synchronized (flowLock) {
            if (canReinstallFont()) connectReader(hasDisplayedPage ? displayedReaderPageIndex : anchorIndex(), true);
        }
    }

    public BleTransport.Failure getConnectionFailure() {
        return connectionFailure;
    }
}
