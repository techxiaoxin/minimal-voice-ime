package com.minivoice.ime.asr;

import android.content.Context;

import com.minivoice.ime.settings.Prefs;

import java.io.File;

/** 用手机里的 Qwen2.5-0.5B 整理口语，纯离线，慢一点但是不联网。 */
public final class LlmPolisher {

    private static final String DEFAULT_SYSTEM =
            "帮我整理这段语音转文字：只删语气词和重复，有实际意思的词都保留，不改说法、不压短，"
                    + "只改错别字和标点，需要时按内容分段。只输出正文。";

    private static volatile boolean tried = false;
    private static volatile String loadedPath = null;

    /** 设置页里看到的提示词：用户改过就用改过的。 */
    public static String rulesText(Context ctx) {
        if (!Prefs.getBool(ctx, "polish_rule_local_reset_v50", false)) {
            Prefs.putString(ctx, Prefs.K_PROMPT_LOCAL, DEFAULT_SYSTEM);
            Prefs.putBool(ctx, "polish_rule_local_reset_v50", true);
            return DEFAULT_SYSTEM;
        }
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
        int maxTokens = Math.max(64, Math.min(512, raw.length() * 3 + 48));

        // 小模型吃不动太复杂的长指令，把要求写进用户消息里，再用一句话兜一次。
        String ask = "把下面这段话整理成通顺的书面语：去掉语气词和重复，补上标点，不要总结、不要删有实际意思的词。只输出整理后的正文：\n" + raw;
        String out = clean(LocalLlm.generate(rulesText(ctx), ask, maxTokens), raw);

        // 没吐东西，或者基本就是把原文复读一遍，说明这轮没干活，换更直白的说法再来一次
        if (!worked(out, raw)) {
            String ask2 = "整理下面这句话：删掉嗯啊呃这类语气词和重复的词，补上标点。只输出整理后的正文：\n" + raw;
            out = clean(LocalLlm.generate("你是中文文字整理助手。", ask2, maxTokens), raw);
        }
        // 两次都没干活就当失败，交给上层的规则清理兜底
        if (!worked(out, raw)) return "";
        return out;
    }

    /** 判断这次到底有没有整理：空结果、和原文一模一样、还是那副没标点的样子，都算没干活。 */
    private static boolean worked(String out, String raw) {
        if (out == null || out.trim().isEmpty()) return false;
        String a = raw.trim();
        String b = out.trim();
        if (a.equals(b)) return false;
        if (b.length() >= 8 && !hasPunct(b) && !hasPunct(a)) return false;
        return true;
    }

    private static boolean hasPunct(String s) {
        for (int i = 0; i < s.length(); i++) {
            if ("，。？！；：、".indexOf(s.charAt(i)) >= 0) return true;
        }
        return false;
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
