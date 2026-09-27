package com.cookandroid.waterfall;

import org.jtransforms.fft.FloatFFT_1D;

import java.util.Arrays;

public class StftAnalyzer {

    private final SpectrogramRenderer renderer;

    private int sampleRate = 44100;
    private int channels = 1;

    private final int fftSize = 2048;
    private final int hopSize = 512;

    private FloatFFT_1D fft = new FloatFFT_1D(fftSize);
    private float[] window = new float[fftSize];

    private float[] monoRing = new float[fftSize * 8];
    private int ringWrite = 0;
    private int available = 0;

    private float displayGain = 1.0f;
    private int maxFreqHz = 5000;

    public StftAnalyzer(SpectrogramRenderer renderer) {
        this.renderer = renderer;
        makeHann(window);
    }

    public void onFormat(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = Math.max(1, channels);
        renderer.setSampleRate(sampleRate);
    }

    public void setDisplayGain(float gain) { this.displayGain = gain; }
    public void setMaxFreqHz(int hz) { this.maxFreqHz = hz; }

    public void pushPcm16(byte[] pcm16le) {
        // PCM16LE -> mono float
        int samples = pcm16le.length / 2;
        for (int i = 0; i < samples; i++) {
            int lo = pcm16le[2*i] & 0xFF;
            int hi = pcm16le[2*i + 1];
            short s = (short)((hi << 8) | lo);
            float v = s / 32768.0f;

            // stereo면 간단히 그냥 섞기(채널 수 정확한 다운믹스는 확장 가능)
            writeRing(v);
        }

        // hopSize 단위로 STFT 처리
        while (available >= fftSize) {
            float[] frame = readFrame(fftSize);
            processFrame(frame);
            dropFromRing(hopSize);
        }
    }

    private void processFrame(float[] frame) {
        // window
        for (int i = 0; i < fftSize; i++) frame[i] *= window[i];

        // real FFT를 complex로 넣기 위한 배열(2N)
        float[] fftBuf = new float[fftSize * 2];
        for (int i = 0; i < fftSize; i++) {
            fftBuf[2*i] = frame[i];
            fftBuf[2*i + 1] = 0f;
        }

        fft.complexForward(fftBuf);

        int maxBin = (int)Math.min(fftSize/2, (maxFreqHz * (long)fftSize) / sampleRate);
        if (maxBin < 8) maxBin = 8;

        float[] columnDb = new float[maxBin];
        float eps = 1e-7f;

        for (int k = 0; k < maxBin; k++) {
            float re = fftBuf[2*k];
            float im = fftBuf[2*k + 1];
            float mag = (float)Math.sqrt(re*re + im*im);
            float db = 20f * (float)Math.log10(mag + eps);
            // 보기 좋게 스케일
            columnDb[k] = db * displayGain;
        }

        renderer.pushSpectrogramColumn(columnDb);
    }

    private void makeHann(float[] w) {
        int N = w.length;
        for (int n = 0; n < N; n++) {
            w[n] = (float)(0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / (N - 1)));
        }
    }

    // ---- ring buffer helpers (단순 구현) ----
    private void writeRing(float v) {
        monoRing[ringWrite] = v;
        ringWrite = (ringWrite + 1) % monoRing.length;
        if (available < monoRing.length) {
            available++;
        } else {
            // overflow 시 가장 오래된 걸 버린다
            // (여기서는 hop 처리로 계속 빠지므로 보통 안 터짐)
        }
    }

    private float[] readFrame(int n) {
        float[] out = new float[n];
        int start = (ringWrite - available + monoRing.length) % monoRing.length;
        for (int i = 0; i < n; i++) {
            out[i] = monoRing[(start + i) % monoRing.length];
        }
        return out;
    }

    private void dropFromRing(int n) {
        available = Math.max(0, available - n);
    }
}
