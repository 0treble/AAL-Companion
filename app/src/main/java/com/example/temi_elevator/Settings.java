package com.example.temi_elevator;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.constants.Gender;
import com.robotemi.sdk.voice.model.TtsVoice;

public class Settings extends AppCompatActivity {
    private Robot temi;
    private SeekBar speedSeekBar;
    private SeekBar pitchSeekBar;
    private TextView speedValue;
    private TextView pitchValue;
    private RadioGroup genderRadioGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_window);

        temi = Robot.getInstance();

        speedSeekBar = findViewById(R.id.speedSeekBar);
        pitchSeekBar = findViewById(R.id.pitchSeekBar);
        speedValue = findViewById(R.id.speedValue);
        pitchValue = findViewById(R.id.pitchValue);
        genderRadioGroup = findViewById(R.id.genderRadioGroup);

        loadCurrentSettings();

        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float speed = getSpeedFromProgress(progress);
                speedValue.setText(String.valueOf(speed));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        pitchSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int pitch = getPitchFromProgress(progress);
                pitchValue.setText(String.valueOf(pitch));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        Button saveButton = findViewById(R.id.saveButton);
        saveButton.setOnClickListener(v -> saveSettings());

        Button backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> finish());
    }

    /*private void getTts() {
        try {
            TtsVoice ttsVoice = temi.getTtsVoice();
            showTranscription("Get TTS Voice result: " + ttsVoice);
        } catch (Exception e) {
            Log.e(TAG, "getTtsVoice() error", e);
        }
    }

    private void setTts( gender, float speed, int pitch) {
        TtsVoice ttsVoice = new TtsVoice(gender, speed, pitch);
        try {
            boolean result = temi.setTtsVoice(ttsVoice);
            showTranscription("Set TTS Voice result: " + result);
        } catch (Exception e) {
            Log.e(TAG, "setTtsVoice() error", e);
        }
    }*/


    private void loadCurrentSettings() {
        TtsVoice ttsVoice = temi.getTtsVoice();
        if (ttsVoice != null) {
            int speedProgress = getProgressFromSpeed(ttsVoice.getSpeed());
            int pitchProgress = getProgressFromPitch(ttsVoice.getPitch());

            speedSeekBar.setProgress(speedProgress);
            pitchSeekBar.setProgress(pitchProgress);

            speedValue.setText(String.valueOf(ttsVoice.getSpeed()));
            pitchValue.setText(String.valueOf(ttsVoice.getPitch()));
        }
    }

    private float getSpeedFromProgress(int progress) {
        return (progress + 5) / 10f;
    }

    private int getPitchFromProgress(int progress) {
        return progress - 10;
    }

    private int getProgressFromSpeed(float speed) {
        return (int) (speed * 10) - 5;
    }

    private int getProgressFromPitch(int pitch) {
        return pitch + 10;
    }

    private void saveSettings() {
        float speed = getSpeedFromProgress(speedSeekBar.getProgress());
        int pitch = getPitchFromProgress(pitchSeekBar.getProgress());
        Gender gender = Gender.UNKNOWN;

        int checkedGenderId = genderRadioGroup.getCheckedRadioButtonId();
        if (checkedGenderId == R.id.maleRadioButton) {
            gender = Gender.MALE;
        }else if (checkedGenderId == R.id.femaleRadioButton) {
            gender = Gender.FEMALE;
        }

        Intent resultIntent = new Intent();
        resultIntent.putExtra("SPEED", speed);
        resultIntent.putExtra("PITCH", pitch);
        resultIntent.putExtra("GENDER", gender.name());
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}
