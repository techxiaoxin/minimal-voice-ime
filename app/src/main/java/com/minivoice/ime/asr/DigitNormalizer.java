package com.minivoice.ime.asr;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把语音识别出来的中文数字整理成阿拉伯数字。
 *
 * 比如念「一百二十三」，识别结果常是「一百二十三」，这里按上下文能稳妥转换的片段
 * 转成「123」。单字的「一、二、三」和量词前面的数字尽量保留，避免把「一个人」
 * 「三个月」这种说法改坏。
 */
public final class DigitNormalizer {

    private static final Map<Character, Integer> DIGIT = new LinkedHashMap<>();
    private static final Map<Character, Long> UNIT = new LinkedHashMap<>();

    static {
        DIGIT.put('零', 0); DIGIT.put('〇', 0); DIGIT.put('一', 1); DIGIT.put('壹', 1);
        DIGIT.put('二', 2); DIGIT.put('贰', 2); DIGIT.put('两', 2); DIGIT.put('三', 3);
        DIGIT.put('叁', 3); DIGIT.put('四', 4); DIGIT.put('肆', 4); DIGIT.put('五', 5);
        DIGIT.put('伍', 5); DIGIT.put('六', 6); DIGIT.put('陆', 6); DIGIT.put('七', 7);
        DIGIT.put('柒', 7); DIGIT.put('八', 8); DIGIT.put('捌', 8); DIGIT.put('九', 9);
        DIGIT.put('玖', 9);

        UNIT.put('十', 10L); UNIT.put('百', 100L); UNIT.put('千', 1000L);
        UNIT.put('万', 10000L); UNIT.put('亿', 100000000L);
    }

    private static final Pattern CHUNK = Pattern.compile(
            "[零〇一壹二贰两三四五六七八九叁肆伍陆柒捌玖十百千万亿]+");

    private DigitNormalizer() {
    }

    public static String toArabic(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = CHUNK.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String chunk = m.group();
            Long value = parse(chunk);
            boolean ok = value != null && value > 0 && convertible(chunk);
            // 序数「第一、第二」不转
            if (ok && m.start() > 0 && text.charAt(m.start() - 1) == '第') ok = false;
            m.appendReplacement(sb, ok ? String.valueOf(value) : chunk);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 含数量单位，或三个字以上的连续数字说法，才值得转；单字「一」这类保留。 */
    private static boolean convertible(String chunk) {
        if (chunk.length() >= 3) return true;
        for (int i = 0; i < chunk.length(); i++) {
            if (UNIT.containsKey(chunk.charAt(i))) return true;
        }
        return false;
    }

    /** 解析一段纯中文数字，解析不出来返回 null。 */
    private static Long parse(String s) {
        long total = 0, base = 0, digit = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Integer d = DIGIT.get(c);
            if (d != null) {
                digit = d;
                continue;
            }
            Long u = UNIT.get(c);
            if (u == null) return null;
            if (u == 10000L || u == 100000000L) {
                total += (base + digit) * u;
                base = 0;
                digit = 0;
            } else {
                if (digit == 0) digit = 1;
                base += digit * u;
                digit = 0;
            }
        }
        return total + base + digit;
    }
}
