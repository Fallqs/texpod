package com.crossmux.textplayer.tts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import com.crossmux.textplayer.MainActivity;
import com.crossmux.textplayer.R;
import com.crossmux.textplayer.TextPlayerController;
import com.crossmux.textplayer.session.TextPlayerSession;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class TextPlayerTtsService extends TextToSpeechService {
    public static final String ACTION_STOP = "com.crossmux.textplayer.action.STOP";

    private static final String CHANNEL_ID = "reading";
    private static final int NOTIFICATION_ID = 41;
    private static final String VOICE_NAME = "Texpod";

    private final SilentTtsStream audioStream = new SilentTtsStream();
    private TextPlayerController controller;
    private volatile String synthesizingCaller;

    @Override
    public void onCreate() {
        super.onCreate();
        controller = TextPlayerController.getInstance(this);
        controller.initialize();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            controller.clearReading();
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        audioStream.stop();
        controller.shutdown();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        String caller = resolveCaller(request.getCallerUid());
        synthesizingCaller = caller;
        try {
            audioStream.begin();
            TextPlayerController.IngestOutcome outcome = controller.ingest(caller, request.getCharSequenceText());
            TextPlayerSession.IngestResult result = outcome.getResult();
            switch (result) {
                case ACCEPTED:
                    updateNotification();
                    if (controller.awaitConsumption(outcome.getToken())) {
                        completeAudio(request, callback);
                    } else {
                        cancelAudio(callback);
                    }
                    break;
                case EMPTY:
                case INACTIVE:
                    completeAudio(request, callback);
                    break;
                case SOURCE_MISMATCH:
                    cancelAudio(callback);
                    break;
                case SESSION_ERROR:
                case QUEUE_FULL:
                case TEXT_LIMIT:
                case INTERRUPTED:
                    callback.error();
                    updateNotification();
                    break;
            }
        } finally {
            synthesizingCaller = null;
        }
    }

    @Override
    public void onStop() {
        audioStream.stop();
        controller.pauseFlow(synthesizingCaller);
    }

    @Override
    protected String[] onGetLanguage() {
        Locale locale = Locale.getDefault();
        return new String[] {locale.getLanguage(), locale.getCountry(), locale.getVariant()};
    }

    @Override
    protected int onIsLanguageAvailable(String language, String country, String variant) {
        return language == null || language.isEmpty() ? TextToSpeech.LANG_NOT_SUPPORTED
                : TextToSpeech.LANG_AVAILABLE;
    }

    @Override
    protected int onLoadLanguage(String language, String country, String variant) {
        return onIsLanguageAvailable(language, country, variant);
    }

    @Override
    public List<Voice> onGetVoices() {
        return Collections.singletonList(new Voice(VOICE_NAME, Locale.getDefault(), Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL, false, Collections.emptySet()));
    }

    @Override
    public String onGetDefaultVoiceNameFor(String language, String country, String variant) {
        return onIsLanguageAvailable(language, country, variant) == TextToSpeech.LANG_NOT_SUPPORTED
                ? null : VOICE_NAME;
    }

    @Override
    public int onIsValidVoiceName(String voiceName) {
        return VOICE_NAME.equals(voiceName) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override
    public int onLoadVoice(String voiceName) {
        return VOICE_NAME.equals(voiceName) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    private String resolveCaller(int callerUid) {
        String[] packages = getPackageManager().getPackagesForUid(callerUid);
        if (packages != null && packages.length > 0) {
            return packages[0];
        }
        String sharedName = getPackageManager().getNameForUid(callerUid);
        return sharedName == null || sharedName.isEmpty() ? "uid:" + callerUid : sharedName;
    }

    private void cancelAudio(SynthesisCallback callback) {
        if (callback.start(16_000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST);
            callback.done();
        }
    }

    private void completeAudio(SynthesisRequest request, SynthesisCallback callback) {
        audioStream.complete(request.getCharSequenceText(), request.getSpeechRate(),
                new SilentTtsStream.Sink() {
                    @Override
                    public int start(int sampleRate, int audioFormat, int channelCount) {
                        return callback.start(sampleRate, audioFormat, channelCount);
                    }

                    @Override
                    public int getMaxBufferSize() {
                        return callback.getMaxBufferSize();
                    }

                    @Override
                    public int audioAvailable(byte[] buffer, int offset, int length) {
                        return callback.audioAvailable(buffer, offset, length);
                    }

                    @Override
                    public void rangeStart(int frame, int start, int end) {
                        callback.rangeStart(frame, start, end);
                    }

                    @Override
                    public int done() {
                        return callback.done();
                    }

                    @Override
                    public void error() {
                        callback.error();
                    }
                });
    }

    private void updateNotification() {
        PendingIntent openPreview = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_text_player)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(openPreview)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException restricted) {
            Log.w("Texpod", "Unable to show the reading notification", restricted);
        }
    }
}
