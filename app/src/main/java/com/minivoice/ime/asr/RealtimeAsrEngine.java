package com.minivoice.ime.asr;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.Proxy;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 百炼实时语音识别。边录边把 PCM 小块发上去，服务端不断回中间结果，
 * 说完再 commit 一次拿最终稿。整条链路走 WebSocket，比录完再整段上传快得多。
 */
public class RealtimeAsrEngine implements AsrEngine {

    private static final String TAG = "RealtimeAsr";

    /** OpenAI Realtime 风格的会话地址，模型放在 query 里。 */
    public static final String URL =
            "wss://private-space.example.com/api-ws/v1/realtime"
                    + "?model=" + AsrConfig.STREAM_MODEL;

    /** 一次发 100 毫秒的音频，16k 16bit 单声道就是 3200 字节。 */
    private static final int CHUNK_BYTES = 3200;
    private static final long FINISH_TIMEOUT_MS = 12000L;

    private final AsrConfig config;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .protocols(Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
            .proxy(Proxy.NO_PROXY)
            .build();

    private Callback callback;
    private WebSocket socket;
    private volatile boolean ready;
    private volatile boolean cancelled;
    private volatile boolean finishing;
    private volatile boolean gotFinal;

    /** 全部原始音频，WebSocket 挂掉时还能退回整段上传。 */
    private final ByteArrayOutputStream all = new ByteArrayOutputStream();
    /** 攒到 100 毫秒再发，减少消息条数。 */
    private final ByteArrayOutputStream chunk = new ByteArrayOutputStream();
    /** 会话还没建好时先攒着，建好再补发，避免开头几个字丢掉。 */
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private volatile boolean finishRequested;

    private Runnable finishTimeout;

    public RealtimeAsrEngine(AsrConfig config) {
        this.config = config;
    }

    @Override
    public void start(Callback cb) {
        callback = cb;
        cancelled = false;
        ready = false;
        finishing = false;
        gotFinal = false;
        synchronized (all) {
            all.reset();
        }
        synchronized (chunk) {
            chunk.reset();
        }
        synchronized (pending) {
            pending.reset();
        }
        finishRequested = false;

        if (TextUtils.isEmpty(config.apiKey)) {
            emitError("还没配 API Key");
            return;
        }

        Request request = new Request.Builder()
                .url(URL)
                .addHeader("Authorization", "Bearer " + config.apiKey)
                .build();
        socket = client.newWebSocket(request, new WsListener());
    }

    @Override
    public void feed(byte[] pcm, int length) {
        if (cancelled || pcm == null || length <= 0) return;
        synchronized (all) {
            all.write(pcm, 0, length);
        }
        if (!ready) {
            synchronized (pending) {
                pending.write(pcm, 0, length);
            }
            return;
        }
        appendAudio(pcm, length);
    }

    private void appendAudio(byte[] pcm, int length) {
        if (finishing) return;
        synchronized (chunk) {
            chunk.write(pcm, 0, length);
            if (chunk.size() >= CHUNK_BYTES) {
                sendAudio(chunk.toByteArray());
                chunk.reset();
            }
        }
    }

    @Override
    public void finish() {
        finishing = true;
        finishRequested = true;
        if (!ready) {
            // 会话还在建，等 session.updated 回来再收尾
            armFinishTimeout();
            return;
        }
        flushAndCommit();
    }

    /** 把攒下的音频全发完，再 commit 一次收尾。 */
    private void flushAndCommit() {
        synchronized (pending) {
            if (pending.size() > 0) {
                byte[] p = pending.toByteArray();
                pending.reset();
                synchronized (chunk) {
                    chunk.write(p, 0, p.length);
                }
            }
        }
        synchronized (chunk) {
            if (chunk.size() > 0) {
                sendAudio(chunk.toByteArray());
                chunk.reset();
            }
        }
        sendJson("{\"type\":\"input_audio_buffer.commit\"}");
        armFinishTimeout();
    }

    @Override
    public void cancel() {
        cancelled = true;
        clearFinishTimeout();
        if (socket != null) {
            socket.close(1000, "cancel");
            socket = null;
        }
    }

    // ---------------- 内部 ----------------

    private void armFinishTimeout() {
        clearFinishTimeout();
        finishTimeout = () -> {
            if (cancelled || gotFinal) return;
            emitError("识别超时了，再说一次");
            if (socket != null) {
                socket.close(1000, "timeout");
                socket = null;
            }
        };
        main.postDelayed(finishTimeout, FINISH_TIMEOUT_MS);
    }

    private void clearFinishTimeout() {
        if (finishTimeout != null) {
            main.removeCallbacks(finishTimeout);
            finishTimeout = null;
        }
    }

    private void sendAudio(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        if (!ready || socket == null) return;
        String b64 = Base64.encodeToString(pcm, Base64.NO_WRAP);
        JsonObject o = new JsonObject();
        o.addProperty("type", "input_audio_buffer.append");
        o.addProperty("audio", b64);
        socket.send(o.toString());
    }

    private void sendJson(String json) {
        if (socket == null) return;
        socket.send(json);
    }

    private void onOpenSendSession() {
        // 手动 commit，不让服务端自己按静音断句，避免说到一半被切段
        String session = "{\"type\":\"session.update\",\"session\":{"
                + "\"input_audio_format\":\"pcm\","
                + "\"input_audio_transcription\":{\"model\":\"" + AsrConfig.STREAM_MODEL + "\"},"
                + "\"turn_detection\":null,"
                + "\"modalities\":[\"text\"]}}";
        sendJson(session);
    }

    /** WebSocket 连不上或者中途断了，退回一次性整段上传，保证还能出字。 */
    private void fallback(String reason) {
        if (cancelled || gotFinal) return;
        Log.w(TAG, "stream fallback: " + reason);
        byte[] pcm;
        synchronized (all) {
            pcm = all.toByteArray();
        }
        if (pcm.length < 3200) {
            emitError("说得太短了，按住多说一会儿");
            return;
        }
        AsrEngine legacy = new BailianAsrEngine(config);
        legacy.start(new AsrEngine.Callback() {
            @Override
            public void onPartial(String text) {
                emitPartial(text);
            }

            @Override
            public void onFinal(String text) {
                gotFinal = true;
                emitFinal(text);
            }

            @Override
            public void onError(String message) {
                emitError(message);
            }
        });
        legacy.feed(pcm, pcm.length);
        legacy.finish();
    }

    private class WsListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            socket = webSocket;
            onOpenSendSession();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JsonObject j = JsonParser.parseString(text).getAsJsonObject();
                String type = j.has("type") ? j.get("type").getAsString() : "";

                if ("session.updated".equals(type) || "session.created".equals(type)) {
                    boolean first = !ready;
                    ready = true;
                    if (first) {
                        // 把开会话之前攒的音频补上
                        synchronized (pending) {
                            if (pending.size() > 0) {
                                byte[] p = pending.toByteArray();
                                pending.reset();
                                synchronized (chunk) {
                                    chunk.write(p, 0, p.length);
                                    if (chunk.size() >= CHUNK_BYTES) {
                                        sendAudio(chunk.toByteArray());
                                        chunk.reset();
                                    }
                                }
                            }
                        }
                    }
                    if (finishRequested) flushAndCommit();
                    return;
                }
                if ("conversation.item.input_audio_transcription.text".equals(type)) {
                    String confirmed = j.has("text") ? j.get("text").getAsString() : "";
                    String stash = j.has("stash") ? j.get("stash").getAsString() : "";
                    String partial = (confirmed == null ? "" : confirmed)
                            + (stash == null ? "" : stash);
                    if (!TextUtils.isEmpty(partial)) emitPartial(partial);
                    return;
                }
                if ("conversation.item.input_audio_transcription.completed".equals(type)) {
                    String transcript = j.has("transcript") ? j.get("transcript").getAsString() : "";
                    gotFinal = true;
                    clearFinishTimeout();
                    if (TextUtils.isEmpty(transcript)) {
                        emitError("没识别出内容，再说一次");
                    } else {
                        emitFinal(transcript);
                    }
                    return;
                }
                if ("error".equals(type)) {
                    JsonObject err = j.has("error") ? j.getAsJsonObject("error") : null;
                    String msg = err != null && err.has("message")
                            ? err.get("message").getAsString() : "流式识别出错";
                    emitError("识别失败：" + msg);
                }
            } catch (Exception e) {
                Log.w(TAG, "解析事件失败", e);
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            if (cancelled) return;
            fallback(t == null ? "unknown" : t.getMessage());
        }
    }

    private void emitPartial(final String text) {
        main.post(() -> {
            if (!cancelled && callback != null) callback.onPartial(text);
        });
    }

    private void emitFinal(final String text) {
        main.post(() -> {
            if (!cancelled && callback != null) callback.onFinal(text);
        });
    }

    private void emitError(final String message) {
        main.post(() -> {
            if (!cancelled && callback != null) callback.onError(message);
        });
    }
}
