package com.cookandroid.waterfall;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.*;
import android.os.Process;

import java.io.IOException;
import java.nio.ByteBuffer;

public class Mp3DecodePlayer {

    private final Context context;
    private final StftAnalyzer analyzer;

    private Thread thread;
    private volatile boolean stop = false;

    public Mp3DecodePlayer(Context context, StftAnalyzer analyzer) {
        this.context = context;
        this.analyzer = analyzer;
    }

    public void start(int rawResId) {
        stop = false;
        thread = new Thread(() -> decodeAndPlay(rawResId), "Mp3DecodePlayer");
        thread.start();
    }

    public void stop() {
        stop = true;
        if (thread != null) {
            try { thread.join(600); } catch (InterruptedException ignored) {}
            thread = null;
        }
    }

    private void decodeAndPlay(int rawResId) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        AudioTrack track = null;

        try {
            AssetFileDescriptor afd = context.getResources().openRawResourceFd(rawResId);
            extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();

            int audioTrackIndex = -1;
            MediaFormat format = null;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    format = f;
                    break;
                }
            }
            if (audioTrackIndex < 0 || format == null) return;

            extractor.selectTrack(audioTrackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int channelOut = (channels == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;

            int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelOut, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    new AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(channelOut).build(),
                    Math.max(minBuf, 8192),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );
            track.play();

            analyzer.onFormat(sampleRate, channels);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!stop && !outputDone) {
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIndex);
                        if (inBuf != null) {
                            int sampleSize = extractor.readSampleData(inBuf, 0);
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long pts = extractor.getSampleTime();
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    ByteBuffer outBuf = decoder.getOutputBuffer(outIndex);
                    if (outBuf != null && info.size > 0) {
                        byte[] pcm = new byte[info.size];
                        outBuf.get(pcm);
                        outBuf.clear();

                        // 1) 재생
                        track.write(pcm, 0, pcm.length);

                        // 2) 분석 (PCM16LE)
                        analyzer.pushPcm16(pcm);
                    }
                    decoder.releaseOutputBuffer(outIndex, false);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            try { if (decoder != null) decoder.stop(); } catch (Exception ignored) {}
            try { if (decoder != null) decoder.release(); } catch (Exception ignored) {}
            try { if (track != null) { track.stop(); track.release(); } } catch (Exception ignored) {}
        }
    }
}
