package com.cookandroid.waterfall;

import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private SpectrogramSurfaceView glView;
    private Mp3DecodePlayer player;
    private StftAnalyzer analyzer;

    private boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        glView = findViewById(R.id.glView);

        // 분석기(FFT) + 렌더러 연결
        analyzer = new StftAnalyzer(glView.getRenderer());
        player = new Mp3DecodePlayer(this, analyzer);

        Button btnMic = findViewById(R.id.btnMic);
        Button btnCross = findViewById(R.id.btnCross);
        Button btnSpec = findViewById(R.id.btnSpec);

        SeekBar seekAmp = findViewById(R.id.seekAmp);
        SeekBar seekFreq = findViewById(R.id.seekFreq);

        btnMic.setOnClickListener(v -> {
            if (!running) {
                running = true;
                btnMic.setText("STOP");
                player.start(R.raw.oboe); // res/raw/oboe.mp3
            } else {
                running = false;
                btnMic.setText("MIC");
                player.stop();
            }
        });

        // CROSS / SPECTROGRAM은 UI 토글용으로만 일단 유지(원하면 확장)
        btnCross.setOnClickListener(v -> glView.getRenderer().toggleCrossSection());
        btnSpec.setOnClickListener(v -> glView.getRenderer().toggleSpectrogram());

        seekAmp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float gain = Math.max(0.05f, progress / 100.0f); // 0.05~2.0
                analyzer.setDisplayGain(gain);
                glView.getRenderer().setZGain(gain);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekFreq.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                analyzer.setMaxFreqHz(Math.max(200, progress)); // 최소 200Hz
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        player.stop();
    }
}
