package com.minivoice.ime.data;

import android.content.Context;

import com.minivoice.ime.settings.Prefs;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 个人词库和快捷输入的本地存储。
 * 一条内容就是：缩写（打这几个字母就出）+ 名称（候选里显示的）+ 正文（真正上屏的）。
 * 全部躺在 filesDir/phrases.json 里，不联网也能用。
 */
public final class PhraseStore {

    /** 个人词库：发票抬头、税号、手机号这类自己的固定信息。 */
    public static final String TYPE_PERSONAL = "personal";
    /** 快捷输入：常用回复、地址、套话这类整段文字。 */
    public static final String TYPE_QUICK = "quick";

    public static class Phrase {
        public String code = "";
        public String label = "";
        public String text = "";
        /** 个人词库专用：这个词常被识别成的样子，多个用逗号隔开。 */
        public String alias = "";
        public String type = TYPE_PERSONAL;

        public Phrase() {
        }

        public Phrase(String code, String label, String text, String type) {
            this.code = code == null ? "" : code;
            this.label = label == null ? "" : label;
            this.text = text == null ? "" : text;
            this.type = type == null ? TYPE_PERSONAL : type;
        }

        public boolean isEmpty() {
            return text == null || text.trim().isEmpty();
        }
    }

    private static final Gson GSON = new Gson();
    private static List<Phrase> cache;

    private PhraseStore() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "phrases.json");
    }

    public static synchronized List<Phrase> all(Context ctx) {
        if (cache != null) return new ArrayList<>(cache);
        cache = new ArrayList<>();
        try {
            File f = file(ctx);
            if (f.exists()) {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                List<Phrase> list = GSON.fromJson(s, new TypeToken<List<Phrase>>() {
                }.getType());
                if (list != null) {
                    for (Phrase p : list) {
                        if (p == null || p.isEmpty()) continue;
                        cache.add(p);
                    }
                }
            }
        } catch (Exception ignore) {
            // 文件坏了就当空的，别让输入法起不来
        }
        migrateLegacy(ctx);
        return new ArrayList<>(cache);
    }

    public static synchronized List<Phrase> byType(Context ctx, String type) {
        List<Phrase> out = new ArrayList<>();
        for (Phrase p : all(ctx)) {
            if (type.equals(p.type)) out.add(p);
        }
        return out;
    }

    public static synchronized void save(Context ctx, List<Phrase> list) {
        cache = new ArrayList<>();
        for (Phrase p : list) {
            if (p != null && !p.isEmpty()) cache.add(p);
        }
        try {
            Files.write(file(ctx).toPath(),
                    GSON.toJson(cache).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) {
        }
    }

    private static void writeFile(Context ctx) {
        try {
            Files.write(file(ctx).toPath(), GSON.toJson(cache).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) {
        }
    }

    /** 老版本的个人词库其实存的是「缩写 + 整段正文」，升级后统一挪到快捷输入。 */
    private static void migrateLegacy(Context ctx) {
        if (Prefs.getBool(ctx, "phrase_personal_migrated_v55", false)) return;
        boolean changed = false;
        for (Phrase p : cache) {
            if (TYPE_PERSONAL.equals(p.type) && p.code != null && !p.code.isEmpty()) {
                p.type = TYPE_QUICK;
                changed = true;
            }
        }
        if (changed) writeFile(ctx);
        Prefs.putBool(ctx, "phrase_personal_migrated_v55", true);
    }

    public static synchronized void addAll(Context ctx, List<Phrase> news) {
        List<Phrase> list = all(ctx);
        for (Phrase p : news) {
            if (p == null || p.isEmpty()) continue;
            for (int i = 0; i < list.size(); i++) {
                Phrase old = list.get(i);
                // 个人词库按词语本身去重，快捷输入按缩写去重
                boolean same = TYPE_PERSONAL.equals(p.type)
                        ? p.text.equalsIgnoreCase(old.text)
                        : (!p.code.isEmpty() && p.code.equalsIgnoreCase(old.code));
                if (same) {
                    list.remove(i);
                    break;
                }
            }
            list.add(p);
        }
        save(ctx, list);
    }

    public static synchronized void remove(Context ctx, Phrase p) {
        List<Phrase> list = all(ctx);
        for (int i = 0; i < list.size(); i++) {
            Phrase a = list.get(i);
            if (a.code.equals(p.code) && a.label.equals(p.label) && a.text.equals(p.text)) {
                list.remove(i);
                break;
            }
        }
        save(ctx, list);
    }

    /** 个人词库里所有正确写法，拿去做识别校准。 */
    public static List<String> personalTerms(Context ctx) {
        List<String> out = new ArrayList<>();
        for (Phrase p : byType(ctx, TYPE_PERSONAL)) {
            if (p.text != null && !p.text.trim().isEmpty()) out.add(p.text.trim());
        }
        return out;
    }

    /** 打字时用：按缩写前缀找，先匹配到的先出。 */
    public static synchronized List<Phrase> match(Context ctx, String letters, int max) {
        return matchIn(all(ctx), letters, max);
    }

    /** 纯逻辑的匹配，输入法里和测试都走这里。 */
    public static List<Phrase> matchIn(List<Phrase> pool, String letters, int max) {
        List<Phrase> out = new ArrayList<>();
        if (letters == null || letters.isEmpty()) return out;
        String q = letters.toLowerCase(Locale.ROOT);
        for (Phrase p : pool) {
            if (p.code == null || p.code.isEmpty()) continue;
            String c = p.code.toLowerCase(Locale.ROOT);
            // 必须把缩写完整打完才出，只打一半不出
            if (c.equals(q)) {
                out.add(p);
                if (out.size() >= max) break;
            }
        }
        return out;
    }
}
