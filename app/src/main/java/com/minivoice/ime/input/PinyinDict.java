package com.minivoice.ime.input;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 全键盘打中文用的离线拼音表，词和单字都从 assets 里读，第一次用的时候加载一次。 */
public class PinyinDict {

    private static PinyinDict inst;

    private final Map<String, String[]> words = new LinkedHashMap<>();
    /** 按前三个字母分桶，桶内保持词表原来的高频顺序，前缀查询只看自己那一小撮。 */
    private static final class W {
        final String py;
        final String[] words;

        W(String py, String[] words) {
            this.py = py;
            this.words = words;
        }
    }

    private final Map<String, List<W>> buckets = new HashMap<>();
    private String cacheKey = "";
    private List<String> cacheVal = null;
    private final Map<String, String> chars = new HashMap<>();
    private final Set<String> syllables = new HashSet<>();

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
                    String[] ws = rest.split(" ");
                    words.put(py, ws);
                    String bk = py.length() > 3 ? py.substring(0, 3) : py;
                    List<W> b = buckets.get(bk);
                    if (b == null) {
                        b = new ArrayList<>();
                        buckets.put(bk, b);
                    }
                    b.add(new W(py, ws));
                } else {
                    chars.put(py, rest);
                    syllables.add(py);
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

    /** 一次输入的一串字母：整词、前缀词、自动分词的词组，最后补单字。 */
    public List<String> candidates(String letters, int max) {
        List<String> out = new ArrayList<>();
        if (letters == null || letters.isEmpty()) return out;
        if (letters.equals(cacheKey) && cacheVal != null) {
            return new ArrayList<>(cacheVal);
        }
        List<String> res = build(letters, max);
        cacheKey = letters;
        cacheVal = res;
        return new ArrayList<>(res);
    }

    private List<String> build(String letters, int max) {
        List<String> out = new ArrayList<>();

        String[] exact = words.get(letters);
        if (exact != null) {
            for (String w : exact) {
                if (out.size() >= max) break;
                out.add(w);
            }
        }
        if (out.size() < max) {
            String bk = letters.length() > 3 ? letters.substring(0, 3) : letters;
            List<W> b = buckets.get(bk);
            if (b != null) {
                for (W e : b) {
                    if (e.py.length() == letters.length() || !e.py.startsWith(letters)) continue;
                    for (String w : e.words) {
                        if (out.size() >= max) break;
                        out.add(w);
                    }
                    if (out.size() >= max) break;
                }
            }
        }
        if (out.size() < max) {
            for (String combo : combos(letters, max - out.size())) out.add(combo);
        }
        if (out.size() < max) {
            String chs = chars.get(letters);
            if (chs != null) {
                for (int i = 0; i < chs.length(); i++) {
                    if (out.size() >= max) break;
                    out.add(String.valueOf(chs.charAt(i)));
                }
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    /** 打一长串不带空格的拼音时，先按词库切成音节，再把每段的高频字拼起来。 */
    private List<String> combos(String letters, int max) {
        List<String> pieces = segment(letters);
        if (pieces.size() < 2) return new ArrayList<>();

        List<List<String>> options = new ArrayList<>();
        for (String p : pieces) {
            List<String> one = new ArrayList<>();
            String[] ws = words.get(p);
            if (ws != null) {
                for (String w : ws) {
                    if (one.size() >= 3) break;
                    one.add(w);
                }
            }
            String cs = chars.get(p);
            if (cs != null) {
                for (int i = 0; i < cs.length() && one.size() < 6; i++) {
                    one.add(String.valueOf(cs.charAt(i)));
                }
            }
            if (one.isEmpty()) return new ArrayList<>();
            options.add(one);
        }

        List<String> res = new ArrayList<>();
        buildCombo(options, 0, new StringBuilder(), res, max);
        return res;
    }

    private void buildCombo(List<List<String>> options, int idx, StringBuilder sb,
                            List<String> res, int max) {
        if (res.size() >= max) return;
        if (idx == options.size()) {
            res.add(sb.toString());
            return;
        }
        for (String s : options.get(idx)) {
            int len = sb.length();
            sb.append(s);
            buildCombo(options, idx + 1, sb, res, max);
            sb.setLength(len);
            if (res.size() >= max) return;
        }
    }

    /** 动态规划切片：先保证切的刀数最少，再优先落在词库有的拼音上。 */
    private List<String> segment(String letters) {
        int n = letters.length();
        long neg = Long.MIN_VALUE / 4;
        long[] score = new long[n + 1];
        int[] cut = new int[n + 1];
        int[] cnt = new int[n + 1];
        int[] bonus = new int[n + 1];
        int[] lenSum = new int[n + 1];
        int[] cutLen = new int[n + 1];
        for (int i = 1; i <= n; i++) score[i] = neg;
        for (int i = 1; i <= n; i++) {
            for (int len = 1; len <= 6 && len <= i; len++) {
                String seg = letters.substring(i - len, i);
                boolean isWord = words.containsKey(seg);
                boolean isSyl = syllables.contains(seg);
                if (!isWord && !isSyl) continue;
                if (score[i - len] <= neg) continue;
                int c = cnt[i - len] + 1;
                int b = bonus[i - len] + (isWord ? 1 : 0);
                int l = lenSum[i - len] + len;
                long v = -c * 1000L + b * 10L + l;
                if (v > score[i] || (v == score[i] && cutLen[i] > len)) {
                    score[i] = v;
                    cut[i] = i - len;
                    cnt[i] = c;
                    bonus[i] = b;
                    lenSum[i] = l;
                    cutLen[i] = len;
                }
            }
        }
        if (score[n] <= neg) return new ArrayList<>();
        List<String> pieces = new ArrayList<>();
        int i = n;
        while (i > 0) {
            int from = cut[i];
            pieces.add(0, letters.substring(from, i));
            i = from;
        }
        return pieces;
    }
}
