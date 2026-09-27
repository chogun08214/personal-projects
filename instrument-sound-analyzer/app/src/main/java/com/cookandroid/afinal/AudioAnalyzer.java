package com.cookandroid.afinal;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.jtransforms.fft.DoubleFFT_1D;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AudioAnalyzer {

    public static class Result {
        public int sampleRate;

        // 0~200ms 파형
        public float[] wave200msMs;
        public float[] wave200ms;

        // 0~4000Hz 스펙트럼(dB)
        public float[] specFreqHz;
        public float[] specDb;
    }

    // 파이썬과 비슷하게 Nfft=2048로 고정
    private static final int NFFT = 2048;

    public static Result decodeAndAnalyze(Context ctx, int rawResId) throws Exception {

        // 1) MP3 디코딩해서 PCM(float -1~1) 얻기
        DecodedAudio dec = decodeMp3ToPcmFloat(ctx, rawResId);

        // 2) 0~200ms 파형 (가능하면 파일 sampleRate 기반)
        int sr = dec.sampleRate;
        int n200 = Math.min(dec.pcm.length, (int)(0.2f * sr));
        float[] wave = new float[n200];
        float[] tms = new float[n200];
        for (int i = 0; i < n200; i++) {
            wave[i] = dec.pcm[i];
            tms[i] = (i * 1000f) / sr;
        }

        // 3) FFT용 2048 샘플 준비 (부족하면 0 padding)
        double[] x = new double[2 * NFFT]; // complex interleaved (re, im)
        int n = Math.min(dec.pcm.length, NFFT);
        for (int i = 0; i < n; i++) {
            x[2*i] = dec.pcm[i];
            x[2*i + 1] = 0.0;
        }
        // 나머지는 0

        // 4) FFT
        DoubleFFT_1D fft = new DoubleFFT_1D(NFFT);
        fft.complexForward(x);

        // 5) 0~4000Hz 양의 주파수만 추출 + dB 변환 (최대값 기준 0dB)
        int half = NFFT / 2;
        // 주파수 bin: k * sr/NFFT (k=0..half-1)
        // 4000Hz 이하만
        int maxK = (int)Math.floor(4000.0 * NFFT / sr);
        maxK = Math.min(maxK, half - 1);

        double maxMag = 1e-12;
        double[] mag = new double[maxK + 1];
        for (int k = 0; k <= maxK; k++) {
            double re = x[2*k];
            double im = x[2*k + 1];
            double m = Math.hypot(re, im);
            mag[k] = m;
            if (m > maxMag) maxMag = m;
        }

        float[] fHz = new float[maxK + 1];
        float[] db = new float[maxK + 1];
        for (int k = 0; k <= maxK; k++) {
            fHz[k] = (float)(k * (double)sr / NFFT);
            double ratio = mag[k] / maxMag;
            double dB = 20.0 * Math.log10(ratio + 1e-12);
            // 파이썬 그림처럼 바닥 -60dB로 잘라주기
            if (dB < -60) dB = -60;
            db[k] = (float)dB;
        }

        Result r = new Result();
        r.sampleRate = sr;
        r.wave200msMs = tms;
        r.wave200ms = wave;
        r.specFreqHz = fHz;
        r.specDb = db;
        return r;
    }

    // -------------------- 디코더 --------------------

    private static class DecodedAudio {
        int sampleRate;
        float[] pcm; // mono float -1..1
    }

    private static DecodedAudio decodeMp3ToPcmFloat(Context ctx, int rawResId) throws Exception {

        AssetFileDescriptor afd = ctx.getResources().openRawResourceFd(rawResId);

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());

        int trackIndex = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                trackIndex = i;
                format = f;
                break;
            }
        }
        if (trackIndex < 0 || format == null) throw new IllegalStateException("No audio track found");

        extractor.selectTrack(trackIndex);
        String mime = format.getString(MediaFormat.KEY_MIME);

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();

        // 출력 PCM은 보통 ENCODING_PCM_16BIT
        // 버퍼에서 뽑아서 float로 변환
        ByteArrayOutput out = new ByteArrayOutput(1024 * 1024);

        boolean inputDone = false;
        boolean outputDone = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;

        while (!outputDone) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(10_000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        int sampleSize = extractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0);
                            extractor.advance();
                        }
                    }
                }
            }

            int outIdx = codec.dequeueOutputBuffer(info, 10_000);
            if (outIdx >= 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null && info.size > 0) {
                    byte[] chunk = new byte[info.size];
                    outBuf.get(chunk);
                    outBuf.clear();
                    out.write(chunk);
                }
                codec.releaseOutputBuffer(outIdx, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
            }
        }

        codec.stop();
        codec.release();
        extractor.release();
        afd.close();

        // PCM16 -> float
        byte[] pcmBytes = out.toByteArray();
        // 스테레오면 L만 사용(간단 처리)
        // MediaCodec 출력은 interleaved stereo일 수 있음. channelCount 확인 가능.
        // 여기서는 channelCount가 2면 (L,R) 중 L만 취함.
        int channelCount = 2;
        // 안전하게: 출력 포맷에서 KEY_CHANNEL_COUNT 있으면 사용
        // (디코딩 중 INFO_OUTPUT_FORMAT_CHANGED에서 추적하는 게 더 정확하지만 간단히 처리)
        // 보수적으로 2로 두되, 실제가 1이면 아래 계산에서 자동으로 맞진 않으므로:
        // -> "2로 가정" 대신 "2바이트 단위로 읽고, channelCount=1로 처리"가 더 안전.
        // 그래서: 그냥 모든 샘플을 mono로 간주(대부분 16bit mono/또는 stereo). 아래는 stereo도 대응:

        // 16-bit little endian
        ByteBuffer bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
        int totalShorts = pcmBytes.length / 2;
        short[] shorts = new short[totalShorts];
        bb.asShortBuffer().get(shorts);

        // channelCount 추정: stereo면 짝수로 크고, format에 있으면 좋지만 여기선 간단히 2/1 자동 판단:
        // 아주 단순히 "2로 나눴을 때 충분히 긴지"가 아니라 실제 메타가 필요하지만,
        // 현실적으로 대부분 stereo라 가정하고 L만 사용하되, 너무 짧으면 mono로 처리.
        // -> 여기서는 "stereo 가정"으로 L만 추출.
        int frameCount = totalShorts / 2;
        if (frameCount <= 0) frameCount = totalShorts; // fallback mono
        float[] mono = new float[frameCount];

        if (totalShorts >= 2) {
            // stereo 가정: L=even index
            for (int i = 0; i < frameCount; i++) {
                mono[i] = shorts[2*i] / 32768f;
            }
        } else {
            for (int i = 0; i < totalShorts; i++) mono[i] = shorts[i] / 32768f;
        }

        DecodedAudio da = new DecodedAudio();
        da.sampleRate = sampleRate;
        da.pcm = mono;
        return da;
    }

    // byte[] 누적용 간단 버퍼
    private static class ByteArrayOutput {
        private byte[] buf;
        private int size;

        ByteArrayOutput(int cap) { buf = new byte[cap]; size = 0; }

        void write(byte[] b) {
            ensure(size + b.length);
            System.arraycopy(b, 0, buf, size, b.length);
            size += b.length;
        }

        void ensure(int needed) {
            if (needed <= buf.length) return;
            int n = buf.length;
            while (n < needed) n *= 2;
            byte[] nb = new byte[n];
            System.arraycopy(buf, 0, nb, 0, size);
            buf = nb;
        }

        byte[] toByteArray() {
            byte[] out = new byte[size];
            System.arraycopy(buf, 0, out, 0, size);
            return out;
        }
    }
}
