package com.minivoice.ime.asr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地识别，两段式。
 *
 * 说话时用小模型流式解码，边说边把字推到输入框；
 * 松手后用 SenseVoice 把这整段音频重算一遍，出带标点的准确终稿。
 * 两个模型都在手机上算，不联网。
 */
public class LocalAsrEngine implements AsrEngine {

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Callback callback;

    private OnlineRecognizer streamRec;
    private OnlineStream stream;
    private OfflineRecognizer finalRec;

    private final Object lock = new Object();
    private boolean streamReady = false;
    private boolean finishing = false;
    private boolean canceled = false;
    private boolean delivered = false;
    private String lastPartial = "";

    /** 攒完整的录音，松手后交给高精度模型。 */
    private final ByteArrayOutputStream pcmAll = new ByteArrayOutputStream();

    /** 流式模型还没加载完时先攒着，加载完一次性补进去。 */
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();

    public LocalAsrEngine(Context context) {
        this.ctx = context.getApplicationContext();
    }

    @Override
    public void start(Callback cb) {
        this.callback = cb;
        worker.execute(() -> {
            try {
                if (!LocalModelManager.streamReady(ctx)) {
                    error("本地模型还没下载，去设置页点一下下载");
                    return;
                }
                OnlineRecognizer rec = buildStreamRecognizer();
                OnlineStream s = rec.createStream();
                synchronized (lock) {
                    if (canceled) {
                        s.release();
                        rec.release();
                        return;
                    }
                    streamRec = rec;
                    stream = s;
                    streamReady = true;
                    while (!pending.isEmpty()) {
                        acceptLocked(pending.pollFirst());
                    }
                }
            } catch (Throwable t) {
                error("本地模型加载失败：" + msg(t));
                return;
            }
            // 高精度模型加载慢一点，放后面，不影响边说边出字
            try {
                if (LocalModelManager.finalReady(ctx)) {
                    OfflineRecognizer fr = buildFinalRecognizer();
                    synchronized (lock) {
                        if (canceled) {
                            fr.release();
                            return;
                        }
                        finalRec = fr;
                    }
                }
            } catch (Throwable ignored) {
                // 高精度模型起不来就只用流式结果，不至于整条链路废掉
            }
        });
    }

    private OnlineRecognizer buildStreamRecognizer() {
        File dir = LocalModelManager.dir(ctx);
        String[] files = LocalModelManager.streamFiles(ctx);
        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(new File(dir, files[0]).getAbsolutePath())
                .setDecoder(new File(dir, files[1]).getAbsolutePath())
                .setJoiner(new File(dir, files[2]).getAbsolutePath())
                .build();
        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(new File(dir, files[3]).getAbsolutePath())
                .setNumThreads(2)
                .setModelType("zipformer")
                .setDebug(false)
                .build();
        FeatureConfig featureConfig = FeatureConfig.builder()
                .setSampleRate(16000)
                .setFeatureDim(80)
                .build();
        EndpointConfig endpointConfig = EndpointConfig.builder()
                .setRule1(EndpointRule.builder()
                        .setMustContainNonSilence(false)
                        .setMinTrailingSilence(2.4f)
                        .setMinUtteranceLength(0)
                        .build())
                .setRule2(EndpointRule.builder()
                        .setMustContainNonSilence(true)
                        .setMinTrailingSilence(1.4f)
                        .setMinUtteranceLength(0)
                        .build())
                .setRule3(EndpointRule.builder()
                        .setMustContainNonSilence(false)
                        .setMinTrailingSilence(0f)
                        .setMinUtteranceLength(20f)
                        .build())
                .build();
        OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                .setFeatureConfig(featureConfig)
                .setOnlineModelConfig(modelConfig)
                .setEndpointConfig(endpointConfig)
                .setEnableEndpoint(false)
                .setDecodingMethod("greedy_search")
                .build();
        return new OnlineRecognizer(config);
    }

    private OfflineRecognizer buildFinalRecognizer() {
        if (LocalModelManager.isHq(ctx)) return buildWhisperRecognizer();
        return buildSenseVoiceRecognizer();
    }

    /**
     * 轻量档终稿用 SenseVoice。
     * language 一定要给 auto，写死 zh 的话说英文会被当成中文谐音，
     * get up 会被拼成 Gt upub 这种鬼东西。
     */
    private OfflineRecognizer buildSenseVoiceRecognizer() {
        File dir = LocalModelManager.finalDir(ctx);
        String[] files = LocalModelManager.finalFiles(ctx);
        OfflineSenseVoiceModelConfig sv = OfflineSenseVoiceModelConfig.builder()
                .setModel(new File(dir, files[0]).getAbsolutePath())
                .setLanguage("auto")
                .setInverseTextNormalization(true)
                .build();
        OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                .setSenseVoice(sv)
                .setTokens(new File(dir, files[1]).getAbsolutePath())
                .setNumThreads(2)
                .setDebug(false)
                .build();
        OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig)
                .setDecodingMethod("greedy_search")
                .build();
        return new OfflineRecognizer(config);
    }

    /**
     * 高精度档终稿用 whisper small。英文是它的强项，中文也不差。
     * language 留空让它自己判，中英混说的时候才不会把英文硬翻成中文。
     */
    private OfflineRecognizer buildWhisperRecognizer() {
        File dir = LocalModelManager.finalDir(ctx);
        String[] files = LocalModelManager.finalFiles(ctx);
        OfflineWhisperModelConfig whisper = OfflineWhisperModelConfig.builder()
                .setEncoder(new File(dir, files[0]).getAbsolutePath())
                .setDecoder(new File(dir, files[1]).getAbsolutePath())
                .setLanguage("")
                .setTask("transcribe")
                .setTailPaddings(1000)
                .build();
        OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                .setWhisper(whisper)
                .setTokens(new File(dir, files[2]).getAbsolutePath())
                .setNumThreads(2)
                .setDebug(false)
                .build();
        OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig)
                .setDecodingMethod("greedy_search")
                .build();
        return new OfflineRecognizer(config);
    }

    @Override
    public void feed(byte[] pcm, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(pcm, 0, copy, 0, length);
        synchronized (lock) {
            if (canceled) return;
            pcmAll.write(copy, 0, copy.length);
            if (!streamReady) {
                if (pending.size() > 600) pending.pollFirst();
                pending.addLast(copy);
                return;
            }
            acceptLocked(copy);
        }
    }

    /** 已经在锁里了。 */
    private void acceptLocked(byte[] pcm) {
        try {
            stream.acceptWaveform(byte2float(pcm), 16000);
            while (streamRec.isReady(stream)) {
                streamRec.decode(stream);
            }
            OnlineRecognizerResult r = streamRec.getResult(stream);
            String text = r == null ? "" : r.getText();
            if (!TextUtils.isEmpty(text) && !text.equals(lastPartial) && !finishing) {
                lastPartial = text;
                final String out = text;
                main.post(() -> {
                    Callback cb = callback;
                    if (cb != null && !delivered) cb.onPartial(out);
                });
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void finish() {
        synchronized (lock) {
            if (finishing) return;
            finishing = true;
        }
        worker.execute(() -> {
            String streamText = "";
            String finalText = "";
            synchronized (lock) {
                if (canceled) return;
                if (streamRec != null && stream != null) {
                    try {
                        stream.inputFinished();
                        while (streamRec.isReady(stream)) {
                            streamRec.decode(stream);
                        }
                        OnlineRecognizerResult r = streamRec.getResult(stream);
                        streamText = r == null ? "" : r.getText();
                    } catch (Throwable ignored) {
                    }
                }
            }
            // 用整段 PCM 跑高精度模型，拿到带标点的终稿
            if (finalRec != null) {
                try {
                    byte[] all;
                    synchronized (lock) {
                        all = pcmAll.toByteArray();
                    }
                    if (all.length > 3200) {
                        OfflineStream os = finalRec.createStream();
                        os.acceptWaveform(byte2float(all), 16000);
                        finalRec.decode(os);
                        OfflineRecognizerResult r = finalRec.getResult(os);
                        finalText = r == null ? "" : r.getText();
                        os.release();
                    }
                } catch (Throwable ignored) {
                }
            }
            String text = TextUtils.isEmpty(finalText) ? streamText : finalText;
            deliver(text);
            releaseModel();
        });
    }

    private void deliver(String text) {
        main.post(() -> {
            Callback cb = callback;
            if (cb == null || delivered) return;
            delivered = true;
            if (TextUtils.isEmpty(text)) {
                cb.onError("没听清，再说一次");
            } else {
                cb.onFinal(text);
            }
        });
    }

    @Override
    public void cancel() {
        synchronized (lock) {
            canceled = true;
            streamReady = false;
            pending.clear();
            pcmAll.reset();
        }
        releaseModel();
        callback = null;
    }

    private void releaseModel() {
        synchronized (lock) {
            try {
                if (stream != null) stream.release();
            } catch (Throwable ignored) {
            }
            stream = null;
            try {
                if (streamRec != null) streamRec.release();
            } catch (Throwable ignored) {
            }
            streamRec = null;
            try {
                if (finalRec != null) finalRec.release();
            } catch (Throwable ignored) {
            }
            finalRec = null;
            streamReady = false;
        }
        // 每次录音都是新引擎，用完把线程也收掉，不然按一次多一条线程
        try {
            worker.shutdown();
        } catch (Throwable ignored) {
        }
    }

    private void error(String message) {
        Callback cb = callback;
        if (cb != null) {
            main.post(() -> {
                if (!delivered) {
                    delivered = true;
                    cb.onError(message);
                }
            });
        }
        releaseModel();
    }

    private static String msg(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static float[] byte2float(byte[] pcm) {
        int n = pcm.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = pcm[2 * i] & 0xff;
            int hi = pcm[2 * i + 1] << 8;
            out[i] = (lo | hi) / 32768f;
        }
        return out;
    }
}
