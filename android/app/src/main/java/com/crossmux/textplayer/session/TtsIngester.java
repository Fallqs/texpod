package com.crossmux.textplayer.session;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class TtsIngester {
    private static final int MAX_DETACHED_SOURCES = 4;

    private final TextPlayerSession session;
    private final Object lock = new Object();
    private final Set<String> detachedSources = new LinkedHashSet<>();
    private String activeSource;

    public TtsIngester(TextPlayerSession session) {
        this.session = session;
    }

    public TextPlayerSession.IngestResult ingest(String callerPackage, CharSequence text) {
        if (text == null || text.length() == 0) {
            return TextPlayerSession.IngestResult.EMPTY;
        }
        if (callerPackage == null || callerPackage.isEmpty()) {
            return TextPlayerSession.IngestResult.SOURCE_MISMATCH;
        }
        synchronized (lock) {
            if (activeSource != null && !activeSource.equals(callerPackage)
                    && detachedSources.contains(callerPackage)) {
                return TextPlayerSession.IngestResult.SOURCE_MISMATCH;
            }
            if (activeSource != null && !activeSource.equals(callerPackage)) {
                detach(activeSource);
                activeSource = callerPackage;
                session.start();
            } else {
                TextPlayerSession.Snapshot snapshot = session.getSnapshot();
                if (activeSource == null || snapshot.getState() != TextPlayerSession.State.ACTIVE) {
                    activeSource = callerPackage;
                    detachedSources.clear();
                    session.start();
                }
            }
            TextPlayerSession.IngestResult result = session.submit(callerPackage, text);
            if (result == TextPlayerSession.IngestResult.INACTIVE
                    || result == TextPlayerSession.IngestResult.SOURCE_MISMATCH
                    || result == TextPlayerSession.IngestResult.SESSION_ERROR) {
                session.start();
                result = session.submit(callerPackage, text);
            }
            return result;
        }
    }

    public boolean pause(String callerPackage) {
        synchronized (lock) {
            if (!Objects.equals(activeSource, callerPackage)) {
                return false;
            }
            activeSource = null;
            detachedSources.clear();
            return true;
        }
    }

    public void loadSample(String text) {
        synchronized (lock) {
            activeSource = null;
            detachedSources.clear();
            session.start();
            session.submit("texpod.sample", text);
        }
    }

    public void clear() {
        synchronized (lock) {
            activeSource = null;
            detachedSources.clear();
            session.stop();
        }
    }

    private void detach(String source) {
        if (detachedSources.size() == MAX_DETACHED_SOURCES) {
            detachedSources.remove(detachedSources.iterator().next());
        }
        detachedSources.add(source);
    }
}
