package com.crossmux.textplayer.tts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Locale;

public final class TtsDataActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Locale locale = Locale.getDefault().getLanguage().isEmpty() ? Locale.ENGLISH : Locale.getDefault();
        String language = locale.getISO3Language();
        if (!locale.getCountry().isEmpty()) {
            language += "-" + locale.getISO3Country();
        }
        ArrayList<String> available = new ArrayList<>();
        available.add(language);
        Intent result = new Intent();
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available);
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, new ArrayList<>());
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result);
        finish();
    }
}
