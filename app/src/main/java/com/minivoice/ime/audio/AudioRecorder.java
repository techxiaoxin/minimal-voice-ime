package com.minivoice.ime.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * 16kHz 单声道 PCM16 采集。蓝牙麦在 SCO 通道上走 VOICE_COMMUNICATION，
 * 没连耳机就回落到本机麦克风。
 */
public class AudioRecorder {

    public static final int SAMPLE_RATE = 16000;

    public interface Listener {
        void onPcm(byte[] data, int length);

        void onError(String message);
    }

    private AudioRecord record;
    private Thread worker;
    private volatile boolean running = false;
    private Listener listener;

    private AudioManager audioManager;
    private boolean mutedMusic;
    private boolean mutedSystem;

    @SuppressLint("MissingPermission")
    public boolean start(Context context, boolean bluetoothActive, Listener listener) {
        this.listener = listener;
        this.audioManager = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        int source = bluetoothActive
                ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                : MediaRecorder.AudioSource.MIC;

        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = SAMPLE_RATE * 2;
        int bufferSize = minBuf * 2;

        try {
            record = new AudioRecord(source, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        } catch (Exception e) {
            if (listener != null) listener.onError("无法创建录音器：" + e.getMessage());
            return false;
        }

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            record = null;
            if (listener != null) listener.onError("录音器初始化失败");
            return false;
        }

        running = true;
        record.startRecording();
        muteOutput();

        worker = new Thread(() -> {
            byte[] chunk = new byte[1024];
            while (running) {
                int read = record.read(chunk, 0, chunk.length);
                if (read > 0 && listener != null) {
                    listener.onPcm(chunk, read);
                } else if (read < 0) {
                    if (listener != null) listener.onError("录音读取失败：" + read);
                    break;
                }
            }
        }, "voice-capture");
        worker.start();
        return true;
    }

    /** 录音期间把外放和按键音静掉，不然喇叭里的声音会被麦收进去变成回声。 */
    private void muteOutput() {
        if (audioManager == null) return;
        try {
            if (!audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true);
                mutedMusic = true;
            }
            if (!audioManager.isStreamMute(AudioManager.STREAM_SYSTEM)) {
                audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, true);
                mutedSystem = true;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 录完把之前自己静掉的还原，本来是静音的就不去动它。 */
    private void unmuteOutput() {
        if (audioManager == null) return;
        try {
            if (mutedMusic) audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false);
            if (mutedSystem) audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, false);
        } catch (Throwable ignored) {
        }
        mutedMusic = false;
        mutedSystem = false;
    }

    public void stop() {
        running = false;
        unmuteOutput();
        if (worker != null) {
            try {
                worker.join(300);
            } catch (InterruptedException ignored) {
            }
            worker = null;
        }
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
            record.release();
            record = null;
        }
    }

    public boolean isRunning() {
        return running;
    }
}
