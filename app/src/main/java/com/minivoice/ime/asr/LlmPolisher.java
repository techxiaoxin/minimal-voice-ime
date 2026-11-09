package com.minivoice.ime.asr;

import android.content.Context;

import com.minivoice.ime.settings.Prefs;

import java.io.File;

/** 用手机里的 Qwen2.5-0.5B 整理口语，纯离线，慢一点但是不联网。 */
public final class LlmPolisher {

    private static final String DEFAULT_SYSTEM =
            "把下面这句话改顺，像用户自己打出来的。去掉口水词和重复，修同音错字，补标点。"
                    + "\n意思、信息点、语气都不能丢，不许扩写、总结、分条、加前缀，长度和原文差不多。"
                    + "\n英文和型号照原样写，比如 iPhone、WiFi、Type-C。只输出这一句。";

    private static volatile boolean tried = false;
    private static volatile String loadedPath = null;

    /** 设置页里看到的提示词：用户改过就用改过的。 */
    public static String rulesText(Context ctx) {
        String custom = Prefs.getString(ctx, Prefs.K_PROMPT_LOCAL, "");
        return custom == null || custom.trim().isEmpty() ? DEFAULT_SYSTEM : custom;
    }

    public static String defaultRules() {
        return DEFAULT_SYSTEM;
    }

    private LlmPolisher() {
    }

    /** 模型在就返回 true，顺便把它加载起来。 */
    public static boolean ensureLoaded(Context ctx) {
        String path = file(ctx).getAbsolutePath();
        if (loadedPath != null && !loadedPath.equals(path)) {
            // 用户换档位了，把旧的卸掉再加载新的
            LocalLlm.release();
            loadedPath = null;
        }
        if (LocalLlm.load(path)) {
            tried = true;
            loadedPath = path;
            return true;
        }
        return false;
    }

    private static File file(Context ctx) {
        return LocalLlmModel.file(ctx);
    }

    public static String polish(Context ctx, String raw) {
        if (raw == null || raw.trim().isEmpty()) return raw;
        if (!ensureLoaded(ctx)) return "";
        int maxTokens = Math.max(48, Math.min(256, raw.length() * 3 + 24));
        String out = LocalLlm.generate(rulesText(ctx), raw, maxTokens);
        out = clean(out, raw);
        return out;
    }

    private static String clean(String text, String raw) {
        if (text == null) return "";
        String t = text.trim();
        String[] prefixes = {"整理后：", "整理后:", "整理结果：", "输出：", "以下是整理"};
        for (String p : prefixes) {
            if (t.startsWith(p)) t = t.substring(p.length()).trim();
        }
        while (t.startsWith("\"") && t.endsWith("\"") && t.length() > 2) {
            t = t.substring(1, t.length() - 1).trim();
        }
        // 模型爱啰嗦，明显扩写了就不要，宁可用规则清理
        if (t.length() > raw.length() * 1.8 + 20) return "";
        return TextPolisher.tidy(t);
    }
}
