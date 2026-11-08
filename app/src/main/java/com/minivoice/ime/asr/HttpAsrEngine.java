package com.minivoice.ime.asr;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * HTTP 一次性上传的识别引擎，兼容 OpenAI /v1/audio/transcriptions 风格接口，
 * 也兼容以 base64 承载音频的通用 JSON 接口。
 */
public class HttpAsrEngine implements AsrEngine {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final AsrConfig config;
    private final OkHttpClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

    private volatile boolean cancelled = false;
    private Callback callback;

    public HttpAsrEngine(AsrConfig config) {
        this.config = config;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void start(Callback callback) {
        this.callback = callback;
        this.cancelled = false;
        buffer.reset();
    }

    @Override
    public void feed(byte[] pcm, int length) {
        if (cancelled) return;
        buffer.write(pcm, 0, length);
    }

    @Override
    public void finish() {
        if (cancelled) return;
        final byte[] audio = buffer.toByteArray();
        if (audio.length < 1600) {
            postError("录音太短，没有发出声音");
            return;
        }
        String path = config.endpoint;
        if (isOpenAiStyle(path)) {
            uploadMultipart(audio);
        } else {
            uploadJson(audio);
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        buffer.reset();
    }

    private boolean isOpenAiStyle(String path) {
        return path.contains("/audio/transcriptions") || path.contains("audio/transcriptions");
    }

    private Request.Builder baseBuilder(String url) {
        Request.Builder b = new Request.Builder().url(url);
        if (config.apiKey != null && !config.apiKey.isEmpty()) {
            b.header("Authorization", "Bearer " + config.apiKey);
        }
        for (java.util.Map.Entry<String, String> e : config.extraHeaders.entrySet()) {
            b.header(e.getKey(), e.getValue());
        }
        return b;
    }

    private void uploadMultipart(byte[] audio) {
        MultipartBody.Builder form = new MultipartBody.Builder().setType(MultipartBody.FORM);
        form.addFormDataPart("file", "speech.wav",
                RequestBody.create(wrapWav(audio), MediaType.get("audio/wav")));
        if (config.model != null && !config.model.isEmpty()) {
            form.addFormDataPart("model", config.model);
        }
        form.addFormDataPart("language", config.language == null ? "zh" : config.language);

        Request req = baseBuilder(config.endpoint).post(form.build()).build();
        execute(req);
    }

    private void uploadJson(byte[] audio) {
        JsonObject body = new JsonObject();
        body.addProperty("audio", Base64.encodeToString(audio, Base64.NO_WRAP));
        body.addProperty("format", "pcm");
        body.addProperty("sample_rate", 16000);
        if (config.model != null && !config.model.isEmpty()) {
            body.addProperty("model", config.model);
        }
        body.addProperty("language", config.language == null ? "zh" : config.language);

        Request req = baseBuilder(config.endpoint).post(RequestBody.create(body.toString(), JSON)).build();
        execute(req);
    }

    private void execute(Request req) {
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postError("请求失败：" + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String body = response.body() == null ? "" : response.body().string();
                    if (!response.isSuccessful()) {
                        postError("接口返回 " + response.code() + "：" + shorten(body));
                        return;
                    }
                    String text = parseText(body);
                    if (text == null || text.isEmpty()) {
                        postError("接口没有返回文字");
                    } else {
                        postFinal(text);
                    }
                } catch (Exception e) {
                    postError("解析返回失败：" + e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    /** 兼容常见返回结构，也兼容纯文本返回。 */
    static String parseText(String body) {
        if (body == null) return null;
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return null;
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return trimmed;
        }
        try {
            JsonObject o = JsonParser.parseString(trimmed).getAsJsonObject();
            for (String key : new String[]{"text", "result", "transcript", "transcription"}) {
                if (o.has(key) && !o.get(key).isJsonNull()) {
                    return o.get(key).getAsString();
                }
            }
            if (o.has("data")) {
                if (o.get("data").isJsonPrimitive()) {
                    return o.get("data").getAsString();
                }
                JsonObject d = o.getAsJsonObject("data");
                if (d.has("text")) return d.get("text").getAsString();
            }
            if (o.has("result") && o.get("result").isJsonObject()) {
                JsonObject r = o.getAsJsonObject("result");
                if (r.has("text")) return r.get("text").getAsString();
            }
            if (o.has("sentences")) {
                JsonArray arr = o.getAsJsonArray("sentences");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject s = arr.get(i).getAsJsonObject();
                    if (s.has("text")) sb.append(s.get("text").getAsString());
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 给 PCM 套一个标准 WAV 头，绝大多数接口能直接吃。 */
    static byte[] wrapWav(byte[] pcm) {
        int sampleRate = 16000;
        int channels = 1;
        int bits = 16;
        int dataLen = pcm.length;
        byte[] out = new byte[44 + dataLen];
        int riffLen = 36 + dataLen;
        writeAscii(out, 0, "RIFF");
        writeInt(out, 4, riffLen);
        writeAscii(out, 8, "WAVE");
        writeAscii(out, 12, "fmt ");
        writeInt(out, 16, 16);
        writeShort(out, 20, 1);
        writeShort(out, 22, channels);
        writeInt(out, 24, sampleRate);
        writeInt(out, 28, sampleRate * channels * bits / 8);
        writeShort(out, 32, channels * bits / 8);
        writeShort(out, 34, bits);
        writeAscii(out, 36, "data");
        writeInt(out, 40, dataLen);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    private static void writeAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void writeShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
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
