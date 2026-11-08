package com.minivoice.ime.asr;

import android.content.Context;

import com.minivoice.ime.settings.Prefs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 名词和英文大小写的收尾修正。
 *
 * 语音识别把名字、术语念错一个字很常见，模型也没法保证 CPU、iPhone 这种写法。
 * 这里用用户自己填的固定名词 + 一份常见缩写和品牌名，做纯本地的近似替换，
 * 不联网，两种识别方式都生效。
 */
public final class TermFix {

    /** 全大写缩写的标准写法。 */
    private static final Map<String, String> ACRONYMS = new LinkedHashMap<>();

    /** 品牌和产品名的标准写法，比如 iPhone 不是 Iphone。 */
    private static final Map<String, String> BRANDS = new LinkedHashMap<>();

    static {
        String[] upper = {"cpu", "gpu", "npu", "ram", "rom", "ssd", "hdd", "usb", "hdmi",
                "api", "sdk", "ui", "ux", "ai", "asr", "tts", "ime", "ota", "nfc", "wifi",
                "gps", "lte", "hdr", "oled", "lcd", "fps", "dpi", "ppi", "pc", "tv", "vr",
                "ar", "api", "soc", "bios", "dns", "cdn", "http", "https", "json", "pdf"};
        for (String s : upper) ACRONYMS.put(s, s.toUpperCase());

        BRANDS.put("iphone", "iPhone");
        BRANDS.put("ipad", "iPad");
        BRANDS.put("ipod", "iPod");
        BRANDS.put("ios", "iOS");
        BRANDS.put("macos", "macOS");
        BRANDS.put("macbook", "MacBook");
        BRANDS.put("imac", "iMac");
        BRANDS.put("airpods", "AirPods");
        BRANDS.put("app store", "App Store");
        BRANDS.put("wechat", "WeChat");
        BRANDS.put("douyin", "抖音");
        BRANDS.put("bilibili", "B站");
        BRANDS.put("huawei", "华为");
        BRANDS.put("xiaomi", "小米");
        BRANDS.put("honor", "荣耀");
        BRANDS.put("oppo", "OPPO");
        BRANDS.put("vivo", "vivo");
        BRANDS.put("oneplus", "一加");
        BRANDS.put("android", "Android");
        BRANDS.put("windows", "Windows");
        BRANDS.put("google", "Google");
        BRANDS.put("openai", "OpenAI");
        BRANDS.put("chatgpt", "ChatGPT");
        BRANDS.put("gpt", "GPT");
        BRANDS.put("claude", "Claude");
        BRANDS.put("deepseek", "DeepSeek");
        BRANDS.put("doubao", "豆包");
        BRANDS.put("tesla", "Tesla");
        BRANDS.put("nvidia", "NVIDIA");
        BRANDS.put("qualcomm", "Qualcomm");
    }

    private TermFix() {
    }

    public static String apply(Context ctx, String text) {
        if (text == null || text.isEmpty()) return text;
        List<String> vocab = Prefs.getWordList(ctx);
        return apply(text, vocab);
    }

    public static String apply(String text, List<String> vocab) {
        if (text == null) return "";
        String out = text;

        // 1. 用户自己填的固定名词：出现读错一个字的情况就地改回来
        if (vocab != null) {
            for (String term : vocab) {
                if (term == null) continue;
                String t = term.trim();
                if (t.isEmpty()) continue;
                out = fixNearTerm(out, t);
            }
        }

        // 2. 常见缩写统一成大写，品牌名统一成官方写法
        out = fixWordCase(out, ACRONYMS);
        out = fixWordCase(out, BRANDS);

        // 3. 英文单词在句首时首字母大写，句中不动，避免把中文句子弄乱
        out = capitalizeSentenceStart(out);
        return out;
    }

    /** 同一个长度内只差一个字的片段，按固定名词纠正。中文才有意义，英文靠大小写规则处理。 */
    private static String fixNearTerm(String text, String term) {
        if (hasCjk(term)) {
            int n = term.codePointCount(0, term.length());
            if (n < 2 || text.contains(term)) return text;
            int[] chars = text.codePoints().toArray();
            for (int i = 0; i + n <= chars.length; i++) {
                String win = new String(chars, i, n);
                if (!hasCjk(win)) continue;
                if (distance(win, term) == 1) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(new String(chars, 0, i));
                    sb.append(term);
                    sb.append(new String(chars, i + n, chars.length - i - n));
                    return sb.toString();
                }
            }
        }
        return text;
    }

    private static String fixWordCase(String text, Map<String, String> map) {
        String out = text;
        for (Map.Entry<String, String> e : map.entrySet()) {
            String key = e.getKey();
            String canon = e.getValue();
            // 只替换全小写形式，避免把已经写对的再改坏
            Pattern p = Pattern.compile("(?<![A-Za-z])" + Pattern.quote(key) + "(?![A-Za-z])");
            Matcher m = p.matcher(out);
            out = m.replaceAll(Matcher.quoteReplacement(canon));
        }
        return out;
    }

    /** 句首的英文单词首字母大写，像 hello there 变成 Hello there。 */
    private static String capitalizeSentenceStart(String text) {
        Pattern p = Pattern.compile("(^|[。！？\\n]\\s*)([a-z][a-z']{1,})");
        Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String head = m.group(1);
            String word = m.group(2);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    head + Character.toUpperCase(word.charAt(0)) + word.substring(1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    /** 编辑距离，只用来判断“是不是差一个字”。 */
    private static int distance(String a, String b) {
        int n = a.length(), m = b.length();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) dp[i][0] = i;
        for (int j = 0; j <= m; j++) dp[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[n][m];
    }
}
