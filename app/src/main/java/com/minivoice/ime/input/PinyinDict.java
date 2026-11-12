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
    private final Map<String, String> chars = new LinkedHashMap<>();
    private final Set<String> syllables = new HashSet<>();
    /** 首字母缩写到词，比如 nh -> 你好，xx -> 谢谢。 */
    private final Map<String, List<String>> abbrWords = new LinkedHashMap<>();
    /** 每个声母下最常用的字，缩写拼不出来时用它兜底。 */
    private final Map<Character, List<String>> initialChars = new HashMap<>();
    private final Map<Character, Integer> charWeight = new HashMap<>();
    private final Map<Character, Integer> startWeight = new HashMap<>();

    /** 词库里没有、但日常最常用的几个缩写，优先给出来。 */
    private static final Map<String, String> COMMON_ABB = new HashMap<>();
    /** 键盘上每个键周围一圈，用来纠正按偏一格的情况。 */
    private static final Map<Character, String> KEY_NEIGHBORS = new HashMap<>();

    static {
        COMMON_ABB.put("hd", "好的");
        COMMON_ABB.put("hh", "哈哈");
        COMMON_ABB.put("hhh", "哈哈哈");
        COMMON_ABB.put("hhhh", "哈哈哈哈");
        COMMON_ABB.put("nh", "你好");
        COMMON_ABB.put("zd", "知道");
        COMMON_ABB.put("sm", "什么");
        COMMON_ABB.put("zm", "怎么");
        COMMON_ABB.put("wm", "我们");
        COMMON_ABB.put("nm", "你们");
        COMMON_ABB.put("tm", "他们");
        COMMON_ABB.put("zj", "再见");
        COMMON_ABB.put("xx", "谢谢");
        COMMON_ABB.put("bq", "抱歉");
        COMMON_ABB.put("sd", "收到");
        COMMON_ABB.put("dd", "等等");
        COMMON_ABB.put("mm", "慢慢");

        KEY_NEIGHBORS.put('q', "wa");
        KEY_NEIGHBORS.put('w', "qeasd");
        KEY_NEIGHBORS.put('e', "wrsdf");
        KEY_NEIGHBORS.put('r', "etdfg");
        KEY_NEIGHBORS.put('t', "ryfgh");
        KEY_NEIGHBORS.put('y', "tughj");
        KEY_NEIGHBORS.put('u', "yhjik");
        KEY_NEIGHBORS.put('i', "ujklo");
        KEY_NEIGHBORS.put('o', "ikpl");
        KEY_NEIGHBORS.put('p', "ol");
        KEY_NEIGHBORS.put('a', "qwsxz");
        KEY_NEIGHBORS.put('s', "qwedxza");
        KEY_NEIGHBORS.put('d', "wersfca");
        KEY_NEIGHBORS.put('f', "ertdgvc");
        KEY_NEIGHBORS.put('g', "tyuhbvf");
        KEY_NEIGHBORS.put('h', "yuijnbg");
        KEY_NEIGHBORS.put('j', "uiknhvc");
        KEY_NEIGHBORS.put('k', "iolmjh");
        KEY_NEIGHBORS.put('l', "opk");
        KEY_NEIGHBORS.put('z', "asx");
        KEY_NEIGHBORS.put('x', "zsdc");
        KEY_NEIGHBORS.put('c', "xdfv");
        KEY_NEIGHBORS.put('v', "cfgb");
        KEY_NEIGHBORS.put('b', "vghn");
        KEY_NEIGHBORS.put('n', "bhjm");
        KEY_NEIGHBORS.put('m', "njk");
    }

    public static synchronized PinyinDict get(Context ctx) {
        if (inst == null) inst = new PinyinDict(ctx.getApplicationContext());
        return inst;
    }

    private PinyinDict(Context ctx) {
        load(ctx, "pinyin_words.txt", true);
        load(ctx, "pinyin_chars.txt", false);
        buildIndexes();
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

    private void buildIndexes() {
        for (String[] ws : words.values()) {
            for (String w : ws) {
                if (w.isEmpty()) continue;
                startWeight.merge(w.charAt(0), 1, Integer::sum);
                for (int i = 0; i < w.length(); i++) {
                    char c = w.charAt(i);
                    if (c >= 0x4e00 && c <= 0x9fff) {
                        charWeight.merge(c, 1, Integer::sum);
                    }
                }
            }
        }

        for (Map.Entry<String, String> e : chars.entrySet()) {
            String py = e.getKey();
            if (py.isEmpty()) continue;
            char ini = py.charAt(0);
            List<String> pool = initialChars.get(ini);
            if (pool == null) {
                pool = new ArrayList<>();
                initialChars.put(ini, pool);
            }
            for (int i = 0; i < e.getValue().length(); i++) {
                String ch = String.valueOf(e.getValue().charAt(i));
                if (!pool.contains(ch)) pool.add(ch);
            }
        }
        for (List<String> pool : initialChars.values()) {
            pool.sort((a, b) -> {
                int sa = startWeight.getOrDefault(a.charAt(0), 0) * 4
                        + charWeight.getOrDefault(a.charAt(0), 0);
                int sb = startWeight.getOrDefault(b.charAt(0), 0) * 4
                        + charWeight.getOrDefault(b.charAt(0), 0);
                return Integer.compare(sb, sa);
            });
        }

        for (Map.Entry<String, String[]> e : words.entrySet()) {
            List<String> pieces = segment(e.getKey());
            if (pieces.size() < 2 || pieces.size() > 8) continue;
            StringBuilder ab = new StringBuilder();
            for (String p : pieces) ab.append(p.charAt(0));
            String key = ab.toString();
            List<String> list = abbrWords.get(key);
            if (list == null) {
                list = new ArrayList<>();
                abbrWords.put(key, list);
            }
            for (String w : e.getValue()) {
                if (w.length() != pieces.size() || list.contains(w)) continue;
                if (list.size() >= 12) break;
                list.add(w);
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

        addExactWords(letters, out, max);
        addPrefixWords(letters, out, max);

        // 像 hd、hhh 这种首字母缩写，优先给常用词，再给词库里能对上的缩写词
        addExactAbbr(letters, out, max);

        // 按错一格、前后颠倒或常见拼音混淆时，补一批纠错候选
        addFuzzyCorrections(letters, out, max);

        // 词库里没有对应缩写时，用每个声母下最高频的字拼一个兜底
        addAbbrCombos(letters, out, max);

        if (out.size() < max) {
            for (String combo : combos(letters, max - out.size())) addIfNew(out, combo, max);
        }
        if (out.size() < max) {
            String chs = chars.get(letters);
            if (chs != null) {
                for (int i = 0; i < chs.length(); i++) {
                    if (out.size() >= max) break;
                    addIfNew(out, String.valueOf(chs.charAt(i)), max);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private void addExactWords(String letters, List<String> out, int max) {
        String[] exact = words.get(letters);
        if (exact == null) return;
        for (String w : exact) addIfNew(out, w, max);
    }

    private void addPrefixWords(String letters, List<String> out, int max) {
        String bk = letters.length() > 3 ? letters.substring(0, 3) : letters;
        List<W> b = buckets.get(bk);
        if (b == null) return;
        for (W e : b) {
            if (e.py.length() == letters.length() || !e.py.startsWith(letters)) continue;
            for (String w : e.words) {
                if (out.size() >= max) return;
                addIfNew(out, w, max);
            }
        }
    }

    private void addExactAbbr(String letters, List<String> out, int max) {
        if (!looksLikeInitials(letters)) return;
        String common = COMMON_ABB.get(letters);
        if (common != null) addIfNew(out, common, max);
        List<String> hit = abbrWords.get(letters);
        if (hit == null) return;
        for (String w : hit) addIfNew(out, w, max);
    }

    /** 首字母缩写没有词库命中时，按声母常用字拼出几个短词。 */
    private void addAbbrCombos(String letters, List<String> out, int max) {
        if (!looksLikeInitials(letters) || letters.length() > 3) return;
        // 已经有词库缩写或常用缩写时不再硬拼，避免把长串全拼错字拆成怪词
        if (out.size() >= 2) return;
        List<List<String>> options = new ArrayList<>();
        for (int i = 0; i < letters.length(); i++) {
            List<String> pool = initialChars.get(letters.charAt(i));
            if (pool == null || pool.isEmpty()) return;
            options.add(new ArrayList<>(pool.subList(0, Math.min(6, pool.size()))));
        }
        List<String> res = new ArrayList<>();
        buildCombo(options, 0, new StringBuilder(), res, Math.min(10, max - out.size()));
        for (String w : res) addIfNew(out, w, max);
    }

    /** 单键邻键、相邻换位和常见模糊音，修正一次输入里的轻微错键。 */
    private void addFuzzyCorrections(String letters, List<String> out, int max) {
        if (letters.length() < 2 || letters.length() > 12) return;
        Set<String> variants = new LinkedHashSet<>();
        for (int i = 0; i < letters.length(); i++) {
            char c = letters.charAt(i);
            String near = KEY_NEIGHBORS.get(c);
            if (near == null) continue;
            for (int j = 0; j < near.length(); j++) {
                StringBuilder sb = new StringBuilder(letters);
                sb.setCharAt(i, near.charAt(j));
                variants.add(sb.toString());
            }
            if (i + 1 < letters.length()) {
                StringBuilder sb = new StringBuilder(letters);
                sb.setCharAt(i, letters.charAt(i + 1));
                sb.setCharAt(i + 1, c);
                variants.add(sb.toString());
            }
        }
        addPinyinVariants(letters, variants);
        for (String v : variants) {
            if (out.size() >= max) return;
            int limit = Math.min(max, out.size() + 3);
            addExactWords(v, out, limit);
            addPrefixWords(v, out, limit);
            addExactAbbr(v, out, limit);
        }
    }

    private void addPinyinVariants(String letters, Set<String> variants) {
        String[][] pairs = {
                {"zh", "z"}, {"ch", "c"}, {"sh", "s"},
                {"ang", "an"}, {"eng", "en"}, {"ing", "in"},
                {"iang", "ian"}, {"uang", "uan"}, {"ou", "uo"},
                {"n", "l"}, {"l", "n"}, {"f", "h"}
        };
        for (String[] p : pairs) {
            if (letters.contains(p[0])) {
                variants.add(letters.replace(p[0], p[1]));
            }
        }
    }

    private boolean looksLikeInitials(String letters) {
        if (letters == null || letters.length() < 2 || letters.length() > 8) return false;
        for (int i = 0; i < letters.length(); i++) {
            char c = letters.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        return true;
    }

    private void addIfNew(List<String> out, String w, int max) {
        if (w == null || w.isEmpty() || out.size() >= max) return;
        if (!out.contains(w)) out.add(w);
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
