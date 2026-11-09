package com.minivoice.ime.asr;

import android.content.Context;

import java.io.File;

/** 用手机里的 Qwen2.5-0.5B 整理口语，纯离线，慢一点但是不联网。 */
public final class LlmPolisher {

    private static final String SYSTEM =
            "你是输入法的文字整理助手。用户给的是语音识别原文，口语化，可能有识别错误。你的任务是把它改写成通顺的书面句子。"
                    + "\n\n示例："
                    + "\n原文：嗯那个我今天下午大概三点左右吧去那个福田的 apple 店看一下 iphone 的新机子啊顺便问问 wifi 那个问题"
                    + "\n整理：我今天下午三点左右去福田的 Apple 店看 iPhone 新机，顺便问一下 WiFi 的问题。"
                    + "\n\n要求："
                    + "\n1. 只输出整理后的句子，一行，不要前缀，不要引号，不要解释。"
                    + "\n2. 删掉「嗯」「啊」「那个」「就是说」「大概」「吧」这类口语填充词，重复的词合并。"
                    + "\n3. 把口语改成书面表达，语序理顺，该断句就断句，绝对不要照抄原文。"
                    + "\n4. 中英混杂的名词按常见写法输出，比如 iPhone、WiFi、Type-C、Pro，不要翻译成中文，也不要改成别的词。"
                    + "\n5. 明显的同音错字按上下文改对，人名、地名、专业词不乱改。"
                    + "\n6. 意思和语气不能变，不要扩写，不要加评论和总结。"
                    + "\n7. 原文里说到的每件事、每个信息点都要留下，不能因为嫌啰嗦就把内容砍掉。"
                    + "\n8. 整理后的长度应该跟原文差不多，最多只能短一成左右，明显变短就说明你删多了，这是不允许的。"
                    + "\n9. 宁可保留一点口语痕迹，也不许把意思压没。";

    private static volatile boolean tried = false;
    private static volatile String loadedPath = null;

    /** 给设置页看整理规则用。 */
    public static String rulesText() {
        return SYSTEM;
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
