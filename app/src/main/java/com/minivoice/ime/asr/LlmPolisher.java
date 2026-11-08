package com.minivoice.ime.asr;

import android.content.Context;

import java.io.File;

/** 用手机里的 Qwen2.5-0.5B 整理口语，纯离线，慢一点但是不联网。 */
public final class LlmPolisher {

    private static final String SYSTEM =
            "你是输入法的文字整理助手。用户给你的是一段语音识别的原文，可能是口语，可能有识别错误。"
                    + "\n要求："
                    + "\n1. 只做整理，不要扩写，不要加解释，不要加引号。"
                    + "\n2. 去掉「嗯」「那个」「就是说」这类口头语，重复的词合并。"
                    + "\n3. 补上合适的标点，句子该断就断。"
                    + "\n4. 明显的同音错字、识别错的词按上下文改对，人名地名专业词不乱改。"
                    + "\n5. 保持原来的意思和语气，不要改写成长篇大论。"
                    + "\n6. 直接输出整理后的文字，一行，不要任何前缀。";

    private static volatile boolean tried = false;

    private LlmPolisher() {
    }

    /** 模型在就返回 true，顺便把它加载起来。 */
    public static boolean ensureLoaded(Context ctx) {
        if (LocalLlm.load(file(ctx).getAbsolutePath())) {
            tried = true;
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
        String out = LocalLlm.generate(SYSTEM, raw, maxTokens);
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
