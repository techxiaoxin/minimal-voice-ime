package com.minivoice.ime.asr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonObject;
import com.minivoice.ime.settings.Prefs;

import java.util.List;
import com.google.gson.JsonParser;

import java.net.Proxy;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 语音转写完再让文本模型过一遍，把口语整理成能直接发的书面语。
 * 走不通或者超时就返回原文，绝不吃掉用户说的话。
 */
public final class TextPolisher {

    public interface Callback {
        void onResult(String text);
    }

    /** 默认提示词，短一点，给模型留余地，别把它框死。 */
    private static final String DEFAULT_SYSTEM =
            "你是语音输入法的润色助手。用户给的是语音识别原文，口语、有错字，请改成像他自己打字发出来的话。"
                    + "\n"
                    + "1. 去掉嗯、啊、呃、那个、就是说这类口水词和重复啰嗦的地方。"
                    + "2. 按上下文修同音错字，补标点、断句，句子顺一点。"
                    + "3. 内容不增不减：说到的信息、语气、细节都要留下，不许扩写、总结、分条、加标题。"
                    + "4. 人名、产品名、型号、数字、英文照原样，别翻译别改写。"
                    + "5. 直接输出整理后的正文，不要任何前缀、引号或解释。";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .protocols(Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
            .proxy(Proxy.NO_PROXY)
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 设置页里看到的提示词：用户改过就用改过的。 */
    public static String rulesText(Context ctx) {
        String custom = Prefs.getString(ctx, Prefs.K_PROMPT_CLOUD, "");
        return custom == null || custom.trim().isEmpty() ? DEFAULT_SYSTEM : custom;
    }

    public static String defaultRules() {
        return DEFAULT_SYSTEM;
    }

    private TextPolisher() {
    }

    /**
     * 纯本地的整理，不联网。
     * 大模型才做得到的改写不做，只干三件稳的事：去重、清理标点、收尾。
     */
    public static String polishLocal(String raw) {
        if (raw == null) return "";
        String t = tidy(raw);
        // 只压那种明显的结巴式重复：同一个字连说三次以上，或两到四字的词组连说三次以上。
        // “常常”“看看”“谢谢”这类正常叠词只有两次，不动它。
        String prev;
        do {
            prev = t;
            t = t.replaceAll("([\\u4e00-\\u9fa5])\\1{2,}", "$1");
            t = t.replaceAll("([\\u4e00-\\u9fa5]{2,4})\\1{2,}", "$1");
        } while (!t.equals(prev));

        // 标点前面不留空格，句末补一个句号
        t = t.replaceAll("\\s+([，。！？、；：])", "$1");
        t = t.replaceAll("[，、；：]{2,}", "，");
        t = t.trim();
        if (!t.isEmpty() && !"，。！？".contains(t.substring(t.length() - 1))) {
            t = t + "。";
        }
        return t;
    }

    /** 全都交给模型整理，短句也一样，短句最容易识别错，更需要修。 */
    public static void polish(Context ctx, String raw, Callback cb) {
        if (raw == null || raw.trim().isEmpty()) {
            cb.onResult(raw);
            return;
        }
        new Thread(() -> {
            String out = request(ctx, raw);
            MAIN.post(() -> cb.onResult(out));
        }).start();
    }

    /** 模型偶尔会自己加前缀或者扩写，这里兜一层，宁可用原文也别乱加内容。 */
    private static String sanitize(String text, String raw) {
        if (text == null) return "";
        String t = text.trim();
        // 去掉「整理后：」「以下是整理后的内容：」这类引导语
        String[] prefixes = {"整理后：", "整理后:", "整理结果：", "以下是整理", "以下是修改后", "修改后："};
        for (String p : prefixes) {
            if (t.startsWith(p)) {
                t = t.substring(p.length()).trim();
            }
        }
        while (t.startsWith("\"") && t.endsWith("\"") && t.length() > 2) {
            t = t.substring(1, t.length() - 1).trim();
        }
        t = tidyPunctuation(t);
        // 明显扩写了就别用，避免模型自作主张
        if (t.length() > raw.length() * 1.8 + 20) return "";
        return t;
    }

    /** 模型删词以后常留下孤零零的标点，这里统一收拾干净。 */
    public static String tidy(String t) {
        if (t == null) return "";
        return tidyPunctuation(t);
    }

    private static String tidyPunctuation(String t) {
        String r = t;
        // 开头和每行开头不该有逗号句号顿号这类标点
        r = r.replaceAll("(?m)^[ \\t]*[，。、；：,.;:!?！？]+", "");
        // 标点前面不留空格
        r = r.replaceAll("([，。、；：,.;:!?！？])[ \\t]+", "$1");
        // 连续相同标点压成一个
        r = r.replaceAll("([，、；：,;:]){2,}", "$1");
        r = r.replaceAll("(。){2,}", "。");
        r = r.replaceAll("([，、；：,;:])+([。！？!?])", "$2");
        // 结尾孤零零的逗号顿号直接去掉
        r = r.replaceAll("[，、；：,;:]+$", "");
        // 多余空格压成一个
        r = r.replaceAll("[ \\t]{2,}", " ");
        return r.trim();
    }

    private static String request(Context ctx, String raw) {
        try {
            AsrConfig c = AsrConfig.load(ctx);
            String key = c.apiKey;
            if (key == null || key.isEmpty()) return raw;

            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            List<String> vocab = Prefs.getWordList(ctx);
            String sysText = rulesText(ctx);
            if (!vocab.isEmpty()) {
                StringBuilder sb = new StringBuilder(sysText);
                sb.append("\n这些专有名词用户要求原样保留，读错了也按这个写法输出：");
                for (int i = 0; i < vocab.size(); i++) {
                    if (i > 0) sb.append("、");
                    sb.append(vocab.get(i));
                }
                sysText = sb.toString();
            }
            sys.addProperty("content", sysText);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", raw);

            JsonObject body = new JsonObject();
            body.addProperty("model", AsrConfig.POLISH_MODEL);
            // 整理这一步只要结果，不要模型先想一段。开着思考会多花一两秒。
            body.addProperty("enable_thinking", false);
            com.google.gson.JsonArray msgs = new com.google.gson.JsonArray();
            msgs.add(sys);
            msgs.add(user);
            body.add("messages", msgs);
            body.addProperty("max_tokens", Math.min(800, raw.length() * 3 + 100));
            body.addProperty("temperature", 0.2);

            Request req = new Request.Builder()
                    .url(AsrConfig.BAILIAN_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + key)
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response r = CLIENT.newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) return raw;
                String s = r.body().string();
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                // 顺手把这次用掉的 token 记下来，设置页能看到累计用量
                if (o.has("usage") && o.get("usage").isJsonObject()) {
                    JsonObject u = o.getAsJsonObject("usage");
                    if (u.has("total_tokens") && !u.get("total_tokens").isJsonNull()) {
                        Prefs.addTokens(ctx, u.get("total_tokens").getAsInt(), "整理");
                    }
                }
                String text = o.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString();
                text = sanitize(text, raw);
                return text.isEmpty() ? raw : text;
            }
        } catch (Exception e) {
            return raw;
        }
    }
}
