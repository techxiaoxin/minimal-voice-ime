package com.minivoice.ime.input;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 全键盘打中文用的离线拼音表，词和单字都从 assets 里读，第一次用的时候加载一次。 */
public class PinyinDict {

    private static PinyinDict inst;

    private final Map<String, String[]> words = new LinkedHashMap<>();
    private final Map<String, String> chars = new HashMap<>();

    public static synchronized PinyinDict get(Context ctx) {
        if (inst == null) inst = new PinyinDict(ctx.getApplicationContext());
        return inst;
    }

    private PinyinDict(Context ctx) {
        load(ctx, "pinyin_words.txt", true);
        load(ctx, "pinyin_chars.txt", false);
    }

    private void load(Context ctx, String name, boolean isWord) {
        InputStream in = null;
        BufferedReader r = null;
        try {
            in = ctx.getAssets().open(name);
            r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String py = line.substring(0, tab).trim();
                String rest = line.substring(tab + 1).trim();
                if (py.isEmpty() || rest.isEmpty()) continue;
                if (isWord) {
                    words.put(py, rest.split(" "));
                } else {
                    chars.put(py, rest);
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {
            }
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 一次输入的一串字母，先给整词，再给以这串字母开头的词，最后补单字。 */
    public List<String> candidates(String letters, int max) {
        List<String> out = new ArrayList<>();
        if (letters == null || letters.isEmpty()) return out;

        String[] exact = words.get(letters);
        if (exact != null) {
            for (String w : exact) {
                if (out.size() >= max) break;
                out.add(w);
            }
        }
        if (out.size() < max) {
            for (Map.Entry<String, String[]> e : words.entrySet()) {
                String key = e.getKey();
                if (key.length() <= letters.length() || !key.startsWith(letters)) continue;
                for (String w : e.getValue()) {
                    if (out.size() >= max) break;
                    out.add(w);
                }
                if (out.size() >= max) break;
            }
        }
        String chs = chars.get(letters);
        if (chs != null) {
            for (int i = 0; i < chs.length(); i++) {
                if (out.size() >= max) break;
                out.add(String.valueOf(chs.charAt(i)));
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(out));
    }
}
