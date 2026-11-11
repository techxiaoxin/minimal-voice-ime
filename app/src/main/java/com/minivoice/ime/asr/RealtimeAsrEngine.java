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

    /** 一次发 100 毫秒的音频，16k 16bit 单声道就是 3200 字节。 */
    private static final int CHUNK_BYTES = 3200;
    private static final long FINISH_TIMEOUT_MS = 12000L;

    private final AsrConfig config;
    private android.content.Context appCtx;
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
    private volatile boolean fallbackStarted;
    private volatile boolean wsFailed;
    private volatile String wsFailReason = "";
    private volatile boolean sessionSent;
    /** 3.0/3.1 实时模型发的是 delta，3-asr 发的是 text，两种都要能拼出中间稿。 */
    private volatile String partialSoFar = "";
    /** qwen-audio 实时模型不接受 turn_detection:none，只能开 VAD，收尾时补静音。 */
    private volatile boolean vadMode;
    private final StringBuilder committedText = new StringBuilder();
    /** 给设置页“识别诊断”看的精简日志，只记事件和错误，不记音频和 Key。 */
    private final StringBuilder diag = new StringBuilder();
    private int partialCount;

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

    public RealtimeAsrEngine(AsrConfig config, android.content.Context ctx) {
        this.config = config;
        this.appCtx = ctx == null ? null : ctx.getApplicationContext();
    }

    @Override
    public void start(Callback cb) {
        callback = cb;
        cancelled = false;
        ready = false;
        finishing = false;
        gotFinal = false;
        fallbackStarted = false;
        wsFailed = false;
        wsFailReason = "";
        sessionSent = false;
        partialSoFar = "";
        // 全部实时模型都开 server_vad：qwen3 在 none 模式下要等 commit 才吐字，
        // 录音中途看不到中间稿；开 VAD 后服务端才会边说边回。
        vadMode = true;
        committedText.setLength(0);
        partialCount = 0;
        synchronized (diag) { diag.setLength(0); }
        logDiag("START model=" + config.streamModel() + " host=" + hostOfEndpoint());
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
                .url(config.streamEndpoint())
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
        // WebSocket 已经废了，录音继续攒着，等松手后再整段上传，
        // 不能在这里立刻发 HTTP，那时候只有开头几十毫秒音频。
        if (wsFailed) return;
        if (!ready) {
            synchronized (pending) {
                pending.write(pcm, 0, length);
            }
            return;
        }
        appendAudio(pcm, length);
    }

    private void markReady() {
        if (ready || cancelled) return;
        ready = true;
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
        if (finishRequested) flushAndCommit();
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
        if (wsFailed) {
            fallback(wsFailReason);
            return;
        }
        if (!ready) {
            // 会话还在建，等 session.updated 回来再收尾
            armFinishTimeout();
            return;
        }
        flushAndCommit();
    }

    /** 把攒下的音频全发完。 */
    private void flushPendingAudio() {
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
    }

    /** 把攒下的音频全发完，再按会话模式收尾。 */
    private void flushAndCommit() {
        logDiag("FINISH bytes=" + all.size());
        flushPendingAudio();
        // 和 0.6.10 一致，commit 一次收尾；服务端默认 VAD 会正常给 completed。
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
            // 实时没返回最终稿也别直接报错，拿完整录音走整段识别兜底。
            Log.w(TAG, "stream finish timeout, fallback");
            fallback("finish timeout");
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

    /** 流式返回里带 usage 就累加；3.0/3.1 实时模型不返回 usage 时按识别文本估算，避免一直显示 0。 */
    private void recordUsage(JsonObject j, String transcript) {
        if (appCtx == null) return;
        int tokens = 0;
        if (j.has("usage") && j.get("usage").isJsonObject()) {
            JsonObject u = j.getAsJsonObject("usage");
            if (u.has("total_tokens") && !u.get("total_tokens").isJsonNull()) {
                tokens = u.get("total_tokens").getAsInt();
            }
        }
        if (tokens <= 0) {
            tokens = Math.max(1, transcript == null ? 1 : transcript.length());
        }
        com.minivoice.ime.settings.Prefs.addTokens(appCtx, tokens, "识别");
    }

    private String hostOfEndpoint() {
        try {
            android.net.Uri u = android.net.Uri.parse(config.streamEndpoint());
            return u.getHost() == null ? "?" : u.getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    private void logDiag(String line) {
        if (appCtx == null) return;
        synchronized (diag) {
            long t = android.os.SystemClock.elapsedRealtime();
            diag.append(t).append("  ").append(line).append('\n');
            if (diag.length() > 6000) diag.delete(0, diag.length() - 6000);
            com.minivoice.ime.settings.Prefs.putString(appCtx, "asr_diag", diag.toString());
        }
    }

    private void sendJson(String json) {
        if (socket == null) return;
        socket.send(json);
    }

    private void onOpenSendSession() {
        // 0.6.10 用的就是 null，服务端会落到默认 server_vad，能边录边出字；
        // 显式写 server_vad 在部分模型上反而不回中间稿。null 兼容性最好。
        String turn = "null";
        String session = "{\"type\":\"session.update\",\"session\":{"
                + "\"input_audio_format\":\"pcm\","
                + "\"input_audio_transcription\":{\"model\":\"" + config.streamModel() + "\"},"
                + "\"turn_detection\":" + turn + ","
                + "\"modalities\":[\"text\"]}}";
        sendJson(session);
    }

    /** 兜底整段上传换成更快的兼容模型，别再用 qwen-audio 原生接口慢慢等。 */
    private AsrConfig fallbackConfig() {
        AsrConfig c = new AsrConfig();
        c.endpoint = config.endpoint;
        c.apiKey = config.apiKey;
        c.protocol = config.protocol;
        c.language = config.language;
        c.model = AsrConfig.BAILIAN_BATCH_MODEL;
        c.polish = config.polish;
        return c;
    }

    /** WebSocket 连不上或者中途断了，退回一次性整段上传，保证还能出字。 */
    private void fallback(String reason) {
        if (cancelled || gotFinal || fallbackStarted) return;
        fallbackStarted = true;
        clearFinishTimeout();
        Log.w(TAG, "stream fallback: " + reason);
        if (socket != null) {
            socket.close(1000, "fallback");
            socket = null;
        }
        byte[] pcm;
        synchronized (all) {
            pcm = all.toByteArray();
        }
        if (pcm.length < 3200) {
            emitError("说得太短了，按住多说一会儿");
            return;
        }
        AsrEngine legacy = new BailianAsrEngine(fallbackConfig(), appCtx);
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
            logDiag("OPEN");
            // 和 0.6.10 一样，等 session.created 之后再发 session.update，
            // 太早发会被服务端丢掉，导致一直等不到 session.updated、录音期间不出字。
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JsonObject j = JsonParser.parseString(text).getAsJsonObject();
                String type = j.has("type") ? j.get("type").getAsString() : "";

                if ("session.created".equals(type)) {
                    logDiag("SESSION_CREATED");
                    if (!sessionSent) {
                        sessionSent = true;
                        onOpenSendSession();
                    }
                    // 等 session.updated 真正应用配置后再送音频，和 0.6.10 的可用流程一致。
                    return;
                }
                if ("session.updated".equals(type)) {
                    logDiag("SESSION_UPDATED ready");
                    markReady();
                    return;
                }
                if ("conversation.item.input_audio_transcription.text".equals(type)
                        || "conversation.item.input_audio_transcription.delta".equals(type)) {
                    String confirmed = j.has("text") ? j.get("text").getAsString() : "";
                    String stash = j.has("stash") ? j.get("stash").getAsString() : "";
                    String partial;
                    if (!TextUtils.isEmpty(stash)) {
                        // 百炼把已确认文本放在 text、正在识别的尾巴放在 stash。
                        partial = (confirmed == null ? "" : confirmed)
                                + (stash == null ? "" : stash);
                    } else if ("conversation.item.input_audio_transcription.delta".equals(type)
                            && !TextUtils.isEmpty(confirmed)) {
                        // 少数实时模型只发增量 delta，没有 stash，自己往下拼。
                        partialSoFar = partialSoFar + confirmed;
                        partial = partialSoFar;
                    } else {
                        partial = confirmed == null ? "" : confirmed;
                    }
                    if (!TextUtils.isEmpty(partial)) {
                        partialSoFar = partial;
                        if (partialCount++ % 5 == 0) logDiag("PARTIAL#" + partialCount + " " + partial);
                        emitPartial(vadMode ? committedText + partial : partial);
                    }
                    return;
                }
                if ("conversation.item.input_audio_transcription.completed".equals(type)) {
                    String transcript = j.has("transcript") ? j.get("transcript").getAsString() : "";
                    logDiag("COMPLETED " + transcript + (j.has("usage") ? " usage=yes" : " usage=no"));
                    recordUsage(j, transcript);
                    // server_vad 会在用户停顿时自动断句。没松手之前的 completed
                    // 只是半句，先攒起来，别提前把输入法收掉。
                    if (vadMode && !finishRequested) {
                        if (!TextUtils.isEmpty(transcript)) committedText.append(transcript);
                        partialSoFar = committedText.toString();
                        if (committedText.length() > 0) emitPartial(committedText.toString());
                        return;
                    }
                    String full = vadMode
                            ? committedText.toString() + (transcript == null ? "" : transcript)
                            : transcript;
                    gotFinal = true;
                    clearFinishTimeout();
                    if (TextUtils.isEmpty(full)) {
                        emitError("没识别出内容，再说一次");
                    } else {
                        emitFinal(full);
                    }
                    return;
                }
                if ("error".equals(type)) {
                    JsonObject err = j.has("error") ? j.getAsJsonObject("error") : null;
                    String msg = err != null && err.has("message")
                            ? err.get("message").getAsString() : "流式识别出错";
                    // 实时协议被服务端拒绝（模型、格式、权限都可能是参数错误）时，
                    // 不要在录音中途用半截音频去重试；先记住失败，松手后整段上传。
                    Log.w(TAG, "stream error: " + msg);
                    logDiag("ERROR " + msg);
                    wsFailed = true;
                    wsFailReason = msg;
                    if (socket != null) {
                        socket.close(1000, "error");
                        socket = null;
                    }
                    if (finishRequested && !gotFinal) fallback(msg);
                }
            } catch (Exception e) {
                Log.w(TAG, "解析事件失败", e);
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            if (cancelled) return;
            wsFailed = true;
            wsFailReason = t == null ? "unknown" : t.getMessage();
            logDiag("FAILURE " + wsFailReason + " code=" + (response == null ? "?" : response.code()));
            socket = null;
            // 连接失败时用户可能还在说话；等松手后拿完整音频走整段识别，
            // 中途就上传只会得到“说得太短”的提示。
            if (finishRequested && !gotFinal) fallback(wsFailReason);
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
