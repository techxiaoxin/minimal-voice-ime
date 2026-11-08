package com.minivoice.ime.asr;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * WebSocket 流式识别引擎。一路说话一路发帧，服务端回中间结果就实时上屏。
 * 帧格式做了通用化：发 {"type":"audio","audio":"<base64 pcm>"}，
 * 收 {"text":"..."} 或 {"result":{"text":"..."}} 都认。
 */
public class WsAsrEngine implements AsrEngine {

    private final AsrConfig config;
    private final OkHttpClient client;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WebSocket socket;
    private Callback callback;
    private volatile boolean finished = false;
    private final StringBuilder lastText = new StringBuilder();

    public WsAsrEngine(AsrConfig config) {
        this.config = config;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void start(Callback callback) {
        this.callback = callback;
        this.finished = false;
        lastText.setLength(0);

        Request.Builder rb = new Request.Builder().url(config.endpoint);
        if (config.apiKey != null && !config.apiKey.isEmpty()) {
            rb.header("Authorization", "Bearer " + config.apiKey);
        }
        for (java.util.Map.Entry<String, String> e : config.extraHeaders.entrySet()) {
            rb.header(e.getKey(), e.getValue());
        }

        socket = client.newWebSocket(rb.build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                JsonObject hello = new JsonObject();
                hello.addProperty("type", "start");
                hello.addProperty("format", "pcm");
                hello.addProperty("sample_rate", 16000);
                if (config.model != null && !config.model.isEmpty()) {
                    hello.addProperty("model", config.model);
                }
                hello.addProperty("language", config.language);
                webSocket.send(hello.toString());
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (!finished) postError("连接中断：" + (t == null ? "未知" : t.getMessage()));
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (!finished && lastText.length() == 0) {
                    postError("连接已关闭：" + reason);
                }
            }
        });
    }

    private void handleMessage(String raw) {
        String text = extractText(raw);
        if (text == null || text.isEmpty()) return;
        lastText.setLength(0);
        lastText.append(text);
        postPartial(text);
    }

    static String extractText(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        if (!t.startsWith("{")) return t;
        try {
            JsonObject o = JsonParser.parseString(t).getAsJsonObject();
            for (String k : new String[]{"text", "transcript", "result"}) {
                if (o.has(k) && o.get(k).isJsonPrimitive()) {
                    return o.get(k).getAsString();
                }
            }
            if (o.has("result") && o.get("result").isJsonObject()) {
                JsonObject r = o.getAsJsonObject("result");
                if (r.has("text")) return r.get("text").getAsString();
            }
            if (o.has("payload_msg")) {
                return o.get("payload_msg").getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public void feed(byte[] pcm, int length) {
        if (socket == null || finished) return;
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "audio");
        frame.addProperty(config.wsAudioField, Base64.encodeToString(pcm, 0, length, Base64.NO_WRAP));
        socket.send(frame.toString());
    }

    @Override
    public void finish() {
        if (finished) return;
        finished = true;
        if (socket != null) {
            JsonObject end = new JsonObject();
            end.addProperty("type", "end");
            socket.send(end.toString());
            socket.close(1000, "done");
        }
        String text = lastText.toString();
        if (text.isEmpty()) {
            postError("没有识别出内容");
        } else {
            postFinal(text);
        }
    }

    @Override
    public void cancel() {
        finished = true;
        if (socket != null) {
            socket.cancel();
            socket = null;
        }
    }

    private void postPartial(String text) {
        main.post(() -> {
            if (callback != null) callback.onPartial(text);
        });
    }

    private void postFinal(String text) {
        main.post(() -> {
            if (callback != null) callback.onFinal(text);
        });
    }

    private void postError(String msg) {
        main.post(() -> {
            if (callback != null) callback.onError(msg);
        });
    }
}
