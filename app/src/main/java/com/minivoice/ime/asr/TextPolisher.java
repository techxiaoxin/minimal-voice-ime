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

    private static final String SYSTEM =
            "你是语音输入法的整理助手。用户说完话，你负责把转写出来的文字整理成他真正想发出去的样子。规则："
                    + "1. 只改表达，不改意思。用户没说的内容一个字都不要加，用户说过的信息不要删。"
                    + "2. 去掉嗯、啊、呃、那个、就是说、然后、嗯哼这类口头语，以及重复啰嗦的词。"
                    + "3. 同音错字按上下文改成正确的字，人名、产品名、型号、数字、英文按原样保留，不要自行改写或翻译。"
                    + "4. 补上合适的标点，长句按意思断成短句，一句话说不完可以两三句。"
                    + "5. 保留用户原本的语气，别改成公文体，也别加书面化的连接词。"
                    + "6. 只有用户明确在说几件事、明显是并列的时候才分条，最多用 1、2、3 分行，其他情况就写成一整段，不要主动分条。"
                    + "7. 不要加任何前缀、标题、解释、总结，也不要用引号把整段话包起来，直接输出整理后的正文。";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .protocols(Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
            .proxy(Proxy.NO_PROXY)
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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
            String sysText = SYSTEM;
            if (!vocab.isEmpty()) {
                StringBuilder sb = new StringBuilder(sysText);
                sb.append("\n8. 下面这些是用户指定必须原样保留的名词，出现时按这个写法输出，读错同音字也要按这个改：");
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
