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

    /** 默认提示词：五条，短、清楚，不放词表。 */
    private static final String DEFAULT_SYSTEM =
            "帮我整理下面这段语音转成的文字，规则只有几条：\n"
            + "一、删掉纯语气词（嗯、啊、呃、唉）和所有重复、结巴，包括同一个词连说好几次的，"
            + "比如「一个一个一个」「然后然后」「那个那个」，连说几次都只留一次。\n"
            + "二、除了第一条说的语气词和重复，别的地方一个字都别动。有实际意思的词都留着，"
            + "「就是说」「然后」「其实」这种连接词不算废话，不许删。\n"
            + "三、只改错别字和标点，不改说法、不换词、不把长句压短。\n"
            + "四、内容多、层次清楚的可以分段。\n"
            + "五、直接给正文，不要标题、不要编号、不要解释。";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .protocols(Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
            .proxy(Proxy.NO_PROXY)
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 设置页里看到的提示词：用户改过就用改过的。 */
    public static String rulesText(Context ctx) {
        // 一次性清零：把之前存过的规则统一换成这一版，避免旧规则继续生效。
        if (!Prefs.getBool(ctx, "polish_rule_reset_v51", false)) {
            Prefs.putString(ctx, Prefs.K_PROMPT_CLOUD, DEFAULT_SYSTEM);
            Prefs.putBool(ctx, "polish_rule_reset_v51", true);
            return DEFAULT_SYSTEM;
        }
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

        // 一句到底、中间完全没标点的，按常见连接词断一下，至少能读
        if (t.length() >= 12 && !t.matches(".*[，。！？；：].*")) {
            t = t.replaceAll("(?<![，。！？；：])(然后|但是|不过|所以|因为|而且|另外|接着|最后)", "，$1");
            t = t.replaceAll("^，", "");
        }

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

    /** 整理接口跟随识别，同一套地址和 Key。 */
    public static String polishUrl(Context ctx, String asrEndpoint) {
        return AsrConfig.polishEndpoint(asrEndpoint);
    }

    public static String polishKey(Context ctx, String asrKey) {
        return asrKey;
    }

    /** 发一次 chat，成功后返回正文；出错返回 null。 */
    private static String chat(Context ctx, String sysText, String userText, int maxTokens) {
        try {
            AsrConfig c = AsrConfig.load(ctx);
            String key = polishKey(ctx, c.apiKey);
            if (key == null || key.isEmpty()) return null;

            com.google.gson.JsonArray msgs = new com.google.gson.JsonArray();
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", sysText);
            msgs.add(sys);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userText);
            msgs.add(user);

            JsonObject body = new JsonObject();
            String pm = Prefs.getString(ctx, Prefs.K_POLISH_MODEL, AsrConfig.POLISH_MODEL);
            body.addProperty("model", pm == null || pm.trim().isEmpty()
                    ? AsrConfig.POLISH_MODEL : pm.trim());
            // enable_thinking 是百炼兼容接口的参数，别家平台不认识，可能直接报参数错误
            String url = polishUrl(ctx, c.endpoint);
            if (url.contains("aliyuncs.com") || url.contains("dashscope")) {
                body.addProperty("enable_thinking", false);
            }
            body.add("messages", msgs);
            body.addProperty("max_tokens", maxTokens);
            // Kimi 只接受 temperature=1，别的平台用低温度更稳。
            body.addProperty("temperature", url.contains("moonshot") || url.contains("kimi") ? 1.0 : 0.2);

            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + key)
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response r = CLIENT.newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) return null;
                JsonObject o = JsonParser.parseString(r.body().string()).getAsJsonObject();
                if (o.has("usage") && o.get("usage").isJsonObject()) {
                    JsonObject u = o.getAsJsonObject("usage");
                    if (u.has("total_tokens") && !u.get("total_tokens").isJsonNull()) {
                        Prefs.addTokens(ctx, u.get("total_tokens").getAsInt(), "整理");
                    }
                }
                return o.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String request(Context ctx, String raw) {
        String sysText = rulesText(ctx);
        List<String> vocab = new java.util.ArrayList<>(Prefs.getWordList(ctx));
        for (String term : com.minivoice.ime.data.PhraseStore.personalTerms(ctx)) {
            if (!vocab.contains(term)) vocab.add(term);
        }
        if (!vocab.isEmpty()) {
            StringBuilder sb = new StringBuilder(sysText);
            sb.append("\n这些专有名词用户要求原样保留，读错了也按这个写法输出：");
            for (int i = 0; i < vocab.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(vocab.get(i));
            }
            sysText = sb.toString();
        }
        String text = chat(ctx, sysText, raw, Math.min(800, raw.length() * 3 + 100));
        if (text == null) return raw;
        text = sanitize(text, raw);
        if (text.isEmpty()) return raw;

        return text;
    }
}
