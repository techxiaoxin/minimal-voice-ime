package com.minivoice.ime.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minivoice.ime.asr.AsrConfig;
import com.minivoice.ime.asr.LlmPolisher;
import com.minivoice.ime.asr.LocalLlm;
import com.minivoice.ime.settings.Prefs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 把用户粘的一大段文字整理成「缩写 | 名称 | 正文」这样的词库条目。
 * 有本地整理模型就用本地，没有就走云端；两边都不行就退回到按行拆的规则办法。
 */
public final class PhraseAi {

    public interface Callback {
        /** entries 是整理结果，note 是给用户看的一句话说明。 */
        void onResult(List<PhraseStore.Phrase> entries, String note);
    }

    private static final String SYSTEM =
            "你是输入法的词库整理助手。用户会粘一段杂乱的内容，里面可能混着发票抬头、税号、地址、"
                    + "手机号、常用回复、自我介绍等。请把它们拆成一条条独立的词库条目。\n"
                    + "每条输出一行，格式固定为：缩写|名称|正文\n"
                    + "1. 缩写：2 到 4 个小写字母，取名称的拼音首字母，比如“发票抬头”给 fp，“手机号”给 sjh。"
                    + "缩写要唯一，撞了就在后面加 2 这样的数字。\n"
                    + "2. 名称：4 到 8 个字，说清这条是什么，比如“公司开票信息”“我的手机号”。\n"
                    + "3. 正文：真正上屏的内容，原有信息一个字都不许改，号码、税号、地址、金额保持原样；"
                    + "多条信息用换行放在同一条正文里。\n"
                    + "4. 只输出这些行，不要编号、不要解释、不要空行、不要 Markdown。";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .protocols(Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
            .proxy(java.net.Proxy.NO_PROXY)
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PhraseAi() {
    }

    public static void extract(Context ctx, String type, String raw, Callback cb) {
        if (raw == null || raw.trim().isEmpty()) {
            cb.onResult(new ArrayList<>(), "没有可整理的内容");
            return;
        }
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String out = "";
            boolean local = "local".equals(Prefs.getString(app, Prefs.K_POLISH_ENGINE, "cloud"));
            if (local) out = fromLocal(app, raw);
            if (out.isEmpty()) out = fromCloud(app, raw);
            List<PhraseStore.Phrase> entries = parse(out, type);
            String note;
            if (!entries.isEmpty()) {
                note = "整理出 " + entries.size() + " 条，缩写可以在列表里再改";
            } else {
                entries = fallback(raw, type);
                note = entries.isEmpty()
                        ? "模型没整理出来，照着原文一行拆的，缩写要自己补一下"
                        : "模型没整理出来，先按行拆成 " + entries.size() + " 条，缩写要自己补一下";
            }
            final List<PhraseStore.Phrase> res = entries;
            final String n = note;
            MAIN.post(() -> cb.onResult(res, n));
        }).start();
    }

    private static String fromLocal(Context ctx, String raw) {
        try {
            if (!LlmPolisher.ensureLoaded(ctx)) return "";
            String out = LocalLlm.generate(SYSTEM, raw, Math.max(160, Math.min(512, raw.length() / 4 + 120)));
            return out == null ? "" : out.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String fromCloud(Context ctx, String raw) {
        try {
            AsrConfig c = AsrConfig.load(ctx);
            String key = c.apiKey;
            if (key == null || key.isEmpty()) return "";
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", SYSTEM);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", raw);
            JsonObject body = new JsonObject();
            body.addProperty("model", AsrConfig.POLISH_MODEL);
            body.addProperty("enable_thinking", false);
            JsonArray msgs = new JsonArray();
            msgs.add(sys);
            msgs.add(user);
            body.add("messages", msgs);
            body.addProperty("max_tokens", Math.min(1000, raw.length() / 2 + 200));
            body.addProperty("temperature", 0.2);

            Request req = new Request.Builder()
                    .url(AsrConfig.BAILIAN_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + key)
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .build();
            try (Response r = CLIENT.newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) return "";
                String s = r.body().string();
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                if (o.has("usage") && o.get("usage").isJsonObject()) {
                    JsonObject u = o.getAsJsonObject("usage");
                    if (u.has("total_tokens") && !u.get("total_tokens").isJsonNull()) {
                        Prefs.addTokens(ctx, u.get("total_tokens").getAsInt(), "整理");
                    }
                }
                return o.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString().trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    static List<PhraseStore.Phrase> parse(String out, String type) {
        return PhraseParser.parse(out, type);
    }

    static List<PhraseStore.Phrase> fallback(String raw, String type) {
        return PhraseParser.fallback(raw, type);
    }
}
