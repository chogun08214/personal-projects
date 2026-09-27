package com.cookandroid.afinal;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private Spinner spn;
    private Button btnPlay, btnAnalyze;
    private LineChart chartWave, chartSpec;

    private MediaPlayer player;
    private ExecutorService exec = Executors.newSingleThreadExecutor();

    // raw 리소스 매핑
    private static class Item {
        final String name;
        final int resId;
        Item(String name, int resId){ this.name = name; this.resId = resId; }
    }

    private Item[] items;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        spn = findViewById(R.id.spnInstrument);
        btnPlay = findViewById(R.id.btnPlay);
        btnAnalyze = findViewById(R.id.btnAnalyze);
        chartWave = findViewById(R.id.chartWave);
        chartSpec = findViewById(R.id.chartSpec);

        items = new Item[]{
                new Item("Oboe", R.raw.oboe),
                new Item("Clarinet", R.raw.clarinet),
                new Item("Piano", R.raw.piano),
                new Item("Guitar", R.raw.guitar)
        };

        ArrayList<String> names = new ArrayList<>();
        for (Item it : items) names.add(it.name);
        spn.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));

        setupWaveChart();
        setupSpecChart();

        btnPlay.setOnClickListener(v -> {
            stopPlayer();
            int idx = spn.getSelectedItemPosition();
            player = MediaPlayer.create(this, items[idx].resId);
            if (player == null) {
                Toast.makeText(this, "MediaPlayer create failed", Toast.LENGTH_SHORT).show();
                return;
            }
            player.start();
        });

        btnAnalyze.setOnClickListener(v -> {
            int idx = spn.getSelectedItemPosition();
            int resId = items[idx].resId;

            Toast.makeText(this, "Analyzing...", Toast.LENGTH_SHORT).show();

            exec.execute(() -> {
                try {
                    AudioAnalyzer.Result r = AudioAnalyzer.decodeAndAnalyze(this, resId);

                    runOnUiThread(() -> {
                        plotWaveform200ms(r.wave200msMs, r.wave200ms);
                        plotSpectrum(r.specFreqHz, r.specDb);
                        Toast.makeText(this, "Done. sr=" + r.sampleRate + "Hz", Toast.LENGTH_SHORT).show();
                    });

                } catch (Exception e) {
                    runOnUiThread(() ->
                            Toast.makeText(this, "Analyze failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                    );
                }
            });
        });
    }

    private void setupWaveChart() {
        chartWave.getDescription().setEnabled(false);
        chartWave.getLegend().setEnabled(false);

        XAxis x = chartWave.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setAxisMinimum(0f);
        x.setAxisMaximum(200f); // 0~200ms

        YAxis left = chartWave.getAxisLeft();
        left.setDrawGridLines(false);
        chartWave.getAxisRight().setEnabled(false);
    }

    private void setupSpecChart() {
        chartSpec.getDescription().setEnabled(false);
        chartSpec.getLegend().setEnabled(false);

        XAxis x = chartSpec.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setAxisMinimum(0f);
        x.setAxisMaximum(4000f);

        YAxis left = chartSpec.getAxisLeft();
        left.setDrawGridLines(false);
        left.setAxisMinimum(-60f);
        left.setAxisMaximum(0f);
        left.setLabelCount(4, true);
        chartSpec.getAxisRight().setEnabled(false);
    }

    private void plotWaveform200ms(float[] tMs, float[] y) {
        ArrayList<Entry> entries = new ArrayList<>(y.length);
        for (int i = 0; i < y.length; i++) {
            entries.add(new Entry(tMs[i], y[i]));
        }
        LineDataSet ds = new LineDataSet(entries, "");
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        ds.setLineWidth(1f);

        chartWave.setData(new LineData(ds));

        chartWave.invalidate();
    }

    private void plotSpectrum(float[] fHz, float[] db) {
        ArrayList<Entry> entries = new ArrayList<>(db.length);
        for (int i = 0; i < db.length; i++) {
            entries.add(new Entry(fHz[i], db[i]));
        }
        LineDataSet ds = new LineDataSet(entries, "");
        ds.setDrawCircles(false);
        ds.setLineWidth(1f);

        chartSpec.setData(new LineData(ds));
        chartSpec.invalidate();
    }

    private void stopPlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayer();
        exec.shutdownNow();
    }
}
