package com.minivoice.ime.asr;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.net.Proxy;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 阿里云百炼 Qwen3-ASR-Flash。录音结束后整段转 WAV，转 Base64 Data URI 上传，
 * 走 OpenAI 兼容的 chat/completions 接口，返回 choices[0].message.content。
 */
public class BailianAsrEngine implements AsrEngine {

    private static final String TAG = "BailianAsr";
    private static final int SAMPLE_RATE = 16000;
    /** 单次最多上传 30 秒，长录音拆段，避免一个大请求超时或失败。 */
    private static final int MAX_CHUNK_BYTES = 30 * SAMPLE_RATE * 2;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final AsrConfig config;
    private android.content.Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // 国内网关对 HTTP/2 的处理有时会把请求判成鉴权失败，强制走 HTTP/1.1
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            // 直连，不经过手机上的 VPN 或加速器，避免请求头被中间件改写
            .proxy(Proxy.NO_PROXY)
            .build();

    private Callback callback;
    private okhttp3.Call inFlight;
    private volatile boolean cancelled;

    public BailianAsrEngine(AsrConfig config) {
        this.config = config;
    }

    public BailianAsrEngine(AsrConfig config, android.content.Context ctx) {
        this.config = config;
        this.appCtx = ctx == null ? null : ctx.getApplicationContext();
    }

    @Override
    public void start(Callback cb) {
        callback = cb;
        cancelled = false;
        synchronized (buffer) {
            buffer.reset();
        }
    }

    @Override
    public void feed(byte[] pcm, int len) {
        if (cancelled || pcm == null || len <= 0) return;
        synchronized (buffer) {
            buffer.write(pcm, 0, len);
        }
    }

    @Override
    public void finish() {
        byte[] pcm;
        synchronized (buffer) {
            pcm = buffer.toByteArray();
        }
        // 16k 16bit 单声道，3200 字节约 0.1 秒，太短就别发请求了
        if (pcm.length < 3200) {
            emitError("说得太短了，按住多说一会儿");
            return;
        }
        if (pcm.length > MAX_CHUNK_BYTES) {
            uploadChunks(pcm, 0, new StringBuilder());
            return;
        }
        uploadWithRetry(buildWav(pcm), new UploadCallback() {
            @Override
            public void onText(String text) {
                if (TextUtils.isEmpty(text)) emitError("没识别出内容，再说一次");
                else emitFinal(text);
            }

            @Override
            public void onError(String message) {
                emitError(message);
            }
        }, 0);
    }

    /** 长录音按 30 秒切段，顺序识别后拼起来；一段失败不会让整段白录。 */
    private void uploadChunks(byte[] pcm, int offset, StringBuilder joined) {
        if (cancelled) return;
        if (offset >= pcm.length) {
            String text = joined.toString().trim();
            if (TextUtils.isEmpty(text)) emitError("没识别出内容，再说一次");
            else emitFinal(text);
            return;
        }
        int end = Math.min(offset + MAX_CHUNK_BYTES, pcm.length);
        byte[] part = new byte[end - offset];
        System.arraycopy(pcm, offset, part, 0, part.length);
        uploadWithRetry(buildWav(part), new UploadCallback() {
            @Override
            public void onText(String text) {
                if (TextUtils.isEmpty(text)) {
                    // 某一段没出字（比如纯静音），跳过继续，不要整段失败
                    uploadChunks(pcm, end, joined);
                    return;
                }
                if (joined.length() > 0) joined.append(' ');
                joined.append(text.trim());
                uploadChunks(pcm, end, joined);
            }

            @Override
            public void onError(String message) {
                emitError(message);
            }
        }, 0);
    }

    /** 网络抖动或 429 重试一次，长录音别因为某一段失败整段白录。 */
    private void uploadWithRetry(byte[] wav, UploadCallback cb, int attempt) {
        upload(wav, new UploadCallback() {
            @Override
            public void onText(String text) {
                cb.onText(text);
            }

            @Override
            public void onError(String message) {
                if (attempt < 1 && !cancelled) {
                    uploadWithRetry(wav, cb, attempt + 1);
                } else {
                    cb.onError(message);
                }
            }
        });
    }

    interface UploadCallback {
        void onText(String text);

        void onError(String message);
    }

    @Override
    public void cancel() {
        cancelled = true;
        if (inFlight != null) inFlight.cancel();
    }

    private void upload(byte[] wav, UploadCallback cb) {
        if (TextUtils.isEmpty(config.apiKey)) {
            cb.onError("还没填识别服务的 API Key，去设置里填一下");
            return;
        }
        if (AsrConfig.isZhipuEndpoint(config.endpoint)) {
            uploadZhipu(wav, cb);
            return;
        }
        String dataUri = "data:audio/wav;base64," + Base64.encodeToString(wav, Base64.NO_WRAP);
        String language = TextUtils.isEmpty(config.language) ? "zh" : config.language;
        // 默认中英混合识别，测试语音和日常夹英文都能转出来
        String languageHints = "en".equalsIgnoreCase(language) ? "[\"en\",\"zh\"]" : "[\"zh\",\"en\"]";
        // 实时模型只适合 WebSocket；如果流式失败退回整段上传，
        // 必须换成同系列的整段模型，否则一定报参数错误。
        String model = config.batchModel();
        // qwen-audio 系列的整段识别模型走百炼原生多模态接口，
        // 换到小米等兼容接口时不能走这个路径，否则一定是 404。
        boolean nativeMode = model.toLowerCase(java.util.Locale.ROOT).startsWith("qwen-audio-")
                && AsrConfig.isBailianEndpoint(config.endpoint);

        String body;
        String url;
        if (nativeMode) {
            // qwen-audio-3.1-asr-flash 走原生多模态接口，格式参数必须放在 parameters 里
            url = AsrConfig.nativeEndpoint(config.endpoint);
            body = "{\"model\":\"" + model + "\","
                    + "\"input\":{\"messages\":[{\"role\":\"user\",\"content\":["
                    + "{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"" + dataUri + "\"}}]}]},"
                    + "\"parameters\":{\"format\":\"wav\",\"sample_rate\":\"16000\","
                    + "\"language_hints\":" + languageHints + "}}";
        } else {
            // 小米、普通 OpenAI 兼容接口走这一条。不同平台对 asr_options
            // 的支持不一样，只有明确支持的小米才带，免得别的平台回参数错误。
            String asrOptions = AsrConfig.isXiaomiEndpoint(config.endpoint)
                    ? ",\"asr_options\":{\"language\":\"" + language + "\",\"enable_itn\":true}"
                    : "";
            url = AsrConfig.polishEndpoint(config.endpoint);
            body = "{\"model\":\"" + model + "\","
                    + "\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_audio\","
                    + "\"input_audio\":{\"data\":\"" + dataUri + "\"}}]}]"
                    + asrOptions + ",\"stream\":false}";
        }

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.apiKey)
                .addHeader("X-DashScope-SSE", "disable")
                .post(RequestBody.create(body, JSON))
                .build();

        inFlight = client.newCall(request);
        inFlight.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                if (cancelled) return;
                cb.onError("网络没通：" + e.getMessage());
            }

            @Override
            public void onResponse(okhttp3.Call call, Response response) {
                String text;
                try {
                    text = response.body() != null ? response.body().string() : "";
                } catch (IOException e) {
                    cb.onError("读取返回失败");
                    return;
                }
                if (cancelled) return;
                if (!response.isSuccessful()) {
                    cb.onError(hintForCode(response.code()) + errorMessage(text));
                    return;
                }
                String result = contentOf(text);
                if (TextUtils.isEmpty(result)) cb.onError("没识别出内容，再说一次");
                else cb.onText(result);
            }
        });
    }

    /** 智谱的语音转写不是 chat/completions，要用 multipart 传 wav。 */
    private void uploadZhipu(byte[] wav, UploadCallback cb) {
        RequestBody file = RequestBody.create(wav, MediaType.parse("audio/wav"));
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", config.model)
                .addFormDataPart("file", "audio.wav", file)
                .build();
        Request request = new Request.Builder()
                .url(AsrConfig.zhipuTranscriptionEndpoint(config.endpoint))
                .addHeader("Authorization", "Bearer " + config.apiKey)
                .post(body)
                .build();
        inFlight = client.newCall(request);
        inFlight.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                if (cancelled) return;
                cb.onError("网络没通：" + e.getMessage());
            }

            @Override
            public void onResponse(okhttp3.Call call, Response response) {
                String text;
                try {
                    text = response.body() != null ? response.body().string() : "";
                } catch (IOException e) {
                    cb.onError("读取返回失败");
                    return;
                }
                if (cancelled) return;
                if (!response.isSuccessful()) {
                    cb.onError(hintForCode(response.code()) + errorMessage(text));
                    return;
                }
                String result = contentOf(text);
                if (TextUtils.isEmpty(result)) cb.onError("没识别出内容，再说一次");
                else cb.onText(result);
            }
        });
    }

    /** 常见状态码翻译成人话，免得只看到一串英文。 */
    private String hintForCode(int code) {
        if (code == 401) {
            String k = config.apiKey == null ? "" : config.apiKey;
            String tail = k.length() > 4 ? k.substring(k.length() - 4) : k;
            return "Key 被拒（401）· 内置Key 长度" + k.length() + " 尾号" + tail + " · ";
        }
        if (code == 403) return "Key 没有权限（403）：";
        if (code == 429) return "调用太频繁被限流（429），等一会儿再说：";
        if (code == 400) return "请求参数不对（400）：";
        if (code >= 500) return "百炼服务端出错（" + code + "），稍后再试：";
        return "百炼返回 " + code + "：";
    }

    /** 兼容接口取 choices[0].message.content，原生接口取 output.text。 */
    private String contentOf(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            boolean usageRecorded = false;
            if (appCtx != null && root.has("usage") && root.get("usage").isJsonObject()) {
                JsonObject u = root.getAsJsonObject("usage");
                if (u.has("total_tokens") && !u.get("total_tokens").isJsonNull()) {
                    com.minivoice.ime.settings.Prefs.addTokens(appCtx, u.get("total_tokens").getAsInt(), "识别");
                    usageRecorded = true;
                }
            }
            String result = null;
            JsonObject output = root.has("output") ? root.getAsJsonObject("output") : null;
            if (output != null && output.has("text")) {
                result = output.get("text").getAsString().trim();
            } else if (root.has("text") && !root.get("text").isJsonNull()) {
                // 智谱的转写接口直接返回 text，部分兼容接口放在 result 里。
                result = root.get("text").getAsString().trim();
            } else if (root.has("result") && root.get("result").isJsonPrimitive()) {
                result = root.get("result").getAsString().trim();
            } else {
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices != null && choices.size() > 0) {
                    JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                    if (message != null && message.has("content")
                            && !message.get("content").isJsonNull()) {
                        result = message.get("content").getAsString().trim();
                    }
                }
            }
            if (result != null && !usageRecorded && appCtx != null) {
                // 有些整段接口不返回 usage，按识别文本长度估一笔，避免用量一直是 0。
                com.minivoice.ime.settings.Prefs.addTokens(appCtx,
                        Math.max(1, result.length()), "识别");
            }
            return result;
        } catch (Exception e) {
            Log.w(TAG, "解析返回失败", e);
            return null;
        }
    }

    private String errorMessage(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject error = root.getAsJsonObject("error");
            if (error != null && error.has("message")) {
                return error.get("message").getAsString();
            }
            if (root.has("message")) return root.get("message").getAsString();
        } catch (Exception ignored) {
            // 返回体不是 JSON 就原样截一段
        }
        return json.length() > 120 ? json.substring(0, 120) : json;
    }

    /** PCM 套 44 字节 RIFF 头，变成标准 WAV。 */
    private byte[] buildWav(byte[] pcm) {
        int total = pcm.length;
        ByteBuffer buf = ByteBuffer.allocate(44 + total).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(36 + total);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) 1);
        buf.putInt(SAMPLE_RATE);
        buf.putInt(SAMPLE_RATE * 2);
        buf.putShort((short) 2);
        buf.putShort((short) 16);
        buf.put("data".getBytes());
        buf.putInt(total);
        buf.put(pcm);
        return buf.array();
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
