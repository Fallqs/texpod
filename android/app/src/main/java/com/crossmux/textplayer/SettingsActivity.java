package com.crossmux.textplayer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.crossmux.textplayer.layout.ReaderProfile;

public final class SettingsActivity extends Activity {
    private static final String STATE_FONT_SIZE = "pendingFontSize";
    private SeekBar fontSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        SystemBarInsets.applyTo(findViewById(R.id.settings_scroll));
        TextPlayerController controller = TextPlayerController.getInstance(this);
        int currentSize = controller.getTextSizePx();
        int pendingSize = savedInstanceState == null ? currentSize
                : savedInstanceState.getInt(STATE_FONT_SIZE, currentSize);
        TextView value = findViewById(R.id.font_size_value);
        TextView sample = findViewById(R.id.font_sample);
        fontSize = findViewById(R.id.font_size_slider);
        fontSize.setMin(ReaderProfile.MIN_TEXT_SIZE_PX);
        fontSize.setMax(ReaderProfile.MAX_TEXT_SIZE_PX);
        fontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateSample(value, sample, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        fontSize.setProgress(pendingSize);
        updateSample(value, sample, fontSize.getProgress());
        findViewById(R.id.settings_back).setOnClickListener(view -> finish());
        findViewById(R.id.apply_font_size).setOnClickListener(view -> {
            controller.setTextSizePx(fontSize.getProgress());
            finish();
        });
        findViewById(R.id.open_tts_settings).setOnClickListener(view -> openSystemTtsSettings());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_FONT_SIZE, fontSize.getProgress());
    }

    private void updateSample(TextView value, TextView sample, int size) {
        value.setText(getString(R.string.font_size_value, size));
        sample.setTextSize(size);
    }

    private void openSystemTtsSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, R.string.error_tts_settings, Toast.LENGTH_LONG).show();
        }
    }
}
